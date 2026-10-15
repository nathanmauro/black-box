import Foundation

enum RecallURL {
    static let defaultBase = "http://localhost:8766/"

    enum ValidationError: LocalizedError {
        case invalidBase
        var errorDescription: String? {
            "BLACKBOX_URL must be an HTTP or HTTPS URL with a host, without credentials, query, or fragment."
        }
    }

    static func base(_ value: String) throws -> URL {
        guard let components = URLComponents(string: value.trimmingCharacters(in: .whitespacesAndNewlines)),
              let scheme = components.scheme?.lowercased(), ["http", "https"].contains(scheme),
              let host = components.host, !host.isEmpty,
              components.user == nil, components.password == nil,
              components.query == nil, components.fragment == nil,
              let url = components.url else { throw ValidationError.invalidBase }
        return url
    }

    static func search(base: URL, query: String) -> URL {
        var components = URLComponents(url: base, resolvingAgainstBaseURL: false)!
        components.path = "/recall"
        components.queryItems = [
            URLQueryItem(name: "scope", value: query.trimmingCharacters(in: .whitespacesAndNewlines)),
            URLQueryItem(name: "run", value: "1")
        ]
        // URLComponents permits a literal +, but form-style query readers treat it as a space.
        components.percentEncodedQuery = components.percentEncodedQuery?.replacingOccurrences(of: "+", with: "%2B")
        return components.url!
    }

    static func sameOrigin(_ first: URL, _ second: URL) -> Bool {
        func port(_ url: URL) -> Int { url.port ?? (url.scheme?.lowercased() == "https" ? 443 : 80) }
        return first.scheme?.lowercased() == second.scheme?.lowercased()
            && first.host?.lowercased() == second.host?.lowercased() && port(first) == port(second)
    }
}
