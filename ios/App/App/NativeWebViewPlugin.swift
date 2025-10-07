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

      let newWebView = WKWebView(frame: hostView.bounds, configuration: configuration)
      newWebView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
      newWebView.navigationDelegate = self
      newWebView.allowsBackForwardNavigationGestures = true
      newWebView.scrollView.contentInsetAdjustmentBehavior = .never
      webView = newWebView
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
    webView.evaluateJavaScript("document.body && document.body.classList.remove('llm-correct-highlight')", completionHandler: nil)
    webView.navigationDelegate = nil
    webView.removeFromSuperview()
    webView.loadHTMLString("", baseURL: nil)
    self.webView = nil
  }

  private func registerLifecycleObservers() {
    let center = NotificationCenter.default

    let didEnterBackground = center.addObserver(
      forName: UIApplication.didEnterBackgroundNotification,
      object: nil,
      queue: .main
    ) { [weak self] _ in
      self?.webView?.stopLoading()
    }

    lifecycleObservers.append(didEnterBackground)
  }

  private func removeLifecycleObservers() {
    let center = NotificationCenter.default
    lifecycleObservers.forEach { center.removeObserver($0) }
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
