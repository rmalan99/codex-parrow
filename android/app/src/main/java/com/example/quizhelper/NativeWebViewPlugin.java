package com.example.quizhelper;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.PluginMethod;

import java.util.Locale;

@CapacitorPlugin(name = "NativeWebView")
public class NativeWebViewPlugin extends Plugin {

  private WebView webView;
  private OnBackPressedCallback backPressedCallback;
  private ViewportSpec lastViewport;
  private FrameLayout overlayContainer;
  private String pendingNavigationUrl;

  private static final String EVENT_PAGE_LOADED = "pageLoaded";
  private static final String EVENT_PAGE_LOAD_FAILED = "pageLoadFailed";

  @Override
  public void load() {
    super.load();
    runOnUiThreadSafe(this::registerBackHandler);
  }

  @PluginMethod
  public void open(PluginCall call) {
    String url = call.getString("url");
    if (url == null || url.isBlank()) {
      call.reject("url required");
      return;
    }

    Uri parsed = Uri.parse(url);
    if (parsed == null || parsed.getScheme() == null) {
      call.reject("invalid url");
      return;
    }

    String scheme = parsed.getScheme().toLowerCase(Locale.ROOT);
    if (!scheme.equals("http") && !scheme.equals("https")) {
      call.reject("unsupported url scheme");
      return;
    }

    JSObject viewportObject = call.getObject("viewport", null);
    final ViewportSpec viewportSpec = parseViewport(viewportObject);

    if (!runOnUiThreadSafe(() -> {
      lastViewport = viewportSpec;
      if (!ensureWebView()) {
        call.reject("webview unavailable");
        return;
      }
      if (webView == null) {
        call.reject("webview unavailable");
        return;
      }
      if (overlayContainer != null) {
        overlayContainer.setVisibility(View.VISIBLE);
        overlayContainer.bringToFront();
      }
      applyViewportLayout(lastViewport);
      webView.stopLoading();
      pendingNavigationUrl = url;
      webView.loadUrl(url);
      call.resolve();
    })) {
      call.reject("activity unavailable");
    }
  }

  @PluginMethod
  public void evalJs(PluginCall call) {
    String js = call.getString("js");
    if (js == null || js.isBlank()) {
      call.reject("js required");
      return;
    }

    if (!runOnUiThreadSafe(() -> {
      if (!ensureWebView()) {
        call.reject("webview not ready");
        return;
      }
      if (webView == null) {
        call.reject("webview not ready");
        return;
      }
      webView.evaluateJavascript(js, value -> {
        JSObject result = new JSObject();
        result.put("value", value == null ? "" : value);
        call.resolve(result);
      });
    })) {
      call.reject("activity unavailable");
    }
  }

  @Override
  protected void handleOnPause() {
    super.handleOnPause();
    runOnUiThreadSafe(() -> {
      if (webView != null) {
        webView.onPause();
        webView.pauseTimers();
      }
    });
  }

  @Override
  protected void handleOnResume() {
    super.handleOnResume();
    runOnUiThreadSafe(() -> {
      if (webView != null) {
        webView.onResume();
        webView.resumeTimers();
      }
    });
  }

  @Override
  protected void handleOnDestroy() {
    super.handleOnDestroy();
    if (!runOnUiThreadSafe(this::cleanupWebView)) {
      cleanupWebView();
    }
  }

  private static final class ViewportSpec {
    final Integer left;
    final Integer top;
    final Integer width;
    final Integer height;

    ViewportSpec(Integer left, Integer top, Integer width, Integer height) {
      this.left = left;
      this.top = top;
      this.width = width;
      this.height = height;
    }
  }

  private Double optNullableDouble(JSObject viewport, String key) {
    if (viewport == null) {
      return null;
    }
    double value = viewport.optDouble(key, Double.NaN);
    return Double.isNaN(value) ? null : value;
  }

  private ViewportSpec parseViewport(JSObject viewport) {
    if (viewport == null) {
      return null;
    }

    Double xValue = optNullableDouble(viewport, "x");
    Double yValue = optNullableDouble(viewport, "y");
    Double widthValue = optNullableDouble(viewport, "width");
    Double heightValue = optNullableDouble(viewport, "height");
    Double ratioValue = optNullableDouble(viewport, "devicePixelRatio");

    float density = 1f;
    if (getContext() != null && getContext().getResources() != null) {
      DisplayMetrics metrics = getContext().getResources().getDisplayMetrics();
      if (metrics != null && metrics.density > 0f) {
        density = metrics.density;
      }
    }

    float scale = ratioValue != null && ratioValue > 0 ? ratioValue.floatValue() : density;
    if (scale <= 0f) {
      scale = 1f;
    }

    Integer left = xValue != null ? Math.round(xValue.floatValue() * scale) : null;
    Integer top = yValue != null ? Math.round(yValue.floatValue() * scale) : null;
    Integer width = widthValue != null ? Math.round(widthValue.floatValue() * scale) : null;
    Integer height = heightValue != null ? Math.round(heightValue.floatValue() * scale) : null;

    return new ViewportSpec(left, top, width, height);
  }

  private void applyViewportLayout(ViewportSpec viewport) {
    if (webView == null) {
      return;
    }

    ViewParent parent = webView.getParent();
    if (!(parent instanceof ViewGroup)) {
      return;
    }

    ViewGroup container = (ViewGroup) parent;
    int width = viewport != null && viewport.width != null && viewport.width > 0
      ? viewport.width
      : ViewGroup.LayoutParams.MATCH_PARENT;
    int height = viewport != null && viewport.height != null && viewport.height > 0
      ? viewport.height
      : ViewGroup.LayoutParams.MATCH_PARENT;

    ViewGroup.MarginLayoutParams params;
    if (container instanceof FrameLayout) {
      FrameLayout.LayoutParams frameParams = new FrameLayout.LayoutParams(width, height);
      frameParams.gravity = Gravity.TOP | Gravity.START;
      params = frameParams;
    } else {
      params = new ViewGroup.MarginLayoutParams(width, height);
    }

    params.leftMargin = viewport != null && viewport.left != null ? Math.max(viewport.left, 0) : 0;
    params.topMargin = viewport != null && viewport.top != null ? Math.max(viewport.top, 0) : 0;
    params.rightMargin = 0;
    params.bottomMargin = 0;

    webView.setLayoutParams(params);
    webView.bringToFront();
    webView.requestLayout();
  }

  private boolean isSupportedScheme(String scheme) {
    if (scheme == null) {
      return false;
    }
    String normalized = scheme.toLowerCase(Locale.ROOT);
    return normalized.equals("http") || normalized.equals("https");
  }

  private void handleLoadError(CharSequence description, Integer code, Uri uri) {
    String message = description != null ? description.toString() : "Error desconocido";
    JSObject payload = new JSObject();
    if (uri != null) {
      payload.put("url", uri.toString());
    }
    payload.put("message", message);
    if (code != null) {
      payload.put("code", code);
    }
    pendingNavigationUrl = null;
    notifyListeners(EVENT_PAGE_LOAD_FAILED, payload, true);
  }

  private final class NativeWebViewClient extends WebViewClient {
    @Override
    public void onPageFinished(WebView view, String url) {
      super.onPageFinished(view, url);
      pendingNavigationUrl = null;
      JSObject payload = new JSObject();
      payload.put("url", url);
      notifyListeners(EVENT_PAGE_LOADED, payload, true);
    }

    @Override
    @SuppressLint("NewApi")
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
      if (request == null) {
        return false;
      }
      Uri uri = request.getUrl();
      if (uri == null) {
        return false;
      }
      if (isSupportedScheme(uri.getScheme())) {
        pendingNavigationUrl = uri.toString();
        return false;
      }
      return true;
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean shouldOverrideUrlLoading(WebView view, String url) {
      if (url == null) {
        return false;
      }
      Uri uri = Uri.parse(url);
      if (uri == null) {
        return false;
      }
      if (isSupportedScheme(uri.getScheme())) {
        pendingNavigationUrl = uri.toString();
        return false;
      }
      return true;
    }

    @Override
    public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
      if (request != null && request.isForMainFrame()) {
        Uri failingUri = request.getUrl();
        Integer code = error != null ? error.getErrorCode() : null;
        CharSequence description = error != null ? error.getDescription() : null;
        handleLoadError(description, code, failingUri);
      }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
      Uri failingUri = failingUrl != null ? Uri.parse(failingUrl) : null;
      handleLoadError(description, errorCode, failingUri);
    }
  }
  @SuppressLint("SetJavaScriptEnabled")
  private boolean ensureWebView() {
    if (webView == null) {
      Context context = getContext();
      if (context == null) {
        return false;
      }
      webView = new WebView(context);
      WebSettings settings = webView.getSettings();
      settings.setJavaScriptEnabled(true);
      settings.setDomStorageEnabled(true);
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
      }
      webView.setWebViewClient(new NativeWebViewClient());
    }

    FrameLayout container = ensureOverlayContainer();
    if (container == null) {
      return false;
    }

    if (webView.getParent() != container) {
      if (webView.getParent() instanceof ViewGroup) {
        ((ViewGroup) webView.getParent()).removeView(webView);
      }
      FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
      );
      params.gravity = Gravity.TOP | Gravity.START;
      container.addView(webView, params);
    }

    webView.setVisibility(View.VISIBLE);
    applyViewportLayout(lastViewport);
    registerBackHandler();
    return true;
  }

  private FrameLayout ensureOverlayContainer() {
    AppCompatActivity activity = getActivity();
    if (activity == null) {
      return null;
    }

    FrameLayout root = activity.findViewById(android.R.id.content);
    if (root == null) {
      return null;
    }

    if (overlayContainer == null) {
      overlayContainer = new FrameLayout(activity);
      overlayContainer.setClipChildren(false);
      overlayContainer.setClipToPadding(false);
      overlayContainer.setVisibility(View.GONE);
    }

    if (overlayContainer.getParent() != root) {
      if (overlayContainer.getParent() instanceof ViewGroup) {
        ((ViewGroup) overlayContainer.getParent()).removeView(overlayContainer);
      }
      FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
      );
      params.gravity = Gravity.TOP | Gravity.START;
      root.addView(overlayContainer, params);
    }

    return overlayContainer;
  }

  private void registerBackHandler() {
    AppCompatActivity activity = getActivity();
    if (activity == null || backPressedCallback != null) {
      return;
    }

    backPressedCallback = new OnBackPressedCallback(true) {
      @Override
      public void handleOnBackPressed() {
        if (webView != null && webView.canGoBack()) {
          webView.goBack();
          return;
        }
        setEnabled(false);
        activity.onBackPressed();
        setEnabled(true);
      }
    };

    // Allow the WebView to consume back presses before the default Ionic view.
    activity.getOnBackPressedDispatcher().addCallback(activity, backPressedCallback);
  }

  private void cleanupWebView() {
    if (webView != null) {
      ViewParent parent = webView.getParent();
      if (parent instanceof ViewGroup) {
        ((ViewGroup) parent).removeView(webView);
      }
      webView.stopLoading();
      webView.loadUrl("about:blank");
      webView.onPause();
      webView.destroy();
      webView = null;
      lastViewport = null;
    }

    pendingNavigationUrl = null;

    if (overlayContainer != null) {
      overlayContainer.removeAllViews();
      overlayContainer.setVisibility(View.GONE);
    }

    if (backPressedCallback != null) {
      backPressedCallback.remove();
      backPressedCallback = null;
    }
  }

  private boolean runOnUiThreadSafe(Runnable task) {
    AppCompatActivity activity = getActivity();
    if (activity == null) {
      return false;
    }
    activity.runOnUiThread(task);
    return true;
  }
}









