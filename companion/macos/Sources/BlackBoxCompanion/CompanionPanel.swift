import AppKit

final class CompanionPanel: NSPanel {
    static let defaultAutosaveName = "BlackBoxCompanionPanel"

    /// `autosaveName` nil keeps the panel's frame out of UserDefaults (the click-through run must not
    /// move the real shell's remembered position).
    init(contentSize: NSSize, autosaveName: String? = CompanionPanel.defaultAutosaveName) {
        super.init(
            contentRect: NSRect(origin: .zero, size: contentSize),
            styleMask: [.borderless, .nonactivatingPanel, .resizable],
            backing: .buffered,
            defer: false
        )
        level = .floating
        collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
        isMovableByWindowBackground = true
        hidesOnDeactivate = false
        isOpaque = false
        backgroundColor = .clear
        hasShadow = true
        if let autosaveName { _ = setFrameAutosaveName(autosaveName) }
        if frame.origin == .zero, let screen = NSScreen.main?.visibleFrame {
            setFrameOrigin(NSPoint(x: screen.maxX - contentSize.width - 16, y: screen.maxY - contentSize.height - 16))
        }
    }

    override var canBecomeKey: Bool { true }
}
