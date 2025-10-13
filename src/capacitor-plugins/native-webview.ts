import { registerPlugin } from "@capacitor/core";

export type EvalResult = { value?: string | null };

export type NativeViewport = {
  x: number;
  y: number;
  width: number;
  height: number;
  devicePixelRatio?: number;
};

export interface NativeWebViewPlugin {
  open(options: { url: string; viewport?: NativeViewport }): Promise<void>;
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
