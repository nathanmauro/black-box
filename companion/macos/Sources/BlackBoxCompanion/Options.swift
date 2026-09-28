import Foundation

struct Options {
    static let defaultURL = URL(string: "http://127.0.0.1:8766/companion?embedded=1")!

    var url: URL
    var selfTestOutput: String?
    var clickThroughOutput: String?

    enum ParseError: Error, CustomStringConvertible {
        /// A flag was given with no following argument, or the next argument looks like another
        /// flag (starts with "--") rather than this flag's value.
        case missingValue(flag: String)
        case invalidValue(flag: String, value: String)

        var description: String {
            switch self {
            case let .missingValue(flag):
                return "\(flag) requires a value"
            case let .invalidValue(flag, value):
                return "invalid \(flag): \(value)"
            }
        }
    }

    static func parse(_ args: [String], env: [String: String]) throws -> Options {
        var options = Options(url: defaultURL, selfTestOutput: nil)
        if let fromEnv = env["BLACKBOX_COMPANION_URL"] {
            guard let url = validURL(fromEnv) else { throw ParseError.invalidValue(flag: "BLACKBOX_COMPANION_URL", value: fromEnv) }
            options.url = url
        }
        var index = 0
        while index < args.count {
            let arg = args[index]
            switch arg {
            case "--url":
                let value = try requireValue(args, at: index, flag: "--url")
                guard let url = validURL(value) else { throw ParseError.invalidValue(flag: "--url", value: value) }
                options.url = url
                index += 2
            case "--self-test":
                options.selfTestOutput = try requireValue(args, at: index, flag: "--self-test")
                index += 2
            case "--click-through":
                options.clickThroughOutput = try requireValue(args, at: index, flag: "--click-through")
                index += 2
            default:
                index += 1
            }
        }
        return options
    }

    // A missing value silently ran the full GUI app instead of a requested self-test (a CI/e2e
    // caller waiting for exit would hang); a flag-shaped value (the next flag, swallowed as if it
    // were this flag's value) silently ran against whatever was left in place. Both fail loudly.
    private static func requireValue(_ args: [String], at index: Int, flag: String) throws -> String {
        guard index + 1 < args.count else { throw ParseError.missingValue(flag: flag) }
        let value = args[index + 1]
        guard !value.hasPrefix("--") else { throw ParseError.missingValue(flag: flag) }
        return value
    }

    private static func validURL(_ text: String) -> URL? {
        guard let url = URL(string: text), let scheme = url.scheme, scheme == "http" || scheme == "https", url.host != nil else { return nil }
        return url
    }
}
