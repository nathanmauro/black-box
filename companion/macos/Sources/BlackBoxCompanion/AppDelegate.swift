import AppKit
import WebKit

/// What differs between the real shell and the `--click-through` run. Everything else (panel, bridge
/// handler, UI and navigation delegates) is the same code path.
struct ShellEnvironment {
    var sizeStore: PanelSizeStore
    var panelAutosaveName: String?
    var websiteDataStore: WKWebsiteDataStore
    /// Where an http(s) link the shell decided to open externally goes.
    var openExternal: (URL) -> Void
    var onLaunched: ((AppDelegate) -> Void)?
    var onBridgeMessage: ((BridgeMessage) -> Void)?

    static func live() -> ShellEnvironment {
        ShellEnvironment(
            sizeStore: UserDefaultsPanelSizeStore(),
            panelAutosaveName: CompanionPanel.defaultAutosaveName,
            websiteDataStore: .default(),
            openExternal: { NSWorkspace.shared.open($0) }
        )
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate, WKScriptMessageHandler, WKUIDelegate, WKNavigationDelegate {
    private let options: Options
    private let environment: ShellEnvironment
    private var sizeStore: PanelSizeStore { environment.sizeStore }
    private(set) var statusItem: NSStatusItem!
    private(set) var panel: CompanionPanel!
    private(set) var webView: WKWebView!
    private var toggleItem: NSMenuItem!
    private var currentMode = "mini"
    // Set while a bridge mode message is driving the panel's frame, so the resize notification it
    // triggers is not mistaken for — and does not overwrite — a manual resize.
    private var isApplyingProgrammaticResize = false
    // Counts consecutive failed loads (server down at launch, mid-`mvn package`/`launchctl kickstart`
    // 500s, a WebContent process crash) so retries back off instead of hammering the server; reset on
    // the next successful navigation.
    private var retryAttempt = 0
    private var pendingRetry: DispatchWorkItem?

    init(options: Options, environment: ShellEnvironment = .live()) {
        self.options = options
        self.environment = environment
        super.init()
    }

    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.setActivationPolicy(.accessory)

        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = environment.websiteDataStore
        configuration.userContentController.add(self, name: "companion")
        // DraggableWebView (not plain WKWebView) so isMovableByWindowBackground below actually works;
        // see its doc comment.
        webView = DraggableWebView(frame: .zero, configuration: configuration)
        webView.uiDelegate = self
        webView.navigationDelegate = self
        WebViewTransparency.apply(to: webView)

        panel = CompanionPanel(contentSize: NSSize(width: 132, height: 36), autosaveName: environment.panelAutosaveName)
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
        // A manual escape hatch: a launch-time failure or a stuck retry loop is otherwise recoverable
        // only by quitting and relaunching.
        let reload = NSMenuItem(title: "Reload", action: #selector(reloadNow), keyEquivalent: "")
        reload.target = self
        menu.addItem(reload)
        menu.addItem(.separator())
        menu.addItem(NSMenuItem(title: "Quit", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q"))
        statusItem.menu = menu

        webView.load(URLRequest(url: options.url))
        panel.orderFrontRegardless()
        environment.onLaunched?(self)
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
        if let url = components.url { environment.openExternal(url) }
    }

    func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
        guard let parsed = BridgeMessage.parse(message.body) else { return }
        defer { environment.onBridgeMessage?(parsed) }
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
        if LinkPolicy.isAllowedScheme(url), let url { environment.openExternal(url) }
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
        if LinkPolicy.isAllowedScheme(url), let url { environment.openExternal(url) }
        decisionHandler(.cancel)
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        retryAttempt = 0
        pendingRetry?.cancel()
        pendingRetry = nil
    }

    // The server can be down at launch, or return 500s for the SPA's own assets mid `mvn package` /
    // `launchctl kickstart -k` cycle. With no handler at all here the panel — transparent, so a
    // failed load renders as nothing — and the menubar (stuck on the connecting glyph, since it only
    // ever updates from a bridge `state` message the page never got to send) both looked fine while
    // being silently dead, recoverable only by quitting and relaunching.
    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        handleLoadFailure()
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        handleLoadFailure()
    }

    // WebKit's WebContent process can be killed independently of the app (memory pressure, a helper
    // crash); without this, the page's JS — and so its bridge `state` messages — stops, leaving the
    // panel blank and the menubar frozen at its last pulse, which reads as quiet or live rather than
    // disconnected.
    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        handleLoadFailure()
    }

    private func handleLoadFailure() {
        statusItem.button?.title = StatusTitle.render(pulse: .disconnected, unseen: 0)
        scheduleRetry()
    }

    private func scheduleRetry() {
        pendingRetry?.cancel()
        let delay = LoadRetryPolicy.delay(forAttempt: retryAttempt)
        retryAttempt += 1
        let work = DispatchWorkItem { [weak self] in self?.reloadNow() }
        pendingRetry = work
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: work)
    }

    @objc private func reloadNow() {
        pendingRetry?.cancel()
        pendingRetry = nil
        webView.load(URLRequest(url: options.url))
    }
}
