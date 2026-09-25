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
