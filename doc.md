Â¡Excelente idea! **Ionic + Capacitor** te da exactamente lo que necesitas para la **OpciÃ³n 4 (App con WebView)** con UX nativa en **Android e iOS**, un **bridge** seguro para hablar con tu API y **JS inyectado** para leer/pintar en la pÃ¡gina.

Abajo te dejo un **plan directo** + **cÃ³digo mÃ­nimo** para que tengas un â€œmini-navegador analizadorâ€ dentro de tu app Ionic.

---

# Arquitectura (resumen)

1. **Pantalla Ionic** con:

   * Input para URL.
   * BotÃ³n â€œAbrirâ€.
   * BotÃ³n â€œAnalizarâ€.

2. **WebView nativa** (WKWebView / Android WebView) cargando la URL.

3. **InyecciÃ³n JS**:

   * `extractQuestions()` (igual a tu content script) â†’ devuelve `{questions, selectors}`.
   * La app (nativo) llama a **tu API `/solve`** usando **Capacitor HTTP** (evitas CORS).
   * Respuesta `{answers[]}` â†’ `paintHighlights(answers)` inyectado en la misma pÃ¡gina.

4. **Capacitor Plugin** propio para:

   * Abrir URL dentro de un WebView.
   * Ejecutar JS y recibir resultados (`evaluateJavascript`).
   * Canal bidireccional (postMessage).

---

# Estructura de proyecto

```
ionic-quiz-helper/
â”œâ”€ src/                      # Ionic (React/Vue/Angular)
â”‚  â”œâ”€ main app ...
â”‚  â”œâ”€ bridge/quiz-js.ts      # JS a inyectar: extractQuestions & paintHighlights
â”‚  â””â”€ pages/Home.tsx
â”œâ”€ android/                  # CÃ³digo nativo Android (WebView + Plugin)
â”œâ”€ ios/                      # CÃ³digo nativo iOS (WKWebView + Plugin)
â””â”€ capacitor.config.ts
```

> Puedes usar **React** o **Angular**. El ejemplo usa **React** por brevedad.

---

# 1) Crear app base

```bash
npm create ionic@latest
# Elige React (o Angular) + Capacitor
cd ionic-quiz-helper
npm i @capacitor/android @capacitor/ios @capacitor/core @capacitor/filesystem @capacitor/preferences @capacitor-community/http
npx cap add android
npx cap add ios
```

---

# 2) JS a inyectar (reutiliza tu lÃ³gica)

`src/bridge/quiz-js.ts` (exporta strings ejecutables dentro del WebView)

```ts
export const INJECT_STYLE = `
(function(){
  const H='llm-correct-highlight',SID='llm-correct-style';
  if(!document.getElementById(SID)){
    const s=document.createElement('style');s.id=SID;
    s.textContent=\`.
      .\${H}{background:rgba(34,197,94,.25)!important;border-radius:6px;transition:background .25s}
    \`;
    document.head.appendChild(s);
  }
})();
`;

export const EXTRACT_QUESTIONS = `
(function(){
  function cssPath(el){
    if(!(el instanceof Element)) return null;
    const path=[]; while(el && el.nodeType===1 && path.length<6){
      let sel=el.nodeName.toLowerCase();
      if(el.id){ sel+= '#' + CSS.escape(el.id); path.unshift(sel); break; }
      let sib=el, nth=1; while((sib=sib.previousElementSibling)) if(sib.nodeName===el.nodeName) nth++;
      sel += ':nth-of-type(' + nth + ')';
      path.unshift(sel); el=el.parentElement;
    }
    return path.join(' > ');
  }
  const form = document.querySelector('form,[role="form"],[data-quiz],.quiz,.question-container') || document.body;
  const groups=new Map();
  form.querySelectorAll('input[type="radio"],input[type="checkbox"]').forEach((el,idx)=>{
    const key=el.name || el.closest('fieldset') || ('__g'+idx);
    const lbl=form.querySelector('label[for="'+el.id+'"]') || el.closest('label');
    const qt=(el.closest('fieldset,.question,.question-block,.quiz-question')||form);
    const legend=qt.querySelector('legend,.question-title,h3,h4,.title');
    if(!groups.has(key)) groups.set(key,{questionText:(legend?.innerText||'').trim(), options:[]});
    groups.get(key).options.push({
      text:(lbl?.innerText||el.value||'').trim(),
      selector: el.id ? '#' + CSS.escape(el.id) : cssPath(el),
      labelSelector: lbl ? cssPath(lbl) : null
    });
  });
  return JSON.stringify({
    url: location.href,
    title: document.title,
    questions: Array.from(groups.values())
  });
})();
`;

export const PAINT_HIGHLIGHTS = (answersJson: string) => `
(function(){
  try{
    const H='llm-correct-highlight';
    const answers = ${answersJson};
    (answers||[]).forEach(a=>{
      const t=(a.selector && document.querySelector(a.selector)) || (a.labelSelector && document.querySelector(a.labelSelector));
      const node = a.labelSelector ? t : (t?.closest('label') || t);
      node && node.classList.add(H);
    });
    return 'OK';
  }catch(e){ return 'ERR:' + e.message; }
})();
`;
```

---

# 3) Pantalla Ionic (React)

`src/pages/Home.tsx`

```tsx
import React, { useState } from "react";
import { IonButton, IonContent, IonHeader, IonInput, IonPage, IonTitle, IonToolbar } from "@ionic/react";
import { Http } from "@capacitor-community/http";
import { INJECT_STYLE, EXTRACT_QUESTIONS, PAINT_HIGHLIGHTS } from "../bridge/quiz-js";

// Declaramos el Plugin nativo
declare global {
  interface Window { NativeWebView?: any; }
}

const API_BASE = "https://tu-api.example.com";

const Home: React.FC = () => {
  const [url, setUrl] = useState("https://example.com/quiz");
  const [loading, setLoading] = useState(false);

  const openUrl = async () => {
    await window.NativeWebView?.open({ url }); // nativo abre la URL en el WebView
    // Inyecta estilos una vez
    await window.NativeWebView?.evalJs({ js: INJECT_STYLE });
  };

  const analyze = async () => {
    try {
      setLoading(true);
      // 1) Extraer preguntas/opciones desde la pÃ¡gina
      const extractRes = await window.NativeWebView?.evalJs({ js: EXTRACT_QUESTIONS });
      const payload = JSON.parse(extractRes?.value || "{}");

      // 2) Llamar a tu API desde NATIVO (sin CORS) con Capacitor HTTP
      const apiRes = await Http.request({
        method: "POST",
        url: `${API_BASE}/solve`,
        headers: { "Content-Type": "application/json" },
        data: { url: payload.url, title: payload.title, questions: payload.questions }
      });

      const answers = apiRes.data?.answers || [];
      // 3) Pintar respuestas correctas en la misma pÃ¡gina
      const paintRes = await window.NativeWebView?.evalJs({ js: PAINT_HIGHLIGHTS(JSON.stringify(answers)) });
      console.log("paint:", paintRes?.value);
    } catch (e) {
      console.error(e);
      alert("Fallo el anÃ¡lisis");
    } finally {
      setLoading(false);
    }
  };

  return (
    <IonPage>
      <IonHeader>
        <IonToolbar><IonTitle>Quiz Helper</IonTitle></IonToolbar>
      </IonHeader>
      <IonContent className="ion-padding">
        <IonInput value={url} onIonChange={e => setUrl(e.detail.value!)} label="URL" labelPlacement="floating" />
        <div className="ion-margin-top" />
        <IonButton onClick={openUrl}>Abrir</IonButton>
        <IonButton onClick={analyze} disabled={loading} color="success">
          {loading ? "Analizando..." : "Analizar y resaltar"}
        </IonButton>
      </IonContent>
    </IonPage>
  );
}
export default Home;
```

---

# 4) Plugin nativo (Capacitor) â€” Android (Kotlin)

Crea un **Capacitor Plugin** simple para manejar un **WebView propio**.

**Android**: `android/app/src/main/java/com/example/quizhelper/NativeWebViewPlugin.java`

- Instancia un `WebView` nativo y lo monta dentro de un `FrameLayout` flotante que se posiciona usando el viewport calculado en React.
- Emite eventos `pageLoaded` y `pageLoadFailed` para que la UI web sepa si puede habilitar el botÃ³n de generaciÃ³n.
- Valida que solo se abran `http/https`, limpia el WebView en `onPause/onDestroy` y reutiliza el contenedor entre navegaciones.
- El cÃ³digo relevante vive en [`NativeWebViewPlugin.java`](android/app/src/main/java/com/example/quizhelper/NativeWebViewPlugin.java).

> Actualizacion: en la version Java incluida en este repo se limpian los recursos del WebView en `handleOnDestroy`, se pausan timers en `handleOnPause`/`handleOnResume`, se valida que solo se carguen URLs http/https y se usa un `OnBackPressedCallback` para delegar la navegacion atras antes de cerrar la vista.

**Registrar el plugin** en `android/app/src/main/java/.../MainActivity.java` (o `MainActivity.kt`):

```kotlin
import com.getcapacitor.BridgeActivity;
import com.getcapacitor.Plugin;
import java.util.ArrayList;

class MainActivity : BridgeActivity() {
  override fun onCreate(savedInstanceState: android.os.Bundle?) {
    super.onCreate(savedInstanceState)
    registerPlugin(NativeWebViewPlugin::class.java)
  }
}
```

---

# 5) Plugin nativo â€” iOS (Swift, WKWebView)

`ios/App/App/NativeWebViewPlugin.swift`

```swift
import UIKit
import Capacitor
import WebKit

@objc(NativeWebViewPlugin)
public class NativeWebViewPlugin: CAPPlugin {
  private var webView: WKWebView?
  private var lifecycleObservers: [NSObjectProtocol] = []
  private let allowedSchemes: Set<String> = ["http", "https"]

  public override func load() {
    super.load()
    registerLifecycleObservers()
  }

  deinit {
    removeLifecycleObservers()
    detachWebView()
  }

  @objc func open(_ call: CAPPluginCall) {
    guard
      let urlString = call.getString("url"),
      let url = URL(string: urlString),
      let scheme = url.scheme?.lowercased(),
      allowedSchemes.contains(scheme)
    else {
      call.reject("valid http(s) url required")
      return
    }

    DispatchQueue.main.async {
      self.ensureWebView()
      self.webView?.stopLoading()
      self.webView?.load(URLRequest(url: url))
      call.resolve()
    }
  }

  @objc func evalJs(_ call: CAPPluginCall) {
    guard let script = call.getString("js") else {
      call.reject("js required")
      return
    }

    DispatchQueue.main.async {
      self.ensureWebView()
      guard let webView = self.webView else {
        call.reject("webview not ready")
        return
      }

      webView.evaluateJavaScript(script) { result, error in
        if let error = error {
          call.reject(error.localizedDescription)
          return
        }

        let value = (result as? String) ?? ""
        call.resolve(["value": value])
      }
    }
  }

  @objc func close(_ call: CAPPluginCall) {
    DispatchQueue.main.async {
      self.detachWebView()
      call.resolve()
    }
  }

  private func ensureWebView() {
    guard let hostView = bridge?.viewController?.view else { return }

    if webView == nil {
      let configuration = WKWebViewConfiguration()
      configuration.preferences.javaScriptCanOpenWindowsAutomatically = false
      configuration.defaultWebpagePreferences.allowsContentJavaScript = true
      configuration.allowsInlineMediaPlayback = false

      let instance = WKWebView(frame: hostView.bounds, configuration: configuration)
      instance.autoresizingMask = [.flexibleWidth, .flexibleHeight]
      instance.navigationDelegate = self
      instance.allowsBackForwardNavigationGestures = true
      instance.scrollView.contentInsetAdjustmentBehavior = .never
      webView = instance
    }

    guard let webView = webView else { return }

    if webView.superview !== hostView {
      webView.removeFromSuperview()
      webView.frame = hostView.bounds
      hostView.addSubview(webView)
    }
  }

  private func detachWebView() {
    guard let webView = webView else { return }

    webView.stopLoading()
    webView.navigationDelegate = nil
    webView.removeFromSuperview()
    webView.loadHTMLString("", baseURL: nil)
    self.webView = nil
  }

  private func registerLifecycleObservers() {
    let center = NotificationCenter.default
    let observer = center.addObserver(
      forName: UIApplication.didEnterBackgroundNotification,
      object: nil,
      queue: .main
    ) { [weak self] _ in
      self?.webView?.stopLoading()
    }
    lifecycleObservers.append(observer)
  }

  private func removeLifecycleObservers() {
    let center = NotificationCenter.default
    lifecycleObservers.forEach { center.removeObserver() }
    lifecycleObservers.removeAll()
  }
}

extension NativeWebViewPlugin: WKNavigationDelegate {
  public func webView(
    _ webView: WKWebView,
    decidePolicyFor navigationAction: WKNavigationAction,
    decisionHandler: @escaping (WKNavigationActionPolicy) -> Void
  ) {
    guard let scheme = navigationAction.request.url?.scheme?.lowercased() else {
      decisionHandler(.cancel)
      return
    }

    if allowedSchemes.contains(scheme) || scheme == "about" {
      decisionHandler(.allow)
    } else {
      decisionHandler(.cancel)
    }
  }
}
```

Registrar en `ios/App/App/AppDelegate.swift`:

```swift
import Capacitor

@UIApplicationMain
class AppDelegate: UIResponder, UIApplicationDelegate {
  var window: UIWindow?
  func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
    CAPBridge.handleOpenUrl(nil)
    return true
  }
}
```

Y en `ios/App/App/NativeWebViewPlugin.m` (exposiciÃ³n):

```objc
#import <Capacitor/Capacitor.h>

CAP_PLUGIN(NativeWebViewPlugin, "NativeWebView",
  CAP_PLUGIN_METHOD(open, CAPPluginReturnPromise);
  CAP_PLUGIN_METHOD(evalJs, CAPPluginReturnPromise);
)
```

---

# 6) Exponer el plugin al frontend

En **Ionic (TS)**, crea un wrapper sencillo:

```ts
// src/capacitor-plugins/native-webview.ts
import { registerPlugin } from '@capacitor/core';
export const NativeWebView = registerPlugin<any>('NativeWebView');
declare global { interface Window { NativeWebView: any } }
window.NativeWebView = NativeWebView;
```

Importa este archivo en tu `main.tsx` o `index.tsx` una sola vez.

---

# 7) Configurar y correr

```bash
npm run build
npx cap sync
npx cap open android
npx cap open ios
```

Compila y corre en dispositivo/emulador.
En la app:

1. Ingresa la URL del quiz.
2. â€œAbrirâ€.
3. â€œAnalizar y resaltarâ€.

---

## Consideraciones importantes

* **CORS**: todas las llamadas a tu **API** hazlas con `@capacitor-community/http` (nativo) â†’ **sin CORS**.
* **CSP del sitio**: como ejecutas JS **dentro** del WebView del dispositivo, tÃ­picamente podrÃ¡s inyectar (no hay extensiÃ³n de navegador que bloquear). Aun asÃ­, evita `eval` dinÃ¡mico fuera de `evaluateJavascript`.
* **AutenticaciÃ³n**: si el sitio requiere login, el usuario inicia sesiÃ³n dentro del WebView y tus `evaluateJavascript` verÃ¡n el DOM autenticado.
* **Legalidad/ToS**: verificar tÃ©rminos del sitio objetivo.
* **Privacidad**: no envÃ­es el HTML completo si no es necesario; manda `questions` (texto + selectores).
* **Robustez**: si el DOM cambia, tu `extractQuestions()` seguirÃ¡ funcionando porque se basa en heurÃ­sticas (labels/fieldsets); si necesitas precisiÃ³n, tu backend puede aprender patrones por dominio.

---

## Â¿Quieres que te lo deje como repo base?

Puedo prepararte:

* Proyecto **Ionic React** con el **plugin nativo** ya registrado.
* Pantalla `Home` + botones + logs.
* `quiz-js.ts` listo.
* `.http` o `REST Client` para probar `/solve`.

Dime si prefieres **Angular** y lo ajusto al stack que uses mÃ¡s.

## Android WebView notas
- 2025-10-09: Se habilito `android:usesCleartextTraffic` en el manifest para permitir quizzes HTTP durante las pruebas. Considera definir un `network_security_config` acotado antes de liberar.
- 2025-10-09: El plugin nativo ahora posiciona el WebView usando las coordenadas del contenedor en Home.tsx para mantener el header visible.
