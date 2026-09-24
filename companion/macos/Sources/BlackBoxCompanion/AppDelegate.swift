import AppKit
import WebKit

final class AppDelegate: NSObject, NSApplicationDelegate, WKScriptMessageHandler, WKUIDelegate, WKNavigationDelegate {
    private let options: Options
    private let sizeStore: PanelSizeStore
    private var statusItem: NSStatusItem!
    private var panel: CompanionPanel!
    private var webView: WKWebView!
    private var toggleItem: NSMenuItem!
    private var currentMode = "mini"
    // Set while a bridge mode message is driving the panel's frame, so the resize notification it
    // triggers is not mistaken for — and does not overwrite — a manual resize.
    private var isApplyingProgrammaticResize = false

    init(options: Options, sizeStore: PanelSizeStore = UserDefaultsPanelSizeStore()) {
        self.options = options
        self.sizeStore = sizeStore
        super.init()
    }

    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.setActivationPolicy(.accessory)

        let configuration = WKWebViewConfiguration()
        configuration.userContentController.add(self, name: "companion")
        webView = WKWebView(frame: .zero, configuration: configuration)
        webView.uiDelegate = self
        webView.navigationDelegate = self
        WebViewTransparency.apply(to: webView)

        panel = CompanionPanel(contentSize: NSSize(width: 132, height: 36))
        panel.contentView = webView
        NotificationCenter.default.addObserver(self, selector: #selector(panelDidResize), name: NSWindow.didResizeNotification, object: panel)

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
        case let .mode(name, width, height):
            applyMode(name: name, pageDefault: CGSize(width: width, height: height))
        }
    }

    // Prefers the user's remembered manual size for `name` over the page's own default, so a hand
    // resize survives the next level change.
    private func applyMode(name: String, pageDefault: CGSize) {
        currentMode = name
        let target = PanelSizeMemory.resolvedSize(mode: name, pageDefault: pageDefault, store: sizeStore)
        let screen = panel.screen?.visibleFrame ?? NSScreen.main?.visibleFrame ?? panel.frame
        let frame = PanelGeometry.frame(resizing: panel.frame, to: target, within: screen)
        isApplyingProgrammaticResize = true
        panel.setFrame(frame, display: true, animate: true)
        // setFrame(animate: true) resizes over a short animation; hold the guard past it so the
        // notifications it fires are not recorded as a manual resize.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { [weak self] in
            self?.isApplyingProgrammaticResize = false
        }
    }

    @objc private func panelDidResize(_ notification: Notification) {
        PanelSizeMemory.recordResize(panel.frame.size, forMode: currentMode, programmatic: isApplyingProgrammaticResize, store: sizeStore)
    }

    // target="_blank" links open in the default browser instead of inside the panel. Only
    // http/https ever reach NSWorkspace; anything else is ignored rather than shelled out to.
    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration, for navigationAction: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        let url = navigationAction.request.url
        if LinkPolicy.isAllowedScheme(url), let url { NSWorkspace.shared.open(url) }
        return nil
    }

    // A same-window navigation away from the companion origin (no target="_blank") opens externally
    // instead of replacing the companion page inside the panel.
    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        let url = navigationAction.request.url
        guard navigationAction.targetFrame?.isMainFrame == true, LinkPolicy.isExternalNavigation(to: url, from: options.url) else {
            decisionHandler(.allow)
            return
        }
        if LinkPolicy.isAllowedScheme(url), let url { NSWorkspace.shared.open(url) }
        decisionHandler(.cancel)
    }
}
