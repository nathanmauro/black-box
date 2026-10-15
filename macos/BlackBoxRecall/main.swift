import AppKit
import Carbon
import OSLog

private let bundleID = "dev.blackbox.quick-recall"
private let showNotification = NSNotification.Name("dev.blackbox.quick-recall.show")

private final class SuggestionButton: NSButton {
    init(titleText: String, detailText: String) {
        super.init(frame: .zero)
        title = ""
        isBordered = false
        setButtonType(.momentaryPushIn)
        let titleLabel = NSTextField(labelWithString: titleText)
        titleLabel.font = .systemFont(ofSize: 12, weight: .medium)
        let detailLabel = NSTextField(labelWithString: detailText)
        detailLabel.font = .systemFont(ofSize: 11)
        detailLabel.textColor = .secondaryLabelColor
        for label in [titleLabel, detailLabel] {
            label.maximumNumberOfLines = 1
            label.lineBreakMode = .byTruncatingTail
            label.setAccessibilityElement(false)
            label.translatesAutoresizingMaskIntoConstraints = false
            addSubview(label)
        }
        NSLayoutConstraint.activate([
            titleLabel.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 5),
            titleLabel.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -5),
            titleLabel.topAnchor.constraint(equalTo: topAnchor, constant: 6),
            detailLabel.leadingAnchor.constraint(equalTo: titleLabel.leadingAnchor),
            detailLabel.trailingAnchor.constraint(equalTo: titleLabel.trailingAnchor),
            detailLabel.topAnchor.constraint(equalTo: titleLabel.bottomAnchor, constant: 2)
        ])
        setAccessibilityLabel("\(titleText), \(detailText)")
        toolTip = "Open this capture in Black Box"
    }

    required init?(coder: NSCoder) { fatalError("Storyboard initialization is unsupported") }

    override func hitTest(_ point: NSPoint) -> NSView? {
        super.hitTest(point) == nil ? nil : self
    }
}

private final class TraceDocument: NSStackView {
    override var isFlipped: Bool { true }
}

final class AppDelegate: NSObject, NSApplicationDelegate, NSPopoverDelegate, NSSearchFieldDelegate {
    private let logger = Logger(subsystem: bundleID, category: "quick-recall")
    private var statusItem: NSStatusItem!
    private let popover = NSPopover()
    private let searchField = NSSearchField()
    private let suggestionsStack = NSStackView()
    private var suggestions: [RecallSuggestion] = []
    private var previewGeneration = 0
    private var previewDebounce: DispatchWorkItem?
    private var previewTask: URLSessionDataTask?
    private let previewSession: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 8
        configuration.timeoutIntervalForResource = 8
        configuration.urlCache = nil
        configuration.httpCookieStorage = nil
        return URLSession(configuration: configuration)
    }()
    private let traceClient = JevTraceClient()
    private let traceButton = NSButton(title: "Trace with Jev", target: nil, action: nil)
    private let traceScroll = NSScrollView()
    private let traceDocument = TraceDocument()
    private var traceHeight: NSLayoutConstraint?
    private var traceGeneration = 0
    private var traceTask: Task<Void, Never>?
    private var traceURLs: [URL] = []
    private let messageLabel = NSTextField(wrappingLabelWithString: "")
    private var hotKey: EventHotKeyRef?
    private var eventHandler: EventHandlerRef?
    private var baseURL: URL?
    private var persistentMessage = ""
    private var chromeProcesses: [Process] = []

    func applicationDidFinishLaunching(_ notification: Notification) {
        if let existing = NSRunningApplication.runningApplications(withBundleIdentifier: bundleID)
            .first(where: { $0.processIdentifier != ProcessInfo.processInfo.processIdentifier }) {
            DistributedNotificationCenter.default().postNotificationName(showNotification, object: nil, userInfo: nil, deliverImmediately: true)
            existing.activate(options: [])
            NSApp.terminate(nil)
            return
        }
        do {
            baseURL = try RecallURL.base(ProcessInfo.processInfo.environment["BLACKBOX_URL"] ?? RecallURL.defaultBase)
        } catch { persistentMessage = error.localizedDescription }
        setupEditMenu()
        setupPopover()
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)
        if let button = statusItem.button {
            button.image = NSImage(systemSymbolName: "shippingbox", accessibilityDescription: "Black Box Recall")
            button.setAccessibilityLabel("Black Box Recall")
            button.toolTip = "Black Box Recall (Control–Option–B)"
            button.target = self
            button.action = #selector(togglePopover)
        }
        DistributedNotificationCenter.default().addObserver(self, selector: #selector(showPopover), name: showNotification, object: nil)
        registerHotKey()
        setMessage(persistentMessage)
        if !persistentMessage.isEmpty { showPopover() }
    }

    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        showPopover()
        return false
    }

    private func setupEditMenu() {
        // Standard responder-chain actions let the search field handle clipboard shortcuts.
        let menu = NSMenu()
        let appItem = NSMenuItem()
        let appMenu = NSMenu()
        let quit = NSMenuItem(title: "Quit Black Box Recall", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
        quit.target = NSApp
        appMenu.addItem(quit)
        appItem.submenu = appMenu
        menu.addItem(appItem)
        let editItem = NSMenuItem()
        let editMenu = NSMenu(title: "Edit")
        editMenu.addItem(withTitle: "Undo", action: Selector(("undo:")), keyEquivalent: "z")
        let redo = editMenu.addItem(withTitle: "Redo", action: Selector(("redo:")), keyEquivalent: "z")
        redo.keyEquivalentModifierMask = [.command, .shift]
        editMenu.addItem(.separator())
        editMenu.addItem(withTitle: "Cut", action: #selector(NSText.cut(_:)), keyEquivalent: "x")
        editMenu.addItem(withTitle: "Copy", action: #selector(NSText.copy(_:)), keyEquivalent: "c")
        editMenu.addItem(withTitle: "Paste", action: #selector(NSText.paste(_:)), keyEquivalent: "v")
        editMenu.addItem(withTitle: "Select All", action: #selector(NSText.selectAll(_:)), keyEquivalent: "a")
        editItem.submenu = editMenu
        menu.addItem(editItem)
        NSApp.mainMenu = menu
    }

    private func setupPopover() {
        popover.behavior = .transient
        popover.delegate = self
        let controller = NSViewController()
        let view = NSView()
        controller.view = view
        popover.contentViewController = controller
        let heading = NSTextField(labelWithString: "Black Box Recall")
        heading.font = .boldSystemFont(ofSize: 14)
        searchField.placeholderString = "Project, topic, or remembered phrase"
        searchField.delegate = self
        searchField.target = self
        searchField.action = #selector(openResults)
        searchField.setAccessibilityLabel("Recall query")
        searchField.sendsWholeSearchString = true
        searchField.sendsSearchStringImmediately = false
        let shortcut = NSTextField(labelWithString: "Control–Option–B · Return to search · Escape to close")
        shortcut.font = .systemFont(ofSize: 11)
        shortcut.textColor = .secondaryLabelColor
        let openButton = NSButton(title: "Open results", target: self, action: #selector(openResults))
        openButton.bezelStyle = .rounded
        openButton.isEnabled = baseURL != nil
        traceButton.target = self
        traceButton.action = #selector(startTrace)
        traceButton.bezelStyle = .rounded
        traceButton.keyEquivalent = "j"
        traceButton.keyEquivalentModifierMask = .command
        traceButton.toolTip = "Explicitly send bounded retrieved capture excerpts to TypeSafe for an advisory trace (Command–J)."
        traceButton.isEnabled = baseURL != nil
        let quitButton = NSButton(title: "Quit", target: NSApp, action: #selector(NSApplication.terminate(_:)))
        quitButton.bezelStyle = .inline
        let buttons = NSStackView(views: [openButton, traceButton, NSView(), quitButton])
        buttons.orientation = .horizontal
        messageLabel.font = .systemFont(ofSize: 11)
        messageLabel.textColor = .secondaryLabelColor
        suggestionsStack.orientation = .vertical
        suggestionsStack.alignment = .leading
        suggestionsStack.spacing = 4
        suggestionsStack.isHidden = true
        traceDocument.orientation = .vertical
        traceDocument.alignment = .leading
        traceDocument.spacing = 7
        traceScroll.hasVerticalScroller = true
        traceScroll.autohidesScrollers = true
        traceScroll.drawsBackground = false
        traceScroll.documentView = traceDocument
        traceScroll.isHidden = true
        traceHeight = traceScroll.heightAnchor.constraint(equalToConstant: 0)
        traceHeight?.isActive = true
        let stack = NSStackView(views: [heading, searchField, suggestionsStack, traceScroll, shortcut, messageLabel, buttons])
        stack.orientation = .vertical
        stack.alignment = .leading
        stack.spacing = 10
        stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)
        NSLayoutConstraint.activate([
            view.widthAnchor.constraint(equalToConstant: 390),
            stack.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 16),
            stack.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -16),
            stack.topAnchor.constraint(equalTo: view.topAnchor, constant: 16),
            stack.bottomAnchor.constraint(equalTo: view.bottomAnchor, constant: -16),
            searchField.widthAnchor.constraint(equalTo: stack.widthAnchor),
            suggestionsStack.widthAnchor.constraint(equalTo: stack.widthAnchor),
            traceScroll.widthAnchor.constraint(equalTo: stack.widthAnchor),
            messageLabel.widthAnchor.constraint(equalTo: stack.widthAnchor),
            buttons.widthAnchor.constraint(equalTo: stack.widthAnchor)
        ])
    }

    private func registerHotKey() {
        var spec = EventTypeSpec(eventClass: OSType(kEventClassKeyboard), eventKind: UInt32(kEventHotKeyPressed))
        let context = Unmanaged.passUnretained(self).toOpaque()
        let handlerStatus = InstallEventHandler(GetApplicationEventTarget(), { _, _, context in
            guard let context else { return OSStatus(eventNotHandledErr) }
            let delegate = Unmanaged<AppDelegate>.fromOpaque(context).takeUnretainedValue()
            DispatchQueue.main.async { delegate.showPopover() }
            return noErr
        }, 1, &spec, context, &eventHandler)
        let identifier = EventHotKeyID(signature: 0x42425243, id: 1)
        let keyStatus = handlerStatus == noErr
            ? RegisterEventHotKey(UInt32(kVK_ANSI_B), UInt32(controlKey | optionKey), identifier,
                                  GetApplicationEventTarget(), 0, &hotKey)
            : handlerStatus
        if keyStatus != noErr {
            let message = "Control–Option–B is unavailable (macOS error \(keyStatus)). Free this shortcut in the other app or System Settings → Keyboard → Keyboard Shortcuts, then relaunch. The menu bar button still works."
            persistentMessage = [persistentMessage, message].filter { !$0.isEmpty }.joined(separator: "\n")
        }
    }

    @objc private func togglePopover() {
        if popover.isShown { popover.performClose(nil) } else { showPopover() }
    }

    @objc func showPopover() {
        guard let button = statusItem?.button else { return }
        setMessage(persistentMessage)
        NSApp.activate(ignoringOtherApps: true)
        if !popover.isShown { popover.show(relativeTo: button.bounds, of: button, preferredEdge: .minY) }
        focusSearch()
        schedulePreview()
    }

    func popoverDidShow(_ notification: Notification) {
        focusSearch()
    }

    func popoverDidClose(_ notification: Notification) {
        cancelPreview()
        cancelTrace()
    }

    func controlTextDidChange(_ notification: Notification) {
        cancelTrace(clear: true)
        schedulePreview()
    }

    private func cancelPreview() {
        previewGeneration += 1
        previewDebounce?.cancel()
        previewDebounce = nil
        previewTask?.cancel()
        previewTask = nil
    }

    private func schedulePreview() {
        cancelPreview()
        suggestions = []
        let query = searchField.stringValue.trimmingCharacters(in: .whitespacesAndNewlines)
        guard popover.isShown, let baseURL, query.count >= RecallSuggestions.minimumQueryLength else {
            renderSuggestions([], message: nil)
            return
        }
        renderSuggestions([], message: "Looking for matches…")
        let generation = previewGeneration
        let work = DispatchWorkItem { [weak self] in
            guard let self, self.previewGeneration == generation, self.popover.isShown else { return }
            self.previewDebounce = nil
            let request = RecallSuggestions.request(base: baseURL, query: query)
            self.previewTask = self.previewSession.dataTask(with: request) { [weak self] data, response, error in
                DispatchQueue.main.async {
                    guard let self, self.previewGeneration == generation, self.popover.isShown else { return }
                    self.previewTask = nil
                    if let error {
                        let message = (error as? URLError)?.code == .appTransportSecurityRequiresSecureConnection
                            ? "Live matches need HTTPS for this server. Press Return to search in Chrome."
                            : "Couldn’t load matches. Check that Black Box is running, or press Return to search in Chrome."
                        self.renderSuggestions([], message: message)
                        return
                    }
                    guard let response = response as? HTTPURLResponse else {
                        self.renderSuggestions([], message: "Matches unavailable. Press Return to search in Chrome.")
                        return
                    }
                    guard (200...299).contains(response.statusCode) else {
                        let message = [401, 403].contains(response.statusCode)
                            ? "This server requires sign-in. Press Return to search in Chrome."
                            : "Matches unavailable (HTTP \(response.statusCode)). Press Return to search in Chrome."
                        self.renderSuggestions([], message: message)
                        return
                    }
                    guard let data, data.count <= 1_000_000,
                          let matches = try? RecallSuggestions.decode(data) else {
                        self.renderSuggestions([], message: "Couldn’t read matches. Press Return to search in Chrome.")
                        return
                    }
                    self.renderSuggestions(matches, message: matches.isEmpty ? "No matches in the past week. Press Return for more filters." : nil)
                }
            }
            self.previewTask?.resume()
        }
        previewDebounce = work
        DispatchQueue.main.asyncAfter(deadline: .now() + RecallSuggestions.debounceSeconds, execute: work)
    }

    private func renderSuggestions(_ items: [RecallSuggestion], message: String?) {
        suggestions = items
        for view in suggestionsStack.arrangedSubviews {
            suggestionsStack.removeArrangedSubview(view)
            view.removeFromSuperview()
        }
        if let message {
            let label = NSTextField(wrappingLabelWithString: message)
            label.font = .systemFont(ofSize: 11)
            label.textColor = .secondaryLabelColor
            suggestionsStack.addArrangedSubview(label)
            label.widthAnchor.constraint(equalTo: suggestionsStack.widthAnchor).isActive = true
        }
        for (index, item) in items.enumerated() {
            let button = SuggestionButton(titleText: item.title, detailText: item.detail)
            button.target = self
            button.action = #selector(openSuggestion(_:))
            button.tag = index
            suggestionsStack.addArrangedSubview(button)
            NSLayoutConstraint.activate([
                button.widthAnchor.constraint(equalTo: suggestionsStack.widthAnchor),
                button.heightAnchor.constraint(equalToConstant: 46)
            ])
        }
        suggestionsStack.isHidden = items.isEmpty && message == nil
        resizePopover()
    }

    @objc private func openSuggestion(_ sender: NSButton) {
        guard let baseURL, suggestions.indices.contains(sender.tag) else { return }
        openURL(suggestions[sender.tag].eventURL(base: baseURL))
    }

    private func cancelTrace(clear: Bool = false) {
        traceGeneration += 1
        traceTask?.cancel()
        traceTask = nil
        traceButton.title = "Trace with Jev"
        if clear { renderTraceMessage(nil) }
    }

    @objc private func startTrace() {
        cancelTrace(clear: true)
        guard popover.isShown, let baseURL else { return }
        let query: String
        do { query = try JevTraceCore.query(searchField.stringValue) }
        catch { renderTraceMessage(JevTraceError.invalidQuery.localizedDescription); return }
        guard ProcessInfo.processInfo.environment["BLACKBOX_JEV_ENABLED"] == "1" else {
            renderTraceMessage(JevTraceError.missingKey.localizedDescription)
            return
        }
        guard let key = JevKeychain.read() else {
            renderTraceMessage(JevTraceError.unavailableKeychain.localizedDescription)
            return
        }
        let generation = traceGeneration
        traceButton.title = "Restart trace"
        renderTraceMessage("Retrieving captures for Jev… Bounded source excerpts are sent to TypeSafe only for this action.")
        traceTask = Task { [weak self] in
            guard let self else { return }
            do {
                let result = try await self.traceClient.trace(query: query, base: baseURL, key: key) { [weak self] phase in
                    guard let self, self.traceGeneration == generation, self.popover.isShown else { return }
                    self.renderTraceMessage(phase)
                }
                await MainActor.run {
                    guard self.traceGeneration == generation, self.popover.isShown else { return }
                    self.traceTask = nil
                    self.traceButton.title = "Trace with Jev"
                    self.renderTrace(result, base: baseURL)
                }
            } catch {
                await MainActor.run {
                    guard self.traceGeneration == generation, self.popover.isShown else { return }
                    self.traceTask = nil
                    self.traceButton.title = "Trace with Jev"
                    if !(error is CancellationError) {
                        self.renderTraceMessage((error as? JevTraceError)?.localizedDescription ?? JevTraceError.retrieval.localizedDescription)
                    }
                }
            }
        }
    }

    private func clearTraceViews() {
        traceURLs = []
        for view in traceDocument.arrangedSubviews {
            traceDocument.removeArrangedSubview(view)
            view.removeFromSuperview()
        }
    }

    private func addTraceText(_ text: String, bold: Bool = false) {
        let label = NSTextField(wrappingLabelWithString: text)
        label.font = bold ? .boldSystemFont(ofSize: 12) : .systemFont(ofSize: 11)
        label.textColor = bold ? .labelColor : .secondaryLabelColor
        traceDocument.addArrangedSubview(label)
        label.widthAnchor.constraint(equalToConstant: 337).isActive = true
    }

    private func renderTraceMessage(_ message: String?) {
        clearTraceViews()
        if let message { addTraceText(message) }
        updateTraceSize()
    }

    private func renderTrace(_ result: TraceResult, base: URL) {
        clearTraceViews()
        if let anchor = result.anchor { addTraceCapture(anchor, heading: "You may remember this", base: base) }
        if let later = result.later {
            addTraceCapture(later, heading: "Captured later · " + (result.relation?.label ?? "Related follow-up"), base: base)
        }
        addTraceText(result.note)
        updateTraceSize()
    }

    private func addTraceCapture(_ capture: TraceCapture, heading: String, base: URL) {
        addTraceText(heading, bold: true)
        let date = capture.date.map { DateFormatter.localizedString(from: $0, dateStyle: .medium, timeStyle: .short) } ?? "Date unavailable"
        addTraceText("\(capture.project) · \(capture.kind.capitalized) · \(date)")
        addTraceText("Source excerpt:\n" + capture.excerpt)
        let button = NSButton(title: "Open capture", target: self, action: #selector(openTraceCapture(_:)))
        button.bezelStyle = .rounded
        button.tag = traceURLs.count
        button.setAccessibilityLabel("Open capture: " + heading)
        traceURLs.append(capture.eventURL(base: base))
        traceDocument.addArrangedSubview(button)
    }

    private func updateTraceSize() {
        traceScroll.isHidden = traceDocument.arrangedSubviews.isEmpty
        if !traceScroll.isHidden {
            traceDocument.frame = NSRect(x: 0, y: 0, width: 337, height: max(20, traceDocument.fittingSize.height))
            traceDocument.layoutSubtreeIfNeeded()
            traceDocument.frame.size.height = max(20, traceDocument.fittingSize.height)
            traceHeight?.constant = min(220, traceDocument.frame.height + 5)
            traceDocument.scroll(.zero)
        } else { traceHeight?.constant = 0 }
        resizePopover()
    }

    @objc private func openTraceCapture(_ sender: NSButton) {
        guard traceURLs.indices.contains(sender.tag) else { return }
        openURL(traceURLs[sender.tag])
    }

    private func focusSearch() {
        guard let window = popover.contentViewController?.view.window else { return }
        window.title = "Black Box Recall"
        window.makeKeyAndOrderFront(nil)
        window.makeFirstResponder(searchField)
        let size = searchField.frame.size
        logger.notice("Search shown; field size \(size.width)x\(size.height), key window \(window.isKeyWindow)")
    }

    func control(_ control: NSControl, textView: NSTextView, doCommandBy commandSelector: Selector) -> Bool {
        if commandSelector == #selector(NSResponder.insertNewline(_:)) { openResults(); return true }
        if commandSelector == #selector(NSResponder.cancelOperation(_:)) { popover.performClose(nil); return true }
        return false
    }

    @objc private func openResults() {
        guard let baseURL else { showPopover(); return }
        logger.notice("Search submitted")
        openURL(RecallURL.search(base: baseURL, query: searchField.stringValue))
    }

    private func openURL(_ url: URL) {
        guard let baseURL else { return }
        cancelPreview()
        cancelTrace()
        setMessage("Opening results…")
        guard let chrome = NSWorkspace.shared.urlForApplication(withBundleIdentifier: "com.google.Chrome"),
              let executable = Bundle(url: chrome)?.executableURL else {
            showLaunchError("Google Chrome was not found. Install Chrome or its Black Box web app, then try again.")
            return
        }
        let launch = findBlackBoxWebApp(base: baseURL)
        // Chrome's app shim may activate successfully while discarding supplied URLs. Launch
        // Chrome with its app-ID + launch-URL switches; a running Chrome forwards this argv.
        let process = Process()
        process.executableURL = executable
        var browserEnvironment = ProcessInfo.processInfo.environment
        browserEnvironment.removeValue(forKey: "TYPESAFE_API_KEY")
        process.environment = browserEnvironment
        process.arguments = launch?.arguments(for: url) ?? [url.absoluteString]
        process.standardOutput = FileHandle.nullDevice
        process.standardError = FileHandle.nullDevice
        process.terminationHandler = { [weak self] finished in
            DispatchQueue.main.async {
                self?.chromeProcesses.removeAll { $0 === finished }
                if finished.terminationStatus != 0 {
                    self?.showLaunchError("Chrome could not open the search. Open Chrome, then try again.")
                }
            }
        }
        do {
            try process.run()
            chromeProcesses.append(process)
            popover.performClose(nil)
        } catch {
            showLaunchError("Could not start Chrome: \(error.localizedDescription)")
        }
    }

    private func findBlackBoxWebApp(base: URL) -> ChromeLaunch? {
        let directory = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Applications/Chrome Apps.localized", isDirectory: true)
        let apps = (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)) ?? []
        for app in apps.sorted(by: { $0.path < $1.path }) {
            guard app.pathExtension == "app",
                  let data = try? Data(contentsOf: app.appendingPathComponent("Contents/Info.plist")),
                  let plist = (try? PropertyListSerialization.propertyList(from: data, format: nil)) as? [String: Any],
                  let shortcut = plist["CrAppModeShortcutURL"] as? String,
                  let shortcutURL = try? RecallURL.base(shortcut), RecallURL.sameOrigin(base, shortcutURL)
            else { continue }
            let names = [plist["CrAppModeShortcutName"], plist["CFBundleDisplayName"], plist["CFBundleName"]]
            guard names.contains(where: { ($0 as? String)?.caseInsensitiveCompare("Black Box") == .orderedSame })
                || app.deletingPathExtension().lastPathComponent.caseInsensitiveCompare("Black Box") == .orderedSame,
                  let appID = plist["CrAppModeShortcutID"] as? String,
                  let appData = plist["CrAppModeUserDataDir"] as? String else { continue }
            let userData = URL(fileURLWithPath: appData).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            guard let data = try? Data(contentsOf: userData.appendingPathComponent("Local State")),
                  let state = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
                  let registrations = state["app_shims"] as? [String: Any],
                  let registration = registrations[appID] as? [String: Any],
                  let launch = ChromeLaunch(info: plist, registration: registration),
                  FileManager.default.fileExists(atPath: launch.userDataDirectory.appendingPathComponent(launch.profile).path)
            else { continue }
            return launch
        }
        return nil
    }

    private func setMessage(_ message: String) {
        messageLabel.stringValue = message.isEmpty ? "Decisions and handoffs · Past week · More filters in results" : message
        resizePopover()
    }

    private func resizePopover() {
        if let view = popover.contentViewController?.view {
            view.layoutSubtreeIfNeeded()
            popover.contentSize = NSSize(width: 390, height: min(650, max(180, view.fittingSize.height)))
        }
    }

    private func showLaunchError(_ message: String) {
        showPopover()
        setMessage([persistentMessage, message].filter { !$0.isEmpty }.joined(separator: "\n"))
    }

    func applicationWillTerminate(_ notification: Notification) {
        cancelPreview()
        cancelTrace()
        traceClient.invalidate()
        previewSession.invalidateAndCancel()
        if let hotKey { UnregisterEventHotKey(hotKey) }
        if let eventHandler { RemoveEventHandler(eventHandler) }
        DistributedNotificationCenter.default().removeObserver(self)
    }
}

// Key setup is explicit and uses the installed bundle so its Keychain access owns the record.
let commandArguments = Array(CommandLine.arguments.dropFirst())
if commandArguments == ["--validate-jev-key"] {
    if JevTraceClient.validKey(ProcessInfo.processInfo.environment["TYPESAFE_API_KEY"]) != nil { exit(0) }
    FileHandle.standardError.write(Data("TYPESAFE_API_KEY is missing or invalid; no value was printed.\n".utf8))
    exit(1)
}
if commandArguments == ["--check-jev-key"] {
    let present = JevKeychain.read() != nil
    print(present ? "present" : "unavailable")
    exit(present ? 0 : 1)
}
if commandArguments == ["--configure-jev-key"] {
    let installed = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Applications/Black Box Recall.app")
    if Bundle.main.bundleIdentifier != bundleID || Bundle.main.bundleURL.standardizedFileURL != installed.standardizedFileURL {
        FileHandle.standardError.write(Data("Configure Jev using the installed Black Box Recall bundle.\n".utf8))
        exit(1)
    }
    do { try JevKeychain.configureFromEnvironment(); print("Jev key configured in the helper’s Keychain record."); exit(0) }
    catch { FileHandle.standardError.write(Data(((error as? JevTraceError)?.localizedDescription ?? "Jev key setup failed.").utf8)); exit(1) }
}

// Used by the installer before persisting optional configuration; no GUI is created.
if Array(CommandLine.arguments.dropFirst()) == ["--validate-base-url"] {
    do {
        _ = try RecallURL.base(ProcessInfo.processInfo.environment["BLACKBOX_URL"] ?? RecallURL.defaultBase)
        exit(0)
    } catch {
        FileHandle.standardError.write(Data((error.localizedDescription + "\n").utf8))
        exit(1)
    }
}

let app = NSApplication.shared
let delegate = AppDelegate()
app.delegate = delegate
app.setActivationPolicy(.accessory)
app.run()
