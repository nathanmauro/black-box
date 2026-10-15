import Foundation

struct ChromeLaunch {
    let appID: String
    let userDataDirectory: URL
    let profile: String

    init?(info: [String: Any], registration: [String: Any]) {
        guard let appID = info["CrAppModeShortcutID"] as? String,
              appID.count == 32, appID.allSatisfy({ ("a"..."p").contains(String($0)) }),
              let appData = info["CrAppModeUserDataDir"] as? String,
              appData.hasPrefix("/") else { return nil }
        let directory = URL(fileURLWithPath: appData)
        guard directory.lastPathComponent == "_crx_" + appID,
              directory.deletingLastPathComponent().lastPathComponent == "Web Applications"
        else { return nil }
        let installed = registration["installed_profiles"] as? [String] ?? []
        let active = registration["last_active_profiles"] as? [String] ?? []
        let candidates = active.count == 1 ? active : installed
        guard candidates.count == 1, let profile = candidates.first, installed.contains(profile),
              !profile.isEmpty, profile != ".", profile != "..", profile != "-",
              !profile.contains("/"), !profile.contains("\\") else { return nil }
        self.appID = appID
        self.profile = profile
        userDataDirectory = directory.deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    }

    func arguments(for url: URL) -> [String] {
        ["--user-data-dir=\(userDataDirectory.path)", "--profile-directory=\(profile)",
         "--app-id=\(appID)", "--app-launch-url-for-shortcuts-menu-item=\(url.absoluteString)"]
    }
}
