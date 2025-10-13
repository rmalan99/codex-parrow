import { registerPlugin, type PluginListenerHandle } from "@capacitor/core";

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
  addListener(
    eventName: "pageLoaded",
    listenerFunc: (event: { url?: string }) => void
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: "pageLoadFailed",
    listenerFunc: (event: { url?: string; message?: string; code?: number }) => void
  ): Promise<PluginListenerHandle>;
  addListener(eventName: string, listenerFunc: (event: unknown) => void): Promise<PluginListenerHandle>;
  removeAllListeners(): Promise<void>;
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
