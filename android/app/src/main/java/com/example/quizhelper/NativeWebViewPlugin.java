package com.example.quizhelper;

import android.annotation.SuppressLint;
import android.net.Uri;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.webkit.WebView;
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

    if (!runOnUiThreadSafe(() -> {
      ensureWebView();
      if (webView != null) {
        webView.stopLoading();
        webView.loadUrl(url);
      }
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

  @SuppressLint("SetJavaScriptEnabled")
  private void ensureWebView() {
    if (webView == null) {
      webView = new WebView(getContext());
      webView.getSettings().setJavaScriptEnabled(true);
      webView.getSettings().setDomStorageEnabled(true);
      webView.setWebViewClient(new WebViewClient());
    }

    WebView hostWebView = getBridge().getWebView();
    ViewGroup parent = hostWebView != null ? (ViewGroup) hostWebView.getParent() : null;
    if (parent != null && webView.getParent() != parent) {
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

    registerBackHandler();
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
