import AppKit
import WebKit

final class AppDelegate: NSObject, NSApplicationDelegate, WKScriptMessageHandler, WKUIDelegate {
    private let options: Options
    private var statusItem: NSStatusItem!
    private var panel: CompanionPanel!
    private var webView: WKWebView!
    private var toggleItem: NSMenuItem!

    init(options: Options) {
        self.options = options
        super.init()
    }

    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.setActivationPolicy(.accessory)

        let configuration = WKWebViewConfiguration()
        configuration.userContentController.add(self, name: "companion")
        webView = WKWebView(frame: .zero, configuration: configuration)
        webView.uiDelegate = self
        webView.setValue(false, forKey: "drawsBackground")

        panel = CompanionPanel(contentSize: NSSize(width: 132, height: 36))
        panel.contentView = webView

        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        statusItem.button?.title = StatusTitle.render(pulse: .connecting, unseen: 0)
        let menu = NSMenu()
        toggleItem = NSMenuItem(title: "Hide Companion", action: #selector(togglePanel), keyEquivalent: "")
        toggleItem.target = self
        menu.addItem(toggleItem)
        let open = NSMenuItem(title: "Open Black Box", action: #selector(openBlackBox), keyEquivalent: "")
        open.target = self
        menu.addItem(open)
        menu.addItem(.separator())
        menu.addItem(NSMenuItem(title: "Quit", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q"))
        statusItem.menu = menu

        webView.load(URLRequest(url: options.url))
        panel.orderFrontRegardless()
    }

    @objc private func togglePanel() {
        if panel.isVisible {
            panel.orderOut(nil)
            toggleItem.title = "Show Companion"
        } else {
            panel.orderFrontRegardless()
            toggleItem.title = "Hide Companion"
        }
    }

    @objc private func openBlackBox() {
        guard var components = URLComponents(url: options.url, resolvingAgainstBaseURL: false) else { return }
        components.path = "/"
        components.query = nil
        if let url = components.url { NSWorkspace.shared.open(url) }
    }

    func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
        guard let parsed = BridgeMessage.parse(message.body) else { return }
        switch parsed {
        case let .state(pulse, unseen):
            statusItem.button?.title = StatusTitle.render(pulse: pulse, unseen: unseen)
        case let .mode(_, width, height):
            let screen = panel.screen?.visibleFrame ?? NSScreen.main?.visibleFrame ?? panel.frame
            let frame = PanelGeometry.frame(resizing: panel.frame, to: CGSize(width: width, height: height), within: screen)
            panel.setFrame(frame, display: true, animate: true)
        }
    }

    // target="_blank" links open in the default browser instead of inside the panel.
    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration, for navigationAction: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        if let url = navigationAction.request.url { NSWorkspace.shared.open(url) }
        return nil
    }
}
