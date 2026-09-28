import WebKit

/// `NSPanel.isMovableByWindowBackground` only works through views whose `mouseDownCanMoveWindow` is
/// true. A plain `WKWebView` overrides it to `false` (WebKit handles its own mouse-down), so with
/// the web view filling the panel's whole content area, dragging the chip never moved the window —
/// mouse-up just fired the click and expanded it, and the panel stayed pinned at its initial frame.
final class DraggableWebView: WKWebView {
    override var mouseDownCanMoveWindow: Bool { true }
}
