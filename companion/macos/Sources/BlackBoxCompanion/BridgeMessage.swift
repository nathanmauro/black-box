import Foundation

enum BridgeMessage: Equatable {
    case state(pulse: Pulse, unseen: Int)
    case mode(name: String, width: Double, height: Double)

    static func parse(_ body: Any) -> BridgeMessage? {
        guard let dict = body as? [String: Any], let type = dict["type"] as? String else { return nil }
        switch type {
        case "state":
            guard let pulseName = dict["pulse"] as? String, let pulse = Pulse(rawValue: pulseName) else { return nil }
            let unseen = (dict["unseen"] as? NSNumber)?.intValue ?? 0
            return .state(pulse: pulse, unseen: unseen)
        case "mode":
            guard let name = dict["mode"] as? String,
                  let width = (dict["width"] as? NSNumber)?.doubleValue,
                  let height = (dict["height"] as? NSNumber)?.doubleValue else { return nil }
            return .mode(name: name, width: width, height: height)
        default:
            return nil
        }
    }
}
