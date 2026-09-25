import AppKit

/// `drawsBackground` is a private WKWebView key (there is no public API for a transparent web view
/// pre-macOS 26). Guard it behind `responds(to:)` so an OS where the key has moved or gone away
/// degrades to an opaque view instead of throwing NSUnknownKeyException.
enum WebViewTransparency {
    static let setDrawsBackgroundSelector = Selector(("setDrawsBackground:"))

    static func apply(to target: NSObject) {
        guard target.responds(to: setDrawsBackgroundSelector) else { return }
        target.setValue(false, forKey: "drawsBackground")
    }
}
