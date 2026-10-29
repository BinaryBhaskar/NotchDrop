import AppKit
import ApplicationServices
import Carbon.HIToolbox

/// Registers system-wide hotkeys through Carbon (works without Accessibility permission).
@MainActor
final class Hotkey {
    enum Action: UInt32 { case sendSelection = 1, openNotch = 2 }

    static let shared = Hotkey()
    var onPress: ((Action) -> Void)?

    private var refs: [Action: EventHotKeyRef] = [:]
    private var handler: EventHandlerRef?

    func register(_ s: Shortcut?, for action: Action) {
        if let ref = refs.removeValue(forKey: action) { UnregisterEventHotKey(ref) }
        guard let s else { return }
        installHandler()
        var ref: EventHotKeyRef?
        let id = EventHotKeyID(signature: OSType(0x4E_44_52_50), id: action.rawValue) // "NDRP"
        let status = RegisterEventHotKey(s.keyCode, s.carbonModifiers, id, GetApplicationEventTarget(), 0, &ref)
        if status != noErr { NSLog("NotchDrop: hotkey \(s.display) unavailable (\(status))") }
        if let ref { refs[action] = ref }
    }

    /// While a shortcut is being recorded, none fire, so pressing a current combo records it.
    func suspend() {
        for ref in refs.values { UnregisterEventHotKey(ref) }
        refs = [:]
    }

    func registerAll() {
        register(Prefs.shared.shortcut, for: .sendSelection)
        register(Prefs.shared.openShortcut, for: .openNotch)
    }

    private func installHandler() {
        guard handler == nil else { return }
        var spec = EventTypeSpec(eventClass: OSType(kEventClassKeyboard), eventKind: UInt32(kEventHotKeyPressed))
        InstallEventHandler(GetApplicationEventTarget(), { _, event, ctx in
            var id = EventHotKeyID()
            GetEventParameter(event, EventParamName(kEventParamDirectObject), EventParamType(typeEventHotKeyID),
                              nil, MemoryLayout<EventHotKeyID>.size, nil, &id)
            let me = Unmanaged<Hotkey>.fromOpaque(ctx!).takeUnretainedValue()
            guard let action = Action(rawValue: id.id) else { return noErr }
            DispatchQueue.main.async { MainActor.assumeIsolated { me.onPress?(action) } }
            return noErr
        }, 1, &spec, Unmanaged.passUnretained(self).toOpaque(), &handler)
    }
}

/// Figures out what the user means to send: the Finder selection, selected text in the front app, or the clipboard.
@MainActor
enum SelectionGrabber {
    enum Grab { case files([URL]), texts([String]), nothing }

    static func grab() async -> Grab {
        let front = NSWorkspace.shared.frontmostApplication
        if front?.bundleIdentifier == "com.apple.finder" {
            let files = finderSelection()
            if !files.isEmpty { return .files(files) }
        } else if front?.processIdentifier != ProcessInfo.processInfo.processIdentifier, accessibilityAllowed(prompt: true) {
            let g = await copySelection()
            if case .nothing = g {} else { return g }
        }
        return read(.general)
    }

    static var accessibilityTrusted: Bool { AXIsProcessTrusted() }

    /// Prompts once (the first time the hotkey is used); afterwards just reports the current state.
    static func accessibilityAllowed(prompt: Bool) -> Bool {
        if AXIsProcessTrusted() { return true }
        let d = UserDefaults.standard
        if prompt && !d.bool(forKey: Prefs.Key.askedAccessibility) {
            d.set(true, forKey: Prefs.Key.askedAccessibility)
            let opts = [kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: true] as CFDictionary
            return AXIsProcessTrustedWithOptions(opts)
        }
        return false
    }

    static func openAccessibilitySettings() {
        let opts = [kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: true] as CFDictionary
        if !AXIsProcessTrustedWithOptions(opts) {
            NSWorkspace.shared.open(URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility")!)
        }
    }

    /// Asks Finder (Automation permission) for its selected items.
    private static func finderSelection() -> [URL] {
        let src = """
        tell application "Finder"
            set out to ""
            repeat with f in (get selection)
                set out to out & POSIX path of (f as alias) & linefeed
            end repeat
            return out
        end tell
        """
        var err: NSDictionary?
        let result = NSAppleScript(source: src)?.executeAndReturnError(&err)
        if let err { NSLog("NotchDrop: Finder selection failed: \(err)") }
        return (result?.stringValue ?? "").split(separator: "\n").map { URL(fileURLWithPath: String($0)) }
    }

    /// Sends ⌘C to the front app, reads what it copied, then puts the old clipboard back.
    private static func copySelection() async -> Grab {
        let pb = NSPasteboard.general
        let saved = pb.pasteboardItems?.map { item -> NSPasteboardItem in
            let copy = NSPasteboardItem()
            for t in item.types { if let data = item.data(forType: t) { copy.setData(data, forType: t) } }
            return copy
        } ?? []
        let before = pb.changeCount

        let src = CGEventSource(stateID: .combinedSessionState)
        for down in [true, false] {
            let e = CGEvent(keyboardEventSource: src, virtualKey: CGKeyCode(kVK_ANSI_C), keyDown: down)
            e?.flags = .maskCommand
            e?.post(tap: .cghidEventTap)
        }
        for _ in 0..<12 where pb.changeCount == before {
            try? await Task.sleep(nanoseconds: 25_000_000)
        }
        guard pb.changeCount != before else { return .nothing }
        let grabbed = read(pb)
        pb.clearContents()
        if !saved.isEmpty { pb.writeObjects(saved) }
        return grabbed
    }

    static func read(_ pb: NSPasteboard) -> Grab {
        if let urls = pb.readObjects(forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true]) as? [URL], !urls.isEmpty {
            return .files(urls)
        }
        if let s = pb.string(forType: .string), !s.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return .texts([s])
        }
        return .nothing
    }
}
