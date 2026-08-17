import Foundation
import Observation
import RadarDealCore

/// Where a listing opens when the user taps "Voir sur Vinted".
enum OpenTarget: String, CaseIterable {
    case inApp = "IN_APP"
    case external = "EXTERNAL"

    var label: String {
        switch self {
        case .inApp: return "RadarDeal"
        case .external: return "Vinted / navigateur"
        }
    }
}

/// Every user preference, stored locally in `UserDefaults`.
///
/// Nothing here ever leaves the device — there is no RadarDeal server to send it to.
@MainActor
@Observable
final class SettingsStore {

    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    private enum Key {
        static let onboardingDone = "onboarding_done"
        static let notifications = "notifications_enabled"
        static let sound = "sound_enabled"
        static let defaultInterval = "default_interval_seconds"
        static let openTarget = "open_target"
        static let vintedHost = "vinted_host"
        static let monitoringRequested = "monitoring_requested"
    }

    var onboardingDone: Bool {
        get { defaults.bool(forKey: Key.onboardingDone) }
        set { defaults.set(newValue, forKey: Key.onboardingDone) }
    }

    var notificationsEnabled: Bool {
        get { defaults.object(forKey: Key.notifications) as? Bool ?? true }
        set { defaults.set(newValue, forKey: Key.notifications) }
    }

    var soundEnabled: Bool {
        get { defaults.object(forKey: Key.sound) as? Bool ?? true }
        set { defaults.set(newValue, forKey: Key.sound) }
    }

    var defaultIntervalSeconds: Int {
        get { defaults.object(forKey: Key.defaultInterval) as? Int ?? ScanFrequency.standard.seconds }
        set { defaults.set(newValue, forKey: Key.defaultInterval) }
    }

    var openTarget: OpenTarget {
        get { OpenTarget(rawValue: defaults.string(forKey: Key.openTarget) ?? "") ?? .inApp }
        set { defaults.set(newValue.rawValue, forKey: Key.openTarget) }
    }

    /// Vinted marketplace the user shops on, e.g. "www.vinted.fr".
    var vintedHost: String {
        get { defaults.string(forKey: Key.vintedHost) ?? "www.vinted.fr" }
        set { defaults.set(newValue, forKey: Key.vintedHost) }
    }

    var monitoringRequested: Bool {
        get { defaults.bool(forKey: Key.monitoringRequested) }
        set { defaults.set(newValue, forKey: Key.monitoringRequested) }
    }
}
