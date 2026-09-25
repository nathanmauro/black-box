import CoreGraphics

enum PanelGeometry {
    static let minimumSize = CGSize(width: 100, height: 28)

    /// Resizes `current` to `size`, keeping its top-right corner fixed, clamped to `screen`.
    static func frame(resizing current: CGRect, to size: CGSize, within screen: CGRect) -> CGRect {
        let width = min(max(size.width, minimumSize.width), screen.width)
        let height = min(max(size.height, minimumSize.height), screen.height)
        var origin = CGPoint(x: current.maxX - width, y: current.maxY - height)
        origin.x = min(max(origin.x, screen.minX), screen.maxX - width)
        origin.y = min(max(origin.y, screen.minY), screen.maxY - height)
        return CGRect(origin: origin, size: CGSize(width: width, height: height))
    }
}
