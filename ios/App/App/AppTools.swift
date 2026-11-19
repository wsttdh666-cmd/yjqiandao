import UIKit
import WebKit
import Capacitor

class MainViewController: CAPBridgeViewController {
    override func capacitorDidLoad() {
        bridge?.registerPluginInstance(AppToolsPlugin())
    }
}

@objc(AppToolsPlugin)
public class AppToolsPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "AppToolsPlugin"
    public let jsName = "AppTools"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "open", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "webLogin", returnType: CAPPluginReturnPromise),
    ]

    @objc func open(_ call: CAPPluginCall) {
        guard let url = URL(string: call.getString("url") ?? "") else { return call.reject("网址不对") }
        DispatchQueue.main.async {
            UIApplication.shared.open(url)
            call.resolve()
        }
    }

    @objc func webLogin(_ call: CAPPluginCall) {
        guard let url = URL(string: call.getString("url") ?? "") else { return call.reject("网址不对") }
        DispatchQueue.main.async {
            let login = LoginViewController(url: url, scan: call.getString("scan"), optional: call.getBool("optional") ?? false) { storage, key in
                var result: [String: Any] = ["storage": storage]
                if let key { result["key"] = key }
                call.resolve(result)
            }
            login.modalPresentationStyle = .fullScreen
            self.bridge?.viewController?.present(login, animated: true)
        }
    }
}

class LoginViewController: UIViewController, WKNavigationDelegate, WKUIDelegate {
    private let start: URL
    private let scan: String?
    private let optional: Bool
    private let finish: (String, String?) -> Void
    private let web = WKWebView()

    init(url: URL, scan: String?, optional: Bool, finish: @escaping (String, String?) -> Void) {
        start = url
        self.scan = scan
        self.optional = optional
        self.finish = finish
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError() }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        let back = UIButton(type: .system), done = UIButton(type: .system)
        back.setTitle("返回", for: .normal)
        done.setTitle("登录好了，完成", for: .normal)
        back.addTarget(self, action: #selector(goBack), for: .touchUpInside)
        done.addTarget(self, action: #selector(complete), for: .touchUpInside)
        let bar = UIStackView(arrangedSubviews: [back, done])
        bar.distribution = .fillEqually
        let stack = UIStackView(arrangedSubviews: [bar, web])
        stack.axis = .vertical
        stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            stack.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            stack.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            stack.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            bar.heightAnchor.constraint(equalToConstant: 44),
        ])
        web.navigationDelegate = self
        web.uiDelegate = self
        web.load(URLRequest(url: start))
    }

    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        let scheme = action.request.url?.scheme ?? ""
        let blocked = action.targetFrame?.isMainFrame == true && scheme != "http" && scheme != "https"
        decisionHandler(blocked ? .cancel : .allow)
    }

    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration, for action: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        webView.load(action.request)
        return nil
    }

    private func bare(_ url: URL?) -> String {
        let h = url?.host ?? ""
        return h.hasPrefix("www.") ? String(h.dropFirst(4)) : h
    }

    private var onSite: Bool {
        let site = bare(start), now = bare(web.url)
        return now == site || now.hasSuffix("." + site) || site.hasSuffix("." + now)
    }

    private func toast(_ text: String) {
        let alert = UIAlertController(title: nil, message: text, preferredStyle: .alert)
        present(alert, animated: true)
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { alert.dismiss(animated: true) }
    }

    private static func json(_ s: String?) -> String {
        guard let s, let data = try? JSONSerialization.data(withJSONObject: s, options: .fragmentsAllowed) else { return "null" }
        return String(decoding: data, as: UTF8.self)
    }

    @objc private func goBack() {
        if web.canGoBack { web.goBack() } else { end("null", nil) }
    }

    @objc private func complete() {
        guard onSite else { return toast("还没回到站点页面，登录完再点") }
        web.evaluateJavaScript("JSON.stringify(Object.assign({}, localStorage))") { storage, _ in
            let saved = Self.json(storage as? String)
            guard let scan = self.scan else { return self.end(saved, nil) }
            self.web.evaluateJavaScript(scan) { key, _ in
                let found = key as? String ?? ""
                if found.isEmpty && !self.optional { return self.toast("这页没找到密钥，翻到显示密钥的页面再点完成") }
                self.end(saved, found.isEmpty ? nil : Self.json(found))
            }
        }
    }

    private func end(_ storage: String, _ key: String?) {
        let store = WKWebsiteDataStore.default().httpCookieStore
        store.getAllCookies { cookies in
            for cookie in cookies {
                var props = cookie.properties ?? [:]
                if cookie.isSessionOnly {
                    props[.expires] = Date().addingTimeInterval(2592000)
                    props.removeValue(forKey: .discard)
                }
                if let kept = HTTPCookie(properties: props) {
                    HTTPCookieStorage.shared.setCookie(kept)
                    store.setCookie(kept)
                }
            }
            self.dismiss(animated: true) { self.finish(storage, key) }
        }
    }
}
