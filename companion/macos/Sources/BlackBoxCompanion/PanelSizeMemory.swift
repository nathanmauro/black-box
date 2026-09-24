import CoreGraphics
import Foundation

/// Reads and writes the remembered per-mode panel size. A protocol so the pure decision logic in
/// `PanelSizeMemory` can be unit tested against a fake instead of real `UserDefaults`.
protocol PanelSizeStore {
    func size(forMode mode: String) -> CGSize?
    func setSize(_ size: CGSize, forMode mode: String)
}

final class UserDefaultsPanelSizeStore: PanelSizeStore {
    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func size(forMode mode: String) -> CGSize? {
        let width = defaults.object(forKey: widthKey(mode)) as? Double
        let height = defaults.object(forKey: heightKey(mode)) as? Double
        guard let width, let height, width.isFinite, height.isFinite, width > 0, height > 0 else { return nil }
        return CGSize(width: width, height: height)
    }

    func setSize(_ size: CGSize, forMode mode: String) {
        defaults.set(Double(size.width), forKey: widthKey(mode))
        defaults.set(Double(size.height), forKey: heightKey(mode))
    }

    private func widthKey(_ mode: String) -> String { "BlackBoxCompanion.manualSize.\(mode).width" }
    private func heightKey(_ mode: String) -> String { "BlackBoxCompanion.manualSize.\(mode).height" }
}

/// The expanded panel is user-resizable (spec 3.3). A resize by hand should survive the next level
/// change instead of being clobbered by the page's own default size for that mode.
enum PanelSizeMemory {
    /// The size to apply when entering `mode`: the user's remembered manual size for that mode when
    /// there is one and it is still valid, otherwise the page's own default for that mode.
    static func resolvedSize(mode: String, pageDefault: CGSize, store: PanelSizeStore) -> CGSize {
        store.size(forMode: mode) ?? pageDefault
    }

    /// Record a resize that just happened, but only when it was not caused by the shell itself
    /// applying a bridge mode message — a programmatic resize must never overwrite what the user
    /// chose by hand.
    static func recordResize(_ size: CGSize, forMode mode: String, programmatic: Bool, store: PanelSizeStore) {
        guard !programmatic else { return }
        store.setSize(size, forMode: mode)
    }
}
