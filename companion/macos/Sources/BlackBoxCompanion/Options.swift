import Foundation

struct Options {
    static let defaultURL = URL(string: "http://127.0.0.1:8766/companion?embedded=1")!

    var url: URL
    var selfTestOutput: String?
    var clickThroughOutput: String?

    static func parse(_ args: [String], env: [String: String]) -> Options {
        var options = Options(url: defaultURL, selfTestOutput: nil)
        if let fromEnv = env["BLACKBOX_COMPANION_URL"], let url = validURL(fromEnv) {
            options.url = url
        }
        var index = 0
        while index < args.count {
            let arg = args[index]
            let value: String? = index + 1 < args.count ? args[index + 1] : nil
            switch arg {
            case "--url":
                if let value, let url = validURL(value) { options.url = url }
                index += 2
            case "--self-test":
                options.selfTestOutput = value
                index += 2
            case "--click-through":
                options.clickThroughOutput = value
                index += 2
            default:
                index += 1
            }
        }
        return options
    }

    private static func validURL(_ text: String) -> URL? {
        guard let url = URL(string: text), let scheme = url.scheme, scheme == "http" || scheme == "https", url.host != nil else { return nil }
        return url
    }
}
