import { registerPlugin } from "@capacitor/core";

type EvalResult = { value: string };

export interface NativeWebViewPlugin {
  open(options: { url: string }): Promise<void>;
  evalJs(options: { js: string }): Promise<EvalResult>;
  close?(options?: Record<string, never>): Promise<void>;
}

export const NativeWebView = registerPlugin<NativeWebViewPlugin>("NativeWebView");

declare global {
  interface Window {
    NativeWebView?: NativeWebViewPlugin;
  }
}

if (typeof window !== "undefined") {
  window.NativeWebView = NativeWebView;
}
