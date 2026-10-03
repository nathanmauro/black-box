import AppKit
import Network
import WebKit
import XCTest
@testable import BlackBoxCompanion

/// Drives the real AppDelegate (panel, status item, WKWebView and its navigation and bridge
/// delegates) against a test-owned loopback server, with the shell's retry and readiness timers
/// compressed so a full backoff sequence runs in well under a second. The web data store and size
/// memory are throwaway and panel autosave is off, so the real shell's saved state is untouched.
final class ShellRecoveryTests: XCTestCase {
    private static let readinessTimeout: TimeInterval = 10
    private var timeScale = 0.02

    private static let healthyPage = page(script: "post();")
    private static let blankPage = "<!doctype html><html><body></body></html>"
    // Posts state from an inline script while a slow image still holds back the load event, so the
    // bridge proof arrives before didFinish.
    private static let earlyStatePage = page(script: "post();", extraBody: "<img src=\"/slow.gif\">")
    // Proves itself alive, then reloads, so the next failure shows whether that proof reset backoff.
    private static let healthyThenReloadPage = page(script: "post(); setTimeout(() => location.reload(), 50);")

    private static func page(script: String, extraBody: String = "") -> String {
        """
        <!doctype html><html><body>\(extraBody)<script>
        function post() { window.webkit.messageHandlers.companion.postMessage({type: 'state', pulse: 'live', unseen: 3}); }
        \(script)
        </script></body></html>
        """
    }

    private var server: FakeCompanionServer!
    private var clock: CompressedClock!
    private var delegate: AppDelegate?
    private var retries: [TimeInterval] = []
    // Whether the web view was still loading (didFinish not yet delivered) when each state arrived.
    private var stateWhileLoading: [Bool] = []

    override func tearDown() {
        clock?.active = false
        if let delegate {
            delegate.webView.stopLoading()
            delegate.webView.configuration.userContentController.removeScriptMessageHandler(forName: "companion")
            delegate.panel.orderOut(nil)
            NSStatusBar.system.removeStatusItem(delegate.statusItem)
        }
        server?.stop()
        delegate = nil
        super.tearDown()
    }

    func testStateBeforeDidFinishKeepsAQuietHealthyPageLoaded() throws {
        try launch { path, _ in
            path == "/slow.gif" ? .init(status: 200, body: "", contentType: "image/gif", delay: 1.0) : .init(status: 200, body: Self.earlyStatePage)
        }
        spin(until: { !self.stateWhileLoading.isEmpty }, "the page posts its first state")
        XCTAssertEqual(stateWhileLoading.first, true, "state should arrive before didFinish for this page")
        spin(until: { self.delegate?.webView.isLoading == false }, "didFinish")
        spin(for: Self.readinessTimeout * self.timeScale * 5)

        XCTAssertEqual(retries, [], "a page that proved itself alive before didFinish must not be retried")
        XCTAssertEqual(server.requests.filter { $0 == "/companion" }.count, 1)
        XCTAssertEqual(delegate?.statusItem.button?.title, "● 3")
    }

    func testBlankPagesBackOffAndAProvenLoadResetsBackoff() throws {
        let sequence = [Self.blankPage, Self.blankPage, Self.blankPage, Self.healthyThenReloadPage]
        try launch { path, index in .init(status: 200, body: index < sequence.count ? sequence[index] : Self.blankPage) }
        spin(until: { self.retries.count >= 4 }, "four retries")

        XCTAssertEqual(Array(retries.prefix(4)), [2, 4, 8, 2])
        XCTAssertFalse(stateWhileLoading.isEmpty, "the healthy page should have posted state")
    }

    func testRejectedResponsesBackOffAndWebContentTerminationRetriesFromTheStart() throws {
        // A slower clock so the recovered page's didFinish (held back 0.3s by its image) lands while
        // the termination retry is still pending, rather than racing it.
        timeScale = 0.5
        try launch { path, index in
            if path == "/slow.gif" { return .init(status: 200, body: "", contentType: "image/gif", delay: 0.3) }
            return index < 2 ? .init(status: 500, body: "down") : .init(status: 200, body: Self.earlyStatePage)
        }
        spin(until: { self.delegate?.statusItem.button?.title == "● 3" }, "recovery after two 500s")
        XCTAssertEqual(retries, [2, 4])

        // WebKit's own termination callback, invoked directly: killing the WebContent process from a
        // test needs private API.
        let delegate = try XCTUnwrap(delegate)
        XCTAssertTrue(delegate.webView.isLoading, "termination should land before this load's didFinish")
        delegate.webViewWebContentProcessDidTerminate(delegate.webView)
        XCTAssertEqual(delegate.statusItem.button?.title, "◌")
        spin(until: { delegate.statusItem.button?.title == "● 3" }, "recovery after termination")
        XCTAssertEqual(retries, [2, 4, 2])
        XCTAssertEqual(server.requests.filter { $0 == "/companion" }.count, 4)
    }

    func testManualReloadCancellingASlowLoadIsNotAFailure() throws {
        try launch { _, index in .init(status: 200, body: Self.healthyPage, delay: index == 0 ? 1.0 : 0) }
        spin(until: {
            self.server.requests.contains("/companion") && self.delegate?.webView.isLoading == true
        }, "the initial slow request to be in flight")
        let menu = try XCTUnwrap(delegate?.statusItem.menu)
        menu.performActionForItem(at: menu.indexOfItem(withTitle: "Reload"))
        spin(until: { self.delegate?.statusItem.button?.title == "● 3" }, "the reloaded page posts state")
        spin(for: 1.2)

        XCTAssertEqual(retries, [], "cancelling our own in-flight load must not schedule a retry")
        XCTAssertEqual(delegate?.statusItem.button?.title, "● 3")
    }

    // MARK: Harness

    private func launch(_ respond: @escaping (_ path: String, _ companionIndex: Int) -> FakeCompanionServer.Response) throws {
        server = try FakeCompanionServer(respond: respond)
        spin(until: { self.server.port != 0 }, "the fake server to listen")
        XCTAssertFalse([8766, 8799].contains(server.port))
        clock = CompressedClock(scale: self.timeScale)

        _ = NSApplication.shared
        var environment = ShellEnvironment(
            sizeStore: InMemoryPanelSizeStore(),
            panelAutosaveName: nil,
            websiteDataStore: .nonPersistent(),
            openExternal: { _ in }
        )
        environment.schedule = { [clock] delay, work in clock!.schedule(delay, work) }
        environment.onRetryScheduled = { [weak self] delay in self?.retries.append(delay) }
        environment.onBridgeMessage = { [weak self] message in
            guard let self, case .state = message else { return }
            self.stateWhileLoading.append(self.delegate?.webView.isLoading ?? false)
        }
        let url = try XCTUnwrap(URL(string: "http://127.0.0.1:\(server.port)/companion?embedded=1"))
        let delegate = AppDelegate(options: Options(url: url, selfTestOutput: nil), environment: environment)
        self.delegate = delegate
        delegate.applicationDidFinishLaunching(Notification(name: NSApplication.didFinishLaunchingNotification))
    }

    private func spin(until condition: () -> Bool, timeout: TimeInterval = 10, _ what: String) {
        let deadline = Date().addingTimeInterval(timeout)
        while !condition(), Date() < deadline {
            RunLoop.main.run(until: Date().addingTimeInterval(0.01))
        }
        XCTAssertTrue(condition(), "timed out waiting for \(what); retries=\(retries) requests=\(server?.requests ?? []) loading=\(delegate?.webView.isLoading ?? false) url=\(delegate?.webView.url?.absoluteString ?? "nil")")
    }

    private func spin(for seconds: TimeInterval) {
        RunLoop.main.run(until: Date().addingTimeInterval(seconds))
    }
}

/// Runs scheduled work after `delay * scale`, honouring cancellation; inert once `active` is false.
private final class CompressedClock {
    var active = true
    private let scale: Double

    init(scale: Double) { self.scale = scale }

    func schedule(_ delay: TimeInterval, _ work: DispatchWorkItem) {
        DispatchQueue.main.asyncAfter(deadline: .now() + delay * scale) { [weak self] in
            guard self?.active == true, !work.isCancelled else { return }
            work.perform()
        }
    }
}

/// A minimal HTTP/1.1 server on an ephemeral 127.0.0.1 port, one response per connection.
private final class FakeCompanionServer {
    struct Response {
        var status: Int
        var body: String
        var contentType = "text/html"
        var delay: TimeInterval = 0
    }

    private let listener: NWListener
    private let respond: (String, Int) -> Response
    private var companionCount = 0
    private(set) var requests: [String] = []
    var port: UInt16 { listener.state == .ready ? listener.port?.rawValue ?? 0 : 0 }

    init(respond: @escaping (_ path: String, _ companionIndex: Int) -> Response) throws {
        self.respond = respond
        let parameters = NWParameters.tcp
        parameters.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: .any)
        listener = try NWListener(using: parameters)
        listener.newConnectionHandler = { [weak self] connection in self?.accept(connection) }
        listener.start(queue: .main)
    }

    func stop() { listener.cancel() }

    private func accept(_ connection: NWConnection) {
        connection.start(queue: .main)
        read(connection, buffer: Data())
    }

    private func read(_ connection: NWConnection, buffer: Data) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 65536) { [weak self] data, _, isComplete, error in
            guard let self else { return }
            let buffer = buffer + (data ?? Data())
            if let text = String(data: buffer, encoding: .utf8), text.contains("\r\n\r\n") {
                self.answer(connection, requestLine: text.components(separatedBy: "\r\n").first ?? "")
            } else if error == nil, !isComplete {
                self.read(connection, buffer: buffer)
            } else {
                connection.cancel()
            }
        }
    }

    private func answer(_ connection: NWConnection, requestLine: String) {
        let target = requestLine.split(separator: " ").dropFirst().first.map(String.init) ?? "/"
        let path = String(target.split(separator: "?", maxSplits: 1).first ?? "/")
        requests.append(path)
        let index = path == "/companion" ? companionCount : -1
        if path == "/companion" { companionCount += 1 }
        let response = respond(path, index)
        let body = Data(response.body.utf8)
        let head = "HTTP/1.1 \(response.status) X\r\nContent-Type: \(response.contentType)\r\nContent-Length: \(body.count)\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        DispatchQueue.main.asyncAfter(deadline: .now() + response.delay) {
            connection.send(content: Data(head.utf8) + body, completion: .contentProcessed { _ in connection.cancel() })
        }
    }
}
