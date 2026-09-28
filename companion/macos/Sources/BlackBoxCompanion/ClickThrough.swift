import AppKit
import WebKit

/// Remembers manual sizes for one run only, so the click-through neither reads nor overwrites the
/// real shell's remembered sizes in UserDefaults.
final class InMemoryPanelSizeStore: PanelSizeStore {
    private(set) var sizes: [String: CGSize] = [:]
    func size(forMode mode: String) -> CGSize? { sizes[mode] }
    func setSize(_ size: CGSize, forMode mode: String) { sizes[mode] = size }
}

private struct StepFailure: Error {
    let step: String
    let reason: String
}

/// `--click-through <dir>`: launches the real AppDelegate (same panel, bridge handler, UI and
/// navigation delegates) against a Black Box URL and drives the page from inside the app with
/// `evaluateJavaScript` and in-process key events, so nothing touches the user's mouse or other
/// apps. Each step polls for its condition, prints one line with what it observed, and the run
/// exits 0 when every step passed or 1 at the first failure (hard timeout 60s).
@MainActor
final class ClickThrough {
    nonisolated private static let hardTimeout: TimeInterval = 60
    nonisolated private static let stepTimeout: TimeInterval = 15
    nonisolated private static let pollInterval: UInt64 = 100_000_000

    private let options: Options
    private let outputDir: URL
    private let sizeStore = InMemoryPanelSizeStore()
    private var delegate: AppDelegate!
    private var openedLinks: [URL] = []
    private var lastMode: [String: CGSize] = [:]
    private var currentStep = "launch"

    static func run(options: Options, outputDir: String) -> Never {
        let app = NSApplication.shared
        let driver = ClickThrough(options: options, outputDir: URL(fileURLWithPath: outputDir, isDirectory: true))
        driver.install(on: app)
        DispatchQueue.main.asyncAfter(deadline: .now() + hardTimeout) {
            ClickThrough.finish(failure: StepFailure(step: driver.currentStep, reason: "hard timeout after \(Int(hardTimeout))s"))
        }
        app.run()
        exit(2)
    }

    private init(options: Options, outputDir: URL) {
        self.options = options
        self.outputDir = outputDir
    }

    private func install(on app: NSApplication) {
        do {
            try FileManager.default.createDirectory(at: outputDir, withIntermediateDirectories: true)
        } catch {
            Self.finish(failure: StepFailure(step: "launch", reason: "cannot create \(outputDir.path): \(error)"))
        }
        let environment = ShellEnvironment(
            sizeStore: sizeStore,
            panelAutosaveName: nil,
            websiteDataStore: .nonPersistent(),
            openExternal: { [weak self] url in self?.openedLinks.append(url) },
            onLaunched: { [weak self] _ in
                guard let self else { return }
                Task { @MainActor in await self.runSteps() }
            },
            onBridgeMessage: { [weak self] message in
                if case let .mode(name, width, height) = message { self?.lastMode[name] = CGSize(width: width, height: height) }
            }
        )
        delegate = AppDelegate(options: options, environment: environment)
        app.delegate = delegate
    }

    private var panel: CompanionPanel { delegate.panel }
    private var webView: WKWebView { delegate.webView }

    private func runSteps() async {
        do {
            try await stepMini()
            try await stepStatusTitle()
            try await stepCompact()
            try await stepExpanded()
            try await stepDeepLink()
            try await stepEscape()
            try await stepStatusTitle(label: "status-title-after")
            try stepNoManualSizeRecorded()
            Self.finish(failure: nil)
        } catch let failure as StepFailure {
            Self.finish(failure: failure)
        } catch {
            Self.finish(failure: StepFailure(step: currentStep, reason: "\(error)"))
        }
    }

    // MARK: Steps

    private func stepMini() async throws {
        currentStep = "mini"
        try await waitForMode("mini")
        let size = try await waitForBridgeSize("mini")
        try await snapshot("mini.png")
        report("mini", "data-mode=mini panel=\(describe(panel.frame)) bridge=\(describe(size)) png=mini.png")
    }

    /// Run once on the first mini chip and again after the round trip: opening the project marks its
    /// rows seen, so the second pass proves the menubar follows the page's state, not just its first.
    private func stepStatusTitle(label: String = "status-title") async throws {
        currentStep = label
        var observed = ""
        _ = try await poll("menubar title matches the chip's live pulse/unseen") { [self] () async -> Bool? in
            let raw = await js("(() => { const c = document.querySelector('.companion-chip'); if (!c) return ''; const n = c.querySelector('.companion-count'); return JSON.stringify({cls: c.className, count: n ? n.textContent : null}); })()")
            guard let data = raw.data(using: .utf8),
                  let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let cls = obj["cls"] as? String,
                  let state = ClickThroughChecks.chipState(className: cls, countText: obj["count"] as? String) else { return nil }
            let title = delegate.statusItem.button?.title ?? ""
            observed = "page pulse=\(state.pulse.rawValue) unseen=\(state.unseen) menubar=\"\(title)\""
            return state.pulse != .connecting && title == ClickThroughChecks.expectedStatusTitle(state) ? true : nil
        }
        report(label, observed)
    }

    private func stepCompact() async throws {
        currentStep = "compact"
        try await click(".companion-chip")
        try await waitForMode("compact")
        let size = try await waitForBridgeSize("compact")
        try await snapshot("compact.png")
        report("compact", "clicked chip; data-mode=compact panel=\(describe(panel.frame)) bridge=\(describe(size)) png=compact.png")
    }

    private func stepExpanded() async throws {
        currentStep = "expanded"
        try await waitForSelector(".companion-project-row")
        let name = await js("(document.querySelector('.companion-project-row .companion-project-name') || {}).textContent || ''")
        try await click(".companion-project-row")
        try await waitForMode("expanded")
        let size = try await waitForBridgeSize("expanded")
        try await waitForSelector(".companion-item")
        try await snapshot("expanded.png")
        report("expanded", "clicked project \"\(name)\"; data-mode=expanded panel=\(describe(panel.frame)) bridge=\(describe(size)) png=expanded.png")
    }

    private func stepDeepLink() async throws {
        currentStep = "deep-link"
        let before = openedLinks.count
        let href = await js("document.querySelector('.companion-item').getAttribute('href') || ''")
        try await click(".companion-item")
        let url = try await poll("the shell's link handler receives the row's URL") { [self] () async -> URL? in
            openedLinks.count > before ? openedLinks[before] : nil
        }
        if let problem = ClickThroughChecks.deepLinkProblem(url, companion: options.url) {
            throw StepFailure(step: currentStep, reason: problem)
        }
        let stayed = webView.url.map { !LinkPolicy.isExternalNavigation(to: $0, from: options.url) && $0.path == options.url.path } ?? false
        guard stayed else { throw StepFailure(step: currentStep, reason: "panel navigated away to \(webView.url?.absoluteString ?? "nil")") }
        try await waitForMode("expanded")
        report("deep-link", "href=\(href) -> shell recorded \(url.absoluteString) (not opened); panel stayed on \(webView.url?.path ?? "?")")
    }

    private func stepEscape() async throws {
        currentStep = "escape-1"
        sendEscape()
        try await waitForMode("compact")
        let compact = try await waitForBridgeSize("compact")
        guard panel.isVisible else { throw StepFailure(step: currentStep, reason: "panel is no longer visible after Escape") }
        report("escape-1", "Escape keyDown into the panel -> data-mode=compact panel=\(describe(panel.frame)) bridge=\(describe(compact)) visible=\(panel.isVisible)")

        currentStep = "escape-2"
        sendEscape()
        try await waitForMode("mini")
        let mini = try await waitForBridgeSize("mini")
        guard panel.isVisible else { throw StepFailure(step: currentStep, reason: "panel is no longer visible after Escape") }
        report("escape-2", "Escape keyDown into the panel -> data-mode=mini panel=\(describe(panel.frame)) bridge=\(describe(mini)) visible=\(panel.isVisible)")
    }

    // Every resize in this run came from the bridge, so none of them may be remembered as manual.
    private func stepNoManualSizeRecorded() throws {
        currentStep = "size-memory"
        guard sizeStore.sizes.isEmpty else {
            throw StepFailure(step: currentStep, reason: "bridge resizes were recorded as manual sizes: \(sizeStore.sizes)")
        }
        report("size-memory", "no programmatic resize was recorded as a manual size")
    }

    // MARK: Helpers

    private func report(_ step: String, _ detail: String) {
        print("click-through: ok \(step): \(detail)")
        fflush(stdout)
    }

    private static func finish(failure: StepFailure?) -> Never {
        if let failure {
            print("click-through: FAIL \(failure.step): \(failure.reason)")
            fflush(stdout)
            exit(1)
        }
        print("click-through: passed")
        fflush(stdout)
        exit(0)
    }

    private func describe(_ rect: CGRect) -> String {
        "\(Int(rect.width))x\(Int(rect.height))@(\(Int(rect.minX)),\(Int(rect.minY)))"
    }

    private func describe(_ size: CGSize) -> String {
        "\(Int(size.width))x\(Int(size.height))"
    }

    /// Evaluates `script` and returns its result as a string ("" for anything non-string).
    private func js(_ script: String) async -> String {
        await withCheckedContinuation { continuation in
            webView.evaluateJavaScript(script) { result, _ in
                continuation.resume(returning: (result as? String) ?? "")
            }
        }
    }

    private func poll<T>(_ what: String, timeout: TimeInterval = stepTimeout, _ probe: () async -> T?) async throws -> T {
        let deadline = Date().addingTimeInterval(timeout)
        while true {
            if let value = await probe() { return value }
            if Date() >= deadline { throw StepFailure(step: currentStep, reason: "timed out after \(Int(timeout))s waiting for \(what)") }
            try await Task.sleep(nanoseconds: Self.pollInterval)
        }
    }

    private func waitForMode(_ mode: String) async throws {
        _ = try await poll(".companion[data-mode=\"\(mode)\"]") { [self] () async -> Bool? in
            await js("(document.querySelector('.companion') || {dataset: {}}).dataset.mode || ''") == mode ? true : nil
        }
    }

    private func waitForSelector(_ selector: String) async throws {
        _ = try await poll(selector) { [self] () async -> Bool? in
            await js("document.querySelector(\(jsString(selector))) ? 'yes' : ''") == "yes" ? true : nil
        }
    }

    /// Waits until the bridge has delivered a mode message for `mode` and the panel frame has settled
    /// at the size the shell should apply for it: the remembered manual size when there is one,
    /// otherwise the page's own size, clamped to the screen.
    private func waitForBridgeSize(_ mode: String) async throws -> CGSize {
        var lastSeen = CGSize.zero
        do {
            return try await poll("the bridge to resize the panel for \(mode)") { [self] () async -> CGSize? in
                guard let pageSize = lastMode[mode] else { return nil }
                let resolved = PanelSizeMemory.resolvedSize(mode: mode, pageDefault: pageSize, store: sizeStore)
                let screen = panel.screen?.visibleFrame ?? NSScreen.main?.visibleFrame ?? panel.frame
                let expected = PanelGeometry.frame(resizing: panel.frame, to: resolved, within: screen).size
                lastSeen = panel.frame.size
                return ClickThroughChecks.sizeMatches(panel.frame.size, expected) ? expected : nil
            }
        } catch let failure as StepFailure {
            throw StepFailure(step: failure.step, reason: "\(failure.reason) (bridge said \(lastMode[mode].map(describe) ?? "nothing"), panel is \(describe(lastSeen)))")
        }
    }

    private func click(_ selector: String) async throws {
        try await waitForSelector(selector)
        let clicked = await js("(() => { const el = document.querySelector(\(jsString(selector))); if (!el) return ''; el.click(); return 'clicked'; })()")
        guard clicked == "clicked" else { throw StepFailure(step: currentStep, reason: "could not click \(selector)") }
    }

    /// An Escape keyDown/keyUp delivered to the panel through AppKit, exactly as a real keypress is
    /// routed once it reaches this window: panel -> first responder (the web view) -> page. It is
    /// an in-process NSEvent, never posted to the window server, so no other app sees it.
    private func sendEscape() {
        panel.makeFirstResponder(webView)
        for type in [NSEvent.EventType.keyDown, .keyUp] {
            if let event = NSEvent.keyEvent(with: type, location: .zero, modifierFlags: [], timestamp: ProcessInfo.processInfo.systemUptime,
                                            windowNumber: panel.windowNumber, context: nil, characters: "\u{1b}",
                                            charactersIgnoringModifiers: "\u{1b}", isARepeat: false, keyCode: 53) {
                panel.sendEvent(event)
            }
        }
    }

    private func jsString(_ text: String) -> String {
        let data = try? JSONSerialization.data(withJSONObject: [text])
        let array = data.flatMap { String(data: $0, encoding: .utf8) } ?? "[\"\"]"
        return "\(array)[0]"
    }

    private func snapshot(_ name: String) async throws {
        let image: NSImage? = await withCheckedContinuation { continuation in
            webView.takeSnapshot(with: nil) { image, _ in continuation.resume(returning: image) }
        }
        guard let image, let tiff = image.tiffRepresentation, let rep = NSBitmapImageRep(data: tiff),
              let png = rep.representation(using: .png, properties: [:]) else {
            throw StepFailure(step: currentStep, reason: "snapshot \(name) failed")
        }
        do {
            try png.write(to: outputDir.appendingPathComponent(name))
        } catch {
            throw StepFailure(step: currentStep, reason: "writing \(name) failed: \(error)")
        }
    }
}
