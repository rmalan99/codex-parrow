#import <Capacitor/Capacitor.h>

CAP_PLUGIN(NativeWebViewPlugin, "NativeWebView",
  CAP_PLUGIN_METHOD(open, CAPPluginReturnPromise);
  CAP_PLUGIN_METHOD(evalJs, CAPPluginReturnPromise);
  CAP_PLUGIN_METHOD(close, CAPPluginReturnPromise);
)
