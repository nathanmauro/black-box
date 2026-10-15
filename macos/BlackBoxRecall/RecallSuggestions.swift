import Foundation

struct RecallSuggestion: Decodable {
    let eventId: String
    let sessionId: String
    let kind: String
    let repo: String?
    let headline: String?
    let nextAction: String?
    let rationale: String?

    var title: String {
        let text = [headline, nextAction, rationale].compactMap { $0 }
            .first { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty } ?? "Untitled capture"
        let line = text.components(separatedBy: .newlines)
            .first { !$0.trimmingCharacters(in: .whitespaces).isEmpty } ?? text
        let trimmed = line.trimmingCharacters(in: .whitespaces)
        let characters = Array(trimmed)
        var firstSentence = trimmed
        for index in characters.indices {
            if ".!?。！？".contains(characters[index]),
               index == characters.count - 1 || characters[index + 1].isWhitespace {
                firstSentence = String(characters[...index])
                break
            }
        }
        return Self.shorten(firstSentence, to: 110)
    }

    var detail: String {
        let path = (repo ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let project = path.split(separator: "/").last.map(String.init) ?? "Unknown project"
        return "\(Self.shorten(project, to: 40)) · \(kind.capitalized)"
    }

    func eventURL(base: URL) -> URL {
        var components = URLComponents(url: base, resolvingAgainstBaseURL: false)!
        components.path = "/"
        components.queryItems = [
            URLQueryItem(name: "view", value: "browse"),
            URLQueryItem(name: "session", value: sessionId),
            URLQueryItem(name: "event", value: eventId),
            // Recall exposes repo text, not a trustworthy project key. Clear remembered scope
            // exactly as the web Recall page does so it cannot hide the owning session.
            URLQueryItem(name: "project", value: "")
        ]
        components.percentEncodedQuery = components.percentEncodedQuery?.replacingOccurrences(of: "+", with: "%2B")
        return components.url!
    }

    private static func shorten(_ text: String, to limit: Int) -> String {
        text.count > limit ? String(text.prefix(limit - 1)) + "…" : text
    }
}

enum RecallSuggestions {
    static let minimumQueryLength = 2
    static let maximumItems = 5
    static let debounceSeconds = 0.35

    private struct Response: Decodable { let items: [RecallSuggestion] }

    static func request(base: URL, query: String) -> URLRequest {
        var components = URLComponents(url: base, resolvingAgainstBaseURL: false)!
        components.path = "/api/recall"
        components.queryItems = [
            URLQueryItem(name: "scope", value: query.trimmingCharacters(in: .whitespacesAndNewlines)),
            URLQueryItem(name: "withinHours", value: "168"),
            URLQueryItem(name: "kinds", value: "decision,handoff"),
            URLQueryItem(name: "limit", value: String(maximumItems))
        ]
        components.percentEncodedQuery = components.percentEncodedQuery?.replacingOccurrences(of: "+", with: "%2B")
        var request = URLRequest(url: components.url!, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 8)
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        return request
    }

    static func decode(_ data: Data) throws -> [RecallSuggestion] {
        let response = try JSONDecoder().decode(Response.self, from: data)
        return Array(response.items.filter {
            ["decision", "handoff"].contains($0.kind.lowercased())
                && !$0.eventId.isEmpty && !$0.sessionId.isEmpty
        }.prefix(maximumItems))
    }
}
