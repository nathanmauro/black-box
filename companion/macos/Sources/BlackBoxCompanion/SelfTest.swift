import AppKit
import WebKit

/// Loads the companion route in a real panel, waits for it to be genuinely ready (see
/// `SelfTestChecks`), writes a PNG, exits 0.
final class SelfTest: NSObject, WKNavigationDelegate, WKScriptMessageHandler {
    private let url: URL
    private let output: String
    private var panel: CompanionPanel!
    private var webView: WKWebView!
    // The state/mode bridge is the shell's only real integration point; require it to have fired at
    // least once before declaring the page ready, so a page that never wires it up cannot pass.
    private var receivedBridgeMessage = false

    static func run(url: URL, output: String) -> Never {
        let app = NSApplication.shared
        app.setActivationPolicy(.accessory)
        let test = SelfTest(url: url, output: output)
        test.start()
        app.run()
        exit(2)
    }

    private init(url: URL, output: String) {
        self.url = url
        self.output = output
    }

    private func start() {
        let configuration = WKWebViewConfiguration()
        configuration.userContentController.add(self, name: "companion")
        panel = CompanionPanel(contentSize: NSSize(width: 340, height: 420))
        webView = WKWebView(frame: NSRect(x: 0, y: 0, width: 340, height: 420), configuration: configuration)
        webView.navigationDelegate = self
        panel.contentView = webView
        panel.orderFrontRegardless()
        webView.load(URLRequest(url: url))
        DispatchQueue.main.asyncAfter(deadline: .now() + 20) { SelfTest.fail("timeout after 20s") }
    }

    private static func fail(_ reason: String) -> Never {
        FileHandle.standardError.write("self-test: \(reason)\n".data(using: .utf8)!)
        exit(1)
    }

    private static let pollInterval: TimeInterval = 0.1
    private static let pollTimeout: TimeInterval = 10

    func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
        if BridgeMessage.parse(message.body) != nil { receivedBridgeMessage = true }
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        pollForCompanion(deadline: Date().addingTimeInterval(Self.pollTimeout))
    }

    // The page mounts .companion synchronously, before its data loads and before the bridge has said
    // anything — polling for that element alone would report ok for a page stuck behind a failed API
    // call or a dead bridge. Poll until SelfTestChecks says it is actually ready instead.
    private func pollForCompanion(deadline: Date) {
        let script = """
        (function () {
          var el = document.querySelector('.companion');
          if (!el) return null;
          return {
            mode: el.getAttribute('data-mode'),
            pulse: el.getAttribute('data-pulse'),
            hasError: !!document.querySelector('.companion-error'),
          };
        })()
        """
        webView.evaluateJavaScript(script) { [self] result, _ in
            let dict = result as? [String: Any]
            let mode = dict?["mode"] as? String
            let pulse = dict?["pulse"] as? String
            let hasError = dict?["hasError"] as? Bool ?? false
            if SelfTestChecks.isReady(mode: mode, hasError: hasError, pulse: pulse, receivedBridgeMessage: receivedBridgeMessage) {
                captureSnapshot(mode: mode!)
                return
            }
            if Date() >= deadline {
                SelfTest.fail(notReadyReason(mode: mode, pulse: pulse, hasError: hasError))
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + Self.pollInterval) { [self] in
                pollForCompanion(deadline: deadline)
            }
        }
    }

    private func notReadyReason(mode: String?, pulse: String?, hasError: Bool) -> String {
        if mode == nil || mode == "" { return ".companion not rendered" }
        if hasError { return "page shows .companion-error" }
        if pulse == nil || pulse == "connecting" { return "pulse stuck at \(pulse ?? "missing")" }
        if !receivedBridgeMessage { return "no bridge message received from the page" }
        return "not ready"
    }

    private func captureSnapshot(mode: String) {
        webView.takeSnapshot(with: nil) { image, error in
            guard let image, let tiff = image.tiffRepresentation, let rep = NSBitmapImageRep(data: tiff),
                  let png = rep.representation(using: .png, properties: [:]) else {
                SelfTest.fail("snapshot failed: \(String(describing: error))")
            }
            do {
                try png.write(to: URL(fileURLWithPath: self.output))
                print("self-test: ok mode=\(mode) png=\(self.output)")
                exit(0)
            } catch {
                SelfTest.fail("write failed: \(error)")
            }
        }
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        SelfTest.fail("navigation failed: \(error)")
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        SelfTest.fail("provisional navigation failed: \(error)")
    }
}
