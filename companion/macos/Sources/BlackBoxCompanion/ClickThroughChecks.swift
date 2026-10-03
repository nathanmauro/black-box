import CoreGraphics
import Foundation

/// Pure assertions used by the `--click-through` run, kept apart from the AppKit orchestration so
/// they can be unit tested.
enum ClickThroughChecks {
    struct ShellState: Equatable {
        var pulse: Pulse
        var unseen: Int
    }

    /// Nil when `url` is a browse-view deep link on the companion's own origin (path "/", with
    /// view=browse and non-empty session and event); otherwise a short description of the problem.
    static func deepLinkProblem(_ url: URL?, companion: URL) -> String? {
        guard let url else { return "no URL reached the shell's link handler" }
        if LinkPolicy.isExternalNavigation(to: url, from: companion) { return "\(url.absoluteString) is not on the Black Box origin" }
        guard let components = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return "\(url.absoluteString) does not parse" }
        if components.path != "/" { return "path is \"\(components.path)\", expected \"/\"" }
        let items = components.queryItems ?? []
        func value(_ name: String) -> String? { items.first(where: { $0.name == name })?.value }
        if value("view") != "browse" { return "view is \(value("view") ?? "missing"), expected browse" }
        for name in ["session", "event"] where (value(name) ?? "").isEmpty {
            return "\(name) is missing or empty"
        }
        return nil
    }

    /// The Recall launcher URL the companion's recall form should hand the shell: `/recall` on the
    /// companion's origin with `project` (omitted when nil), the trimmed `query`, and `run=1`,
    /// serialized as application/x-www-form-urlencoded exactly as the page's URLSearchParams does.
    static func expectedRecallURL(companion: URL, project: String?, query: String) -> URL? {
        guard var components = URLComponents(url: companion, resolvingAgainstBaseURL: false) else { return nil }
        var pairs: [(String, String)] = []
        if let project, !project.isEmpty { pairs.append(("project", project)) }
        pairs.append(("query", query.trimmingCharacters(in: .whitespaces)))
        pairs.append(("run", "1"))
        components.percentEncodedPath = "/recall"
        components.percentEncodedQuery = pairs.map { "\(formEncoded($0.0))=\(formEncoded($0.1))" }.joined(separator: "&")
        components.fragment = nil
        return components.url
    }

    /// Nil when `url` is exactly `expected`; otherwise a short description of the mismatch.
    static func recallLinkProblem(_ url: URL?, expected: URL?) -> String? {
        guard let url else { return "no URL reached the shell's link handler" }
        guard let expected else { return "could not build the expected recall URL" }
        return url.absoluteString == expected.absoluteString ? nil : "got \(url.absoluteString), expected \(expected.absoluteString)"
    }

    /// WHATWG application/x-www-form-urlencoded byte serializer: ASCII alphanumerics and `*-._`
    /// pass through, space becomes `+`, every other UTF-8 byte is percent-encoded in uppercase hex.
    static func formEncoded(_ text: String) -> String {
        var out = ""
        for byte in text.utf8 {
            switch byte {
            case UInt8(ascii: "a")...UInt8(ascii: "z"), UInt8(ascii: "A")...UInt8(ascii: "Z"), UInt8(ascii: "0")...UInt8(ascii: "9"),
                 UInt8(ascii: "*"), UInt8(ascii: "-"), UInt8(ascii: "."), UInt8(ascii: "_"):
                out.append(Character(UnicodeScalar(byte)))
            case UInt8(ascii: " "):
                out.append("+")
            default:
                out.append(String(format: "%%%02X", byte))
            }
        }
        return out
    }

    /// Frames are integral in practice; allow a point of rounding either way.
    static func sizeMatches(_ actual: CGSize, _ expected: CGSize, tolerance: CGFloat = 1) -> Bool {
        abs(actual.width - expected.width) <= tolerance && abs(actual.height - expected.height) <= tolerance
    }

    /// The page's pulse and unseen count as the mini chip renders them: pulse from its
    /// `companion-chip--<pulse>` class, unseen from the count badge (absent means zero).
    static func chipState(className: String, countText: String?) -> ShellState? {
        let prefix = "companion-chip--"
        guard let modifier = className.split(separator: " ").first(where: { $0.hasPrefix(prefix) }),
              let pulse = Pulse(rawValue: String(modifier.dropFirst(prefix.count))) else { return nil }
        let unseen = countText.flatMap { Int($0.trimmingCharacters(in: .whitespacesAndNewlines)) } ?? 0
        return ShellState(pulse: pulse, unseen: unseen)
    }

    static func expectedStatusTitle(_ state: ShellState) -> String {
        StatusTitle.render(pulse: state.pulse, unseen: state.unseen)
    }
}
