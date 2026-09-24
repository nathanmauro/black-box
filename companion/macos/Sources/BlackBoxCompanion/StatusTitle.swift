enum Pulse: String {
    case connecting, live, idle, disconnected
}

enum StatusTitle {
    static func render(pulse: Pulse, unseen: Int) -> String {
        let glyph: String
        switch pulse {
        case .live: glyph = "●"
        case .idle: glyph = "○"
        case .connecting, .disconnected: glyph = "◌"
        }
        return unseen > 0 ? "\(glyph) \(unseen)" : glyph
    }
}
