import Foundation
import Security
import LocalAuthentication

/// Only this helper's generic-password record; no key is written to a plist or log.
enum JevKeychain {
    static let service = "dev.blackbox.quick-recall.typesafe"
    static let account = "typesafe"
    private static var identity: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
         kSecAttrAccount as String: account, kSecAttrSynchronizable as String: false]
    }

    private static func noInteraction() -> LAContext {
        let context = LAContext()
        context.interactionNotAllowed = true
        return context
    }

    static func read() -> String? {
        // This record uses the file-based macOS keychain, whose prompt suppression does
        // not reliably honor LAContext alone. Restore the process setting after this
        // synchronous read; all helper Keychain access remains serialized.
        var interactionAllowed: DarwinBoolean = false
        guard SecKeychainGetUserInteractionAllowed(&interactionAllowed) == errSecSuccess,
              SecKeychainSetUserInteractionAllowed(false) == errSecSuccess else { return nil }
        defer { SecKeychainSetUserInteractionAllowed(interactionAllowed.boolValue) }
        return readValue(interactive: false)
    }

    private static func readValue(interactive: Bool) -> String? {
        var query = identity
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        if !interactive { query[kSecUseAuthenticationContext as String] = noInteraction() }
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data, let value = String(data: data, encoding: .utf8) else { return nil }
        return JevTraceClient.validKey(value)
    }

    static func configureFromEnvironment() throws {
        guard let key = JevTraceClient.validKey(ProcessInfo.processInfo.environment["TYPESAFE_API_KEY"]) else { throw JevTraceError.missingKey }
        let value: [String: Any] = [kSecValueData as String: Data(key.utf8)]
        // Only this explicit setup command may ask macOS for access. A refusal never
        // deletes or replaces the existing credential or its access controls.
        let updated = SecItemUpdate(identity as CFDictionary, value as CFDictionary)
        if updated == errSecSuccess {
            guard readValue(interactive: true) != nil else { throw JevTraceError.unavailableKeychain }
            return
        }
        guard updated == errSecItemNotFound else { throw JevTraceError.unavailableKeychain }
        var item = identity
        item[kSecValueData as String] = Data(key.utf8)
        item[kSecAttrLabel as String] = "Black Box Recall · TypeSafe"
        item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        guard SecItemAdd(item as CFDictionary, nil) == errSecSuccess, readValue(interactive: true) != nil else { throw JevTraceError.unavailableKeychain }
    }
}
