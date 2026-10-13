import AppKit
import Carbon.HIToolbox

/// A global keyboard shortcut. `display` is captured when recording, e.g. "⌃⌥⌘N".
struct Shortcut: Codable, Equatable {
    var keyCode: UInt32
    var modifiers: UInt // NSEvent.ModifierFlags raw value
    var display: String

    var carbonModifiers: UInt32 {
        let f = NSEvent.ModifierFlags(rawValue: modifiers)
        var m: UInt32 = 0
        if f.contains(.command) { m |= UInt32(cmdKey) }
        if f.contains(.option) { m |= UInt32(optionKey) }
        if f.contains(.control) { m |= UInt32(controlKey) }
        if f.contains(.shift) { m |= UInt32(shiftKey) }
        return m
    }

    init?(event e: NSEvent) {
        let flags = e.modifierFlags.intersection([.command, .option, .control, .shift])
        let fKey = Self.fKeys[Int(e.keyCode)] != nil // F-keys work without modifiers
        guard !flags.subtracting(.shift).isEmpty || fKey else { return nil }
        keyCode = UInt32(e.keyCode)
        modifiers = flags.rawValue
        var s = ""
        if flags.contains(.control) { s += "⌃" }
        if flags.contains(.option) { s += "⌥" }
        if flags.contains(.shift) { s += "⇧" }
        if flags.contains(.command) { s += "⌘" }
        display = s + Self.keyName(e)
    }

    private static let fKeys: [Int: String] = [
        kVK_F1: "F1", kVK_F2: "F2", kVK_F3: "F3", kVK_F4: "F4", kVK_F5: "F5", kVK_F6: "F6", kVK_F7: "F7", kVK_F8: "F8",
        kVK_F9: "F9", kVK_F10: "F10", kVK_F11: "F11", kVK_F12: "F12", kVK_F13: "F13", kVK_F14: "F14", kVK_F15: "F15",
        kVK_F16: "F16", kVK_F17: "F17", kVK_F18: "F18", kVK_F19: "F19", kVK_F20: "F20",
    ]

    private static func keyName(_ e: NSEvent) -> String {
        switch Int(e.keyCode) {
        case kVK_Space: return "Space"
        case kVK_Return: return "↩"
        case kVK_Tab: return "⇥"
        case kVK_Delete: return "⌫"
        case kVK_Escape: return "⎋"
        case kVK_LeftArrow: return "←"
        case kVK_RightArrow: return "→"
        case kVK_UpArrow: return "↑"
        case kVK_DownArrow: return "↓"
        case let k where fKeys[k] != nil: return fKeys[k]!
        default:
            return (e.charactersIgnoringModifiers ?? "?").uppercased()
        }
    }
}

/// User preferences, persisted in UserDefaults. Values read off the main thread go through `Prefs.Key` directly.
@MainActor
final class Prefs: ObservableObject {
    static let shared = Prefs()

    enum Key {
        static let notchEnabled = "notchEnabled"
        static let zoneWidth = "zoneWidth"
        static let zoneHeight = "zoneHeight"
        static let openOnHover = "openOnHover"
        static let showMenuBarIcon = "showMenuBarIcon"
        static let sounds = "sounds"
        static let haptics = "haptics"
        static let confirmReceive = "confirmReceive"
        static let saveFolder = "saveFolder"
        static let copyText = "copyReceivedText"
        static let openLinks = "openReceivedLinks"
        static let shortcut = "sendShortcut"
        static let askedAccessibility = "askedAccessibility"
    }

    static let defaultZone = CGSize(width: 560, height: 210)
    static let zoneWidthRange: ClosedRange<Double> = 240...1000
    static let zoneHeightRange: ClosedRange<Double> = 40...400

    private let d = UserDefaults.standard

    @Published var notchEnabled: Bool { didSet { d.set(notchEnabled, forKey: Key.notchEnabled) } }
    @Published var zoneWidth: Double { didSet { d.set(zoneWidth, forKey: Key.zoneWidth) } }
    @Published var zoneHeight: Double { didSet { d.set(zoneHeight, forKey: Key.zoneHeight) } }
    @Published var openOnHover: Bool { didSet { d.set(openOnHover, forKey: Key.openOnHover) } }
    @Published var showMenuBarIcon: Bool { didSet { d.set(showMenuBarIcon, forKey: Key.showMenuBarIcon) } }
    @Published var sounds: Bool { didSet { d.set(sounds, forKey: Key.sounds) } }
    @Published var haptics: Bool { didSet { d.set(haptics, forKey: Key.haptics) } }
    @Published var confirmReceive: Bool { didSet { d.set(confirmReceive, forKey: Key.confirmReceive) } }
    @Published var copyText: Bool { didSet { d.set(copyText, forKey: Key.copyText) } }
    @Published var openLinks: Bool { didSet { d.set(openLinks, forKey: Key.openLinks) } }
    /// nil = default per-phone folder in Downloads.
    @Published var saveFolder: URL? { didSet { d.set(saveFolder?.path, forKey: Key.saveFolder) } }
    /// Not persisted: true while the Notch settings pane is on screen, to draw the activation zone.
    @Published var previewZone = false
    @Published var shortcut: Shortcut? {
        didSet { d.set(shortcut.flatMap { try? JSONEncoder().encode($0) }, forKey: Key.shortcut) }
    }

    private init() {
        d.register(defaults: [
            Key.notchEnabled: true, Key.zoneWidth: Double(Self.defaultZone.width), Key.zoneHeight: Double(Self.defaultZone.height),
            Key.openOnHover: false, Key.showMenuBarIcon: true, Key.sounds: true, Key.haptics: true,
            Key.confirmReceive: false, Key.copyText: true, Key.openLinks: false,
        ])
        notchEnabled = d.bool(forKey: Key.notchEnabled)
        zoneWidth = d.double(forKey: Key.zoneWidth)
        zoneHeight = d.double(forKey: Key.zoneHeight)
        openOnHover = d.bool(forKey: Key.openOnHover)
        showMenuBarIcon = d.bool(forKey: Key.showMenuBarIcon)
        sounds = d.bool(forKey: Key.sounds)
        haptics = d.bool(forKey: Key.haptics)
        confirmReceive = d.bool(forKey: Key.confirmReceive)
        copyText = d.bool(forKey: Key.copyText)
        openLinks = d.bool(forKey: Key.openLinks)
        saveFolder = d.string(forKey: Key.saveFolder).map { URL(fileURLWithPath: $0, isDirectory: true) }
        shortcut = d.data(forKey: Key.shortcut).flatMap { try? JSONDecoder().decode(Shortcut.self, from: $0) }
    }

    func resetZone() {
        zoneWidth = Double(Self.defaultZone.width)
        zoneHeight = Double(Self.defaultZone.height)
    }

    // MARK: Feedback

    func play(_ sound: String) {
        guard sounds else { return }
        NSSound(named: NSSound.Name(sound))?.play()
    }

    func haptic(_ pattern: NSHapticFeedbackManager.FeedbackPattern = .alignment) {
        guard haptics else { return }
        NSHapticFeedbackManager.defaultPerformer.perform(pattern, performanceTime: .now)
    }
}
