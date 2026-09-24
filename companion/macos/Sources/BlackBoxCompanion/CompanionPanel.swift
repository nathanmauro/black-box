import AppKit

final class CompanionPanel: NSPanel {
    init(contentSize: NSSize) {
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
        _ = setFrameAutosaveName("BlackBoxCompanionPanel")
        if frame.origin == .zero, let screen = NSScreen.main?.visibleFrame {
            setFrameOrigin(NSPoint(x: screen.maxX - contentSize.width - 16, y: screen.maxY - contentSize.height - 16))
        }
    }

    override var canBecomeKey: Bool { true }
}
