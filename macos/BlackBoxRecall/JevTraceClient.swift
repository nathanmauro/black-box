import Foundation

private final class TraceNoRedirects: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}

final class JevTraceClient {
    private let redirects = TraceNoRedirects()
    private let testConfiguration: URLSessionConfiguration?

    init(configuration: URLSessionConfiguration? = nil) { testConfiguration = configuration }
    private lazy var session: URLSession = {
        let configuration = testConfiguration ?? URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 10
        configuration.timeoutIntervalForResource = 10
        configuration.urlCache = nil
        configuration.httpCookieStorage = nil
        return URLSession(configuration: configuration, delegate: redirects, delegateQueue: nil)
    }()

    func invalidate() { session.invalidateAndCancel() }

    static func validKey(_ value: String?) -> String? {
        guard let value else { return nil }
        let key = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return (8...4096).contains(key.count) && !key.contains(where: { $0.isWhitespace || $0.unicodeScalars.contains { $0.value < 32 } }) ? key : nil
    }

    func trace(query: String, base: URL, key: String, phase: @MainActor @escaping (String) -> Void = { _ in }) async throws -> TraceResult {
        let query = try JevTraceCore.query(query)
        let start = Date()
        await phase("Retrieving remembered captures…")
        let anchors = try await retrieve(base: base, scope: query, limit: 12)
        var result = try await run(query: query, anchors: anchors, key: key, phase: phase) { anchor in
            guard let repo = anchor.repo, !repo.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, anchor.date != nil else { return [] }
            await phase("Retrieving later captures in the same repository…")
            return try await self.retrieve(base: base, scope: repo, limit: 50)
        }
        result.latencyMilliseconds = Int(Date().timeIntervalSince(start) * 1000)
        return result
    }

    func synthetic(query: String, anchors: [TraceCapture], later: [TraceCapture], key: String) async throws -> TraceResult {
        try await run(query: JevTraceCore.query(query), anchors: Array(anchors.prefix(12)), key: key, phase: { _ in }) { _ in later }
    }

    private func run(query: String, anchors: [TraceCapture], key: String, phase: @MainActor @escaping (String) -> Void,
                     fetchLater: (TraceCapture) async throws -> [TraceCapture]) async throws -> TraceResult {
        let start = Date()
        guard let key = Self.validKey(key) else { throw JevTraceError.missingKey }
        try Task.checkCancellation()
        guard !anchors.isEmpty else {
            return TraceResult(anchor: nil, later: nil, relation: nil, note: "No matching captures were retrieved in the past 30 days.")
        }
        await phase("Jev is selecting a remembered capture…")
        let call = JevTraceCore.anchorCall(query: query, captures: anchors)
        let first = try await evaluate(call, key: key)
        guard let anchor = JevTraceCore.selectAnchor(first, captures: anchors) else {
            return finished(TraceResult(anchor: nil, later: nil, relation: nil, note: "No clear remembered capture among the retrieved candidates."), calls: [first], count: 1, start: start)
        }
        var attempted = 1
        do {
            try Task.checkCancellation()
            let fetched = try await fetchLater(anchor)
            let candidates = JevTraceCore.newerCaptures(anchor: anchor, captures: fetched)
            guard !candidates.isEmpty else {
                return finished(JevTraceCore.compose(anchor: anchor, candidates: [], response: nil), calls: [first], count: 1, start: start)
            }
            await phase("Jev is checking later source evidence…")
            try Task.checkCancellation()
            attempted = 2
            let second = try await evaluate(JevTraceCore.laterCall(query: query, anchor: anchor, candidates: candidates), key: key)
            let result = JevTraceCore.compose(anchor: anchor, candidates: candidates, response: second)
            return finished(result, calls: [first, second], count: 2, start: start)
        } catch is CancellationError { throw CancellationError() }
        catch {
            if Task.isCancelled { throw CancellationError() }
            let safe = (error as? JevTraceError)?.localizedDescription ?? JevTraceError.retrieval.localizedDescription
            let result = TraceResult(anchor: anchor, later: nil, relation: nil, note: JevTraceCore.noConnection + "\n" + safe)
            return finished(result, calls: [first], count: attempted, start: start)
        }
    }

    private func finished(_ value: TraceResult, calls: [JevAnswers], count: Int, start: Date) -> TraceResult {
        var result = value
        result.calls = calls
        result.callCount = count
        result.latencyMilliseconds = Int(Date().timeIntervalSince(start) * 1000)
        return result
    }

    func evaluate(_ call: JevCall, key: String) async throws -> JevAnswers {
        guard let key = Self.validKey(key) else { throw JevTraceError.missingKey }
        var request = URLRequest(url: URL(string: "https://api.typesafe.ai/v1/systemone")!, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 10)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer " + key, forHTTPHeaderField: "Authorization")
        request.httpBody = try JSONSerialization.data(withJSONObject: call.body)
        let data = try await load(request)
        return try JevTraceCore.validate(data, for: call)
    }

    private func retrieve(base: URL, scope: String, limit: Int) async throws -> [TraceCapture] {
        let data = try await load(JevTraceCore.recallRequest(base: base, scope: scope, limit: limit))
        do { return try JevTraceCore.decodeCaptures(data, limit: limit) }
        catch { throw JevTraceError.retrieval }
    }

    private func load(_ request: URLRequest) async throws -> Data {
        try Task.checkCancellation()
        do {
            let (data, response) = try await session.data(for: request)
            try Task.checkCancellation()
            guard let response = response as? HTTPURLResponse else { throw JevTraceError.invalidResponse }
            guard (200...299).contains(response.statusCode) else { throw JevTraceError.http(response.statusCode) }
            guard data.count <= 2_000_000 else { throw JevTraceError.invalidResponse }
            return data
        } catch let error as URLError {
            if error.code == .cancelled || Task.isCancelled { throw CancellationError() }
            if error.code == .timedOut { throw JevTraceError.timeout }
            throw JevTraceError.retrieval
        }
    }
}
