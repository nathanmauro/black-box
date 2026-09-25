import Foundation

/// Pure link-handling rules shared by the new-tab handler and the navigation delegate: never hand a
/// non-http(s) URL to NSWorkspace, and never let a same-window navigation replace the companion page.
enum LinkPolicy {
    /// Only http/https should ever be handed to NSWorkspace; anything else (mailto:, file:, custom
    /// schemes, no scheme at all) is ignored.
    static func isAllowedScheme(_ url: URL?) -> Bool {
        guard let scheme = url?.scheme?.lowercased() else { return false }
        return scheme == "http" || scheme == "https"
    }

    /// True when `url` would take the panel away from `origin` (a different scheme, host, or port).
    /// A same-window navigation that stays on the companion origin is left alone; one that would
    /// leave it is treated as external.
    static func isExternalNavigation(to url: URL?, from origin: URL) -> Bool {
        guard let url else { return false }
        guard let urlScheme = url.scheme?.lowercased(), let originScheme = origin.scheme?.lowercased() else { return false }
        guard let urlHost = url.host?.lowercased(), let originHost = origin.host?.lowercased() else { return false }
        if urlScheme != originScheme || urlHost != originHost { return true }
        return normalizedPort(url) != normalizedPort(origin)
    }

    private static func normalizedPort(_ url: URL) -> Int {
        url.port ?? (url.scheme?.lowercased() == "https" ? 443 : 80)
    }
}
