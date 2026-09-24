import AppKit
import WebKit

/// Loads the companion route in a real panel, waits for `.companion`, writes a PNG, exits 0.
final class SelfTest: NSObject, WKNavigationDelegate {
    private let url: URL
    private let output: String
    private var panel: CompanionPanel!
    private var webView: WKWebView!

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
        panel = CompanionPanel(contentSize: NSSize(width: 340, height: 420))
        webView = WKWebView(frame: NSRect(x: 0, y: 0, width: 340, height: 420))
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

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { [self] in
            webView.evaluateJavaScript("(document.querySelector('.companion') || {}).getAttribute ? document.querySelector('.companion').getAttribute('data-mode') : null") { result, _ in
                guard let mode = result as? String else { SelfTest.fail(".companion not rendered") }
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
        }
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        SelfTest.fail("navigation failed: \(error)")
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        SelfTest.fail("provisional navigation failed: \(error)")
    }
}
