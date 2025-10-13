package com.example.quizhelper;

import android.annotation.SuppressLint;
import android.os.Build;
import android.net.Uri;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.view.ViewParent;
import android.webkit.WebView;
import android.webkit.WebSettings;
import android.webkit.WebViewClient;

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
      ensureWebView();
      if (webView == null || webView.getParent() == null) {
        call.reject("webview unavailable");
        return;
      }
      applyViewportLayout(lastViewport);
      webView.stopLoading();
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
      ensureWebView();
      if (webView == null || webView.getParent() == null) {
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
  @SuppressLint("SetJavaScriptEnabled")
  private void ensureWebView() {
    if (webView == null) {
      webView = new WebView(getContext());
      WebSettings settings = webView.getSettings();
      settings.setJavaScriptEnabled(true);
      settings.setDomStorageEnabled(true);
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
      }
      webView.setWebViewClient(new WebViewClient());
    }

    ViewGroup parent = findWebViewContainer();
    if (parent != null) {
      if (webView.getParent() != parent) {
        if (webView.getParent() instanceof ViewGroup) {
          ((ViewGroup) webView.getParent()).removeView(webView);
        }
        parent.addView(
          webView,
          new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
          )
        );
      }
      applyViewportLayout(lastViewport);
    }

    registerBackHandler();
  }

  private ViewGroup findWebViewContainer() {
    WebView hostWebView = getBridge().getWebView();
    if (hostWebView != null) {
      ViewParent hostParent = hostWebView.getParent();
      if (hostParent instanceof ViewGroup) {
        return (ViewGroup) hostParent;
      }
    }

    AppCompatActivity activity = getActivity();
    if (activity != null) {
      View contentView = activity.findViewById(android.R.id.content);
      if (contentView instanceof ViewGroup) {
        return (ViewGroup) contentView;
      }
    }

    return null;
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









