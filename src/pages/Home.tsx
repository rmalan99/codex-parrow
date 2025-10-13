import React, { useEffect, useRef, useState } from "react";
import {
  IonButton,
  IonButtons,
  IonContent,
  IonHeader,
  IonInput,
  IonItem,
  IonLabel,
  IonList,
  IonNote,
  IonPage,
  IonSpinner,
  IonTitle,
  IonToolbar,
} from "@ionic/react";
import { Capacitor, type PluginListenerHandle } from "@capacitor/core";
import OpenAI from "openai";
import type {
  EvalResult,
  NativeViewport,
  NativeWebViewPlugin,
} from "../capacitor-plugins/native-webview";
import { INJECT_STYLE, EXTRACT_QUESTIONS, PAINT_HIGHLIGHTS } from "../bridge/quiz-js";
import "./Home.css";

type ExtractedOption = {
  text: string;
  selector: string | null;
  labelSelector: string | null;
};

type ExtractedQuestion = {
  questionText: string;
  options: ExtractedOption[];
};

type ExtractedPayload = {
  url: string;
  title: string;
  questions: ExtractedQuestion[];
};

type Answer = {
  selector: string | null;
  labelSelector: string | null;
};

const DEFAULT_URL = "https://example.com/quiz";
const OPENAI_MODEL = import.meta.env.VITE_OPENAI_MODEL ?? "gpt-4o-mini";

const escapeSelector = (value: string) => {
  if (typeof CSS !== "undefined" && typeof CSS.escape === "function") {
    return CSS.escape(value);
  }
  return value.replace(/[^a-zA-Z0-9_-]/g, (char) => `\\${char}`);
};

const cssPath = (element: Element | null): string | null => {
  if (!element) {
    return null;
  }

  const segments: string[] = [];
  let current: Element | null = element;

  while (current && segments.length < 6) {
    let selector = current.nodeName.toLowerCase();
    if (current.id) {
      selector += `#${escapeSelector(current.id)}`;
      segments.unshift(selector);
      break;
    }

    let siblingIndex = 1;
    let sibling = current.previousElementSibling;
    while (sibling) {
      if (sibling.nodeName === current.nodeName) {
        siblingIndex += 1;
      }
      sibling = sibling.previousElementSibling;
    }

    selector += `:nth-of-type(${siblingIndex})`;
    segments.unshift(selector);
    current = current.parentElement;
  }

  return segments.join(" > ");
};

const extractQuestionsFromHtml = (html: string, targetUrl: string): ExtractedPayload => {
  const parser = new DOMParser();
  const doc = parser.parseFromString(html, "text/html");
  const container =
    doc.querySelector('form,[role="form"],[data-quiz],.quiz,.question-container') ?? doc.body;

  const groups = new Map<string, ExtractedQuestion>();
  const inputs = Array.from(
    container.querySelectorAll<HTMLInputElement>('input[type="radio"],input[type="checkbox"]')
  );

  inputs.forEach((input, index) => {
    const groupKey = input.name || `__g${index}`;
    const label =
      (input.id && container.querySelector(`label[for="${input.id}"]`)) || input.closest("label");
    const questionNode =
      input.closest("fieldset,.question,.question-block,.quiz-question") ?? container;
    const legend = questionNode?.querySelector("legend,.question-title,h3,h4,.title");

    if (!groups.has(groupKey)) {
      groups.set(groupKey, {
        questionText: legend?.textContent?.trim() ?? "",
        options: [],
      });
    }

    const group = groups.get(groupKey)!;
    const selector = input.id ? `#${escapeSelector(input.id)}` : cssPath(input);
    group.options.push({
      text: (label?.textContent || input.value || "").trim(),
      selector,
      labelSelector: label ? cssPath(label) : null,
    });
  });

  return {
    url: targetUrl,
    title: doc.title,
    questions: Array.from(groups.values()).filter((group) => group.options.length > 0),
  };
};

const isExtractedPayload = (value: unknown): value is ExtractedPayload => {
  if (!value || typeof value !== "object") {
    return false;
  }
  const candidate = value as Partial<ExtractedPayload>;
  return (
    typeof candidate.url === "string" &&
    typeof candidate.title === "string" &&
    Array.isArray(candidate.questions)
  );
};

const normalizeAnswers = (raw: unknown): Answer[] => {
  if (!raw || typeof raw !== "object") {
    return [];
  }
  const answers = (raw as { answers?: unknown }).answers;
  if (!Array.isArray(answers)) {
    return [];
  }
  return answers.map((answer): Answer => {
    if (!answer || typeof answer !== "object") {
      return { selector: null, labelSelector: null };
    }
    const selectorValue = (answer as Record<string, unknown>).selector;
    const labelSelectorValue = (answer as Record<string, unknown>).labelSelector;
    return {
      selector: typeof selectorValue === "string" ? selectorValue : null,
      labelSelector: typeof labelSelectorValue === "string" ? labelSelectorValue : null,
    };
  });
};

const solveQuizWithOpenAI = async (
  client: OpenAI,
  payload: ExtractedPayload,
  model: string
): Promise<Answer[]> => {
  const response = await client.responses.create({
    model,
    input: [
      {
        role: "system",
        content:
          "Eres un asistente que resuelve quizzes de seleccion multiple. Devuelve un JSON con la forma {\"answers\":[{\"selector\":string|null,\"labelSelector\":string|null}]} usando las opciones disponibles.",
      },
      {
        role: "user",
        content: `Datos del quiz en JSON: ${JSON.stringify(payload)}`,
      },
      {
        role: "user",
        content:
          "Elige exactamente una opcion por pregunta. Usa los selectores entregados sin modificarlos y devuelve solo JSON valido.",
      },
    ],
    temperature: 0,
  });

  const rawOutput = response.output_text?.trim() ?? "";
  if (!rawOutput) {
    throw new Error("OpenAI no entrego contenido");
  }

  let parsed: unknown;
  try {
    parsed = JSON.parse(rawOutput);
  } catch (error) {
    console.error("OpenAI respondio sin JSON valido", error, rawOutput);
    throw new Error("OpenAI respondio sin JSON valido");
  }

  return normalizeAnswers(parsed);
};

const Home: React.FC = () => {
  const [url, setUrl] = useState(DEFAULT_URL);
  const [loading, setLoading] = useState(false);
  const [status, setStatus] = useState<string | null>(null);
  const [webAnswers, setWebAnswers] = useState<Answer[]>([]);
  const containerRef = useRef<HTMLDivElement | null>(null);
  const [webViewReady, setWebViewReady] = useState(false);
  const listenerHandles = useRef<PluginListenerHandle[]>([]);

  const buildViewport = (): NativeViewport | undefined => {
    const element = containerRef.current;
    if (!element) {
      return undefined;
    }
    const rect = element.getBoundingClientRect();
    const viewport: NativeViewport = {
      x: rect.left,
      y: rect.top,
      width: rect.width,
      height: rect.height,
      devicePixelRatio:
        typeof window !== "undefined" && window.devicePixelRatio ? window.devicePixelRatio : 1,
    };
    return viewport;
  };

  const isNativePlatform = Capacitor.isNativePlatform();

  const ensurePlugin = (): NativeWebViewPlugin => {
    if (!window.NativeWebView) {
      throw new Error("NativeWebView plugin no disponible. Verifica la inicializacion en main.tsx");
    }
    return window.NativeWebView;
  };

  useEffect(() => {
    if (!isNativePlatform) {
      return;
    }

    let cancelled = false;
    const plugin = window.NativeWebView;
    if (!plugin) {
      setStatus("NativeWebView plugin no disponible. Verifica la inicializacion en main.tsx");
      return;
    }

    const registerListeners = async () => {
      try {
        const pageLoadedHandle = await plugin.addListener("pageLoaded", async (event) => {
          if (cancelled) {
            return;
          }
          try {
            await plugin.evalJs({ js: INJECT_STYLE });
            setStatus(
              event?.url ? `Pagina lista en el WebView (${event.url})` : "Pagina lista en el WebView"
            );
            setWebViewReady(true);
          } catch (error) {
            const message = error instanceof Error ? error.message : String(error);
            setStatus(`La pagina cargo pero no se pudo aplicar estilos: ${message}`);
            setWebViewReady(false);
          }
        });
        if (cancelled) {
          pageLoadedHandle.remove();
        } else {
          listenerHandles.current.push(pageLoadedHandle);
        }

        const pageFailedHandle = await plugin.addListener("pageLoadFailed", (event) => {
          if (cancelled) {
            return;
          }
          const reason = event?.message ?? "motivo desconocido";
          setStatus(`Fallo la carga en el WebView: ${reason}`);
          setWebViewReady(false);
        });
        if (cancelled) {
          pageFailedHandle.remove();
        } else {
          listenerHandles.current.push(pageFailedHandle);
        }
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        console.error("No se pudieron registrar listeners del WebView nativo", message);
        setStatus("No se pudieron registrar eventos del WebView nativo");
      }
    };

    registerListeners();

    return () => {
      cancelled = true;
      listenerHandles.current.forEach((handle) => {
        try {
          handle.remove();
        } catch (error) {
          console.warn("Fallo al remover listener del WebView", error);
        }
      });
      listenerHandles.current = [];
    };
  }, [isNativePlatform]);

  const unwrapEvalString = (result: EvalResult | undefined, context: string): string => {
    if (!result || typeof result.value !== "string") {
      throw new Error(`El WebView no entrego datos validos al ${context}`);
    }
    if (result.value.startsWith("ERR:")) {
      throw new Error(`El WebView reporto un error al ${context}: ${result.value.slice(4)}`);
    }
    return result.value;
  };

  const openUrl = async () => {
    try {
      setWebAnswers([]);
      if (isNativePlatform) {
        const plugin = ensurePlugin();
        const viewport = buildViewport();
        if (!viewport) {
          setStatus("No se encontro contenedor para el WebView");
          return;
        }

        setStatus("Abriendo URL en WebView...");
        setWebViewReady(false);
        await plugin.open({ url, viewport });
        setStatus("Cargando la pagina dentro del WebView...");
        return;
      }

      setStatus("Modo web: abriendo la URL en una pestana nueva...");
      const opened = window.open(url, "_blank", "noopener");
      if (!opened) {
        setStatus("Modo web: permite los popups o abre la URL manualmente");
      }
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      setStatus(`No se pudo abrir la URL: ${message}`);
    }
  };

  const analyze = async () => {
    try {
      if (isNativePlatform && !webViewReady) {
        setStatus("Espera a que la pagina cargue antes de generar respuestas");
        return;
      }
      setLoading(true);
      setWebAnswers([]);

      const apiKey = import.meta.env.VITE_OPENAI_API_KEY;
      if (!apiKey) {
        throw new Error("Configura VITE_OPENAI_API_KEY con tu clave de OpenAI");
      }
      const client = new OpenAI({ apiKey, dangerouslyAllowBrowser: true });

      if (isNativePlatform) {
        const plugin = ensurePlugin();
        setStatus("Extrayendo preguntas...");
        const extractRes = await plugin.evalJs({ js: EXTRACT_QUESTIONS });
        const rawPayload = JSON.parse(unwrapEvalString(extractRes, "extraer preguntas"));
        if (!isExtractedPayload(rawPayload)) {
          throw new Error("No se pudieron obtener preguntas del WebView");
        }

        setStatus("Consultando OpenAI...");
        const answers = await solveQuizWithOpenAI(client, rawPayload, OPENAI_MODEL);
        setStatus("Resaltando respuestas...");
        const highlightRes = await plugin.evalJs({
          js: PAINT_HIGHLIGHTS(JSON.stringify(answers)),
        });
        unwrapEvalString(highlightRes, "resaltar respuestas");
        setStatus(`Resaltadas ${answers.length} respuestas`);
        return;
      }

      setStatus("Modo web: descargando la pagina...");
      const response = await fetch(url, { credentials: "include" });
      if (!response.ok) {
        throw new Error(`Respuesta HTTP ${response.status}`);
      }
      const html = await response.text();
      const payload = extractQuestionsFromHtml(html, response.url || url);
      if (!payload.questions.length) {
        throw new Error("No se detectaron preguntas en la pagina");
      }

      setStatus("Consultando OpenAI...");
      const answers = await solveQuizWithOpenAI(client, payload, OPENAI_MODEL);
      setWebAnswers(answers);
      setStatus(
        answers.length > 0
          ? "Resultados listos (modo web). Revisa la lista inferior"
          : "OpenAI no entrego respuestas. Revisa la configuracion"
      );
    } catch (error) {
      console.error(error);
      setWebAnswers([]);
      const message = error instanceof Error ? error.message : String(error);
      setStatus(`Fallo el analisis: ${message}`);
    } finally {
      setLoading(false);
    }
  };

  return (
    <IonPage>
      <IonHeader>
        <IonToolbar>
          <IonTitle>Quiz Helper</IonTitle>
          <IonButtons slot="end">
            <IonButton onClick={analyze} disabled={loading}>
              {loading ? (
                <>
                  <IonSpinner name="dots" className="ion-margin-end" />
                  Escaneando...
                </>
              ) : (
                "Escanear pagina"
              )}
            </IonButton>
          </IonButtons>
        </IonToolbar>
      </IonHeader>
      <IonContent className="ion-padding">
        <IonItem>
          <IonLabel position="floating">URL del quiz</IonLabel>
          <IonInput
            value={url}
            type="url"
            onIonChange={(event) => setUrl(event.detail.value ?? "")}
          />
        </IonItem>
        <div className="ion-margin-top">
          <IonButton expand="block" onClick={openUrl} disabled={loading}>
            Abrir
          </IonButton>
        </div>
        {isNativePlatform && (
          <div ref={containerRef} className="webview-container ion-margin-top">
            {!webViewReady && (
              <div className="webview-placeholder">
                Presiona "Abrir" para cargar el quiz en esta vista.
              </div>
            )}
          </div>
        )}
        {status && (
          <IonNote color="medium" className="ion-margin-top status-note">
            {status}
          </IonNote>
        )}
        {!isNativePlatform && webAnswers.length > 0 && (
          <IonList className="ion-margin-top">
            <IonItem>
              <IonLabel>
                <strong>Respuestas sugeridas</strong>
                <p>Marca estas opciones manualmente en la pestana del quiz.</p>
              </IonLabel>
            </IonItem>
            {webAnswers.map((answer, index) => (
              <IonItem key={`${answer.selector ?? "selector"}-${index}`}>
                <IonLabel>
                  <h2>Pregunta {index + 1}</h2>
                  <p>selector: {answer.selector ?? "(sin selector)"}</p>
                  <p>labelSelector: {answer.labelSelector ?? "(sin label selector)"}</p>
                </IonLabel>
              </IonItem>
            ))}
          </IonList>
        )}
      </IonContent>
    </IonPage>
  );
};

export default Home;

