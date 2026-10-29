import AppKit
import Combine

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate, NSMenuDelegate {
    private(set) static weak var shared: AppDelegate?
    private let app = AppModel.shared
    private let prefs = Prefs.shared
    private var notch: NotchController!
    private var pairWindow: PairWindowController!
    private var settings: SettingsWindowController!
    private var historyWindow: HistoryWindowController!
    private var statusItem: NSStatusItem!
    private var bag = Set<AnyCancellable>()

    func applicationDidFinishLaunching(_ notification: Notification) {
        Self.shared = self
        // Checked first: the launch Apple Event is only available during this call.
        let asLoginItem = launchedAsLoginItem()
        // Launched by the Share menu (notchdrop://share) or a Services item: just send, no window.
        let quietLaunch = asLoginItem || launchedForURL()
        app.start()
        notch = NotchController(app: app)
        pairWindow = PairWindowController(app: app)
        settings = SettingsWindowController(app: app)
        settings.onPair = { [weak self] in self?.pairWindow.show() }
        historyWindow = HistoryWindowController(app: app)
        Notifier.shared.start()
        UniversalControl.shared.start()

        Hotkey.shared.onPress = { [weak self] action in
            switch action {
            case .sendSelection: self?.app.sendSelection()
            case .openNotch: self?.notch.toggleOnPointerScreen()
            }
        }
        prefs.$shortcut.sink { Hotkey.shared.register($0, for: .sendSelection) }.store(in: &bag)
        prefs.$openShortcut.sink { Hotkey.shared.register($0, for: .openNotch) }.store(in: &bag)

        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)
        let menu = NSMenu()
        menu.delegate = self
        statusItem.menu = menu
        app.$reachable.combineLatest(app.$peer).sink { [weak self] reachable, peer in
            self?.updateIcon(paired: peer != nil, reachable: reachable)
        }.store(in: &bag)
        NSApp.servicesProvider = self
        NSUpdateDynamicServices()
        prefs.$showMenuBarIcon.sink { [weak self] in self?.statusItem.isVisible = $0 }.store(in: &bag)

        if app.peer == nil {
            pairWindow.show()
        } else if !quietLaunch {
            settings.show()
        }
        prefs.$autoCheckUpdates.dropFirst().filter { $0 }.sink { _ in
            DispatchQueue.main.async { Updater.shared.checkIfDue() }
        }.store(in: &bag)
        // After the first window, so the one-time opt-in question doesn't land under it.
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { Updater.shared.start() }
    }

    /// Hand the pointer back before quitting, so it's never left hidden and detached.
    func applicationWillTerminate(_ notification: Notification) {
        UniversalControl.shared.stop()
    }

    /// Opening the app again (Spotlight, Finder, Dock) while it's running brings up Settings.
    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        settings.show()
        return false
    }

    func application(_ application: NSApplication, open urls: [URL]) {
        for url in urls where url.scheme == "notchdrop" {
            switch url.host {
            case "share": app.sendShared()
            // For the Shortcuts app ("Open URL") or anything else that can open a link.
            case "open": notch?.toggleOnPointerScreen()
            default: break
            }
        }
    }

    /// Services menu: "Send to Phone with NotchDrop" on selected files or text in any app.
    @objc func sendToPhone(_ pboard: NSPasteboard, userData: String?, error: AutoreleasingUnsafeMutablePointer<NSString?>) {
        guard app.peer != nil else {
            error.pointee = "Pair a phone with NotchDrop first." as NSString
            pairWindow.show()
            return
        }
        app.send(pasteboard: pboard)
    }

    private func launchedForURL() -> Bool {
        NSAppleEventManager.shared().currentAppleEvent?.eventID == AEEventID(kAEGetURL)
    }

    private func launchedAsLoginItem() -> Bool {
        guard let event = NSAppleEventManager.shared().currentAppleEvent else { return false }
        return event.eventID == kAEOpenApplication
            && event.paramDescriptor(forKeyword: keyAEPropData)?.enumCodeValue == keyAELaunchedAsLogInItem
    }

    private func updateIcon(paired: Bool, reachable: Bool) {
        let name = reachable ? "arrow.up.arrow.down.circle.fill" : "arrow.up.arrow.down.circle"
        let img = NSImage(systemSymbolName: name, accessibilityDescription: "NotchDrop")
        img?.isTemplate = true
        statusItem.button?.image = img
        statusItem.button?.appearsDisabled = !paired
    }

    // MARK: Menu

    func menuNeedsUpdate(_ menu: NSMenu) {
        menu.removeAllItems()
        if let peer = app.peer {
            let status = app.reachable ? "Connected · \(app.route?.rawValue ?? "Wi-Fi")" : "Not reachable"
            menu.addItem(disabled("\(peer.displayName) — \(status)"))
            menu.addItem(.separator())
            menu.addItem(item("Send Files…", #selector(sendFiles), "o"))
            let clip = item("Send Clipboard", #selector(sendClipboard), "v")
            menu.addItem(clip)
            if let s = prefs.shortcut {
                menu.addItem(disabled("Send Selection  \(s.display)"))
            }
            menu.addItem(item("Open Received Folder", #selector(openFolder), ""))
            menu.addItem(item("History…", #selector(openHistory), "y"))
            menu.addItem(.separator())
            let cam = item("Phone Camera", #selector(phoneCamera), "")
            cam.state = app.liveStreams.contains(.camera) ? .on : .off
            menu.addItem(cam)
            let scr = item("Phone Screen", #selector(phoneScreen), "")
            scr.state = app.liveStreams.contains(.screen) ? .on : .off
            menu.addItem(scr)
            menu.addItem(item("Phone Desktop (DeX-style)…", #selector(phoneDesktop), ""))
            let control = item("Move Pointer to Phone", #selector(toggleControl), "")
            control.state = prefs.controlEnabled ? .on : .off
            menu.addItem(control)
            menu.addItem(.separator())
            menu.addItem(item("Rename \(peer.displayName)…", #selector(renamePeer), ""))
            menu.addItem(item("Pair a Different Phone…", #selector(pair), ""))
            menu.addItem(item("Unpair \(peer.displayName)", #selector(unpair), ""))
        } else {
            menu.addItem(disabled("No phone paired"))
            menu.addItem(.separator())
            menu.addItem(item("Pair Phone…", #selector(pair), ""))
        }
        menu.addItem(.separator())
        menu.addItem(item("Rename This Mac…", #selector(renameMac), ""))
        menu.addItem(item("Settings…", #selector(openSettings), ","))
        menu.addItem(item("Setup Guide…", #selector(openGuide), ""))
        menu.addItem(item("Check for Updates…", #selector(checkForUpdates), ""))
        menu.addItem(.separator())
        menu.addItem(item("Quit NotchDrop", #selector(quit), "q"))
        app.ping()
    }

    private func item(_ title: String, _ action: Selector, _ key: String) -> NSMenuItem {
        let i = NSMenuItem(title: title, action: action, keyEquivalent: key)
        i.target = self
        return i
    }

    private func disabled(_ title: String) -> NSMenuItem {
        let i = NSMenuItem(title: title, action: nil, keyEquivalent: "")
        i.isEnabled = false
        return i
    }

    @objc func openHistory() { historyWindow.show() }

    @objc func phoneCamera() { app.requestStream(.camera) }
    @objc func phoneScreen() { app.requestStream(.screen) }
    @objc func phoneDesktop() { DesktopWindow.shared.show() }

    @objc func toggleControl() {
        prefs.controlEnabled.toggle()
        if prefs.controlEnabled, !SelectionGrabber.accessibilityTrusted { SelectionGrabber.openAccessibilitySettings() }
    }

    @objc func sendFiles() {
        guard app.peer != nil else { pairWindow.show(); return }
        let panel = NSOpenPanel()
        panel.allowsMultipleSelection = true
        panel.canChooseDirectories = true
        panel.prompt = "Send to \(app.peerName)"
        NSApp.activate(ignoringOtherApps: true)
        if panel.runModal() == .OK { app.send(urls: panel.urls, texts: []) }
    }

    @objc private func sendClipboard() { app.sendClipboard() }

    @objc private func openFolder() { openReceivedFolder() }

    func showSettings(_ pane: SettingsPane? = nil) { settings.show(pane) }

    func openReceivedFolder() {
        let dir = app.store.receiveDirectory
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        NSWorkspace.shared.open(dir)
    }

    @objc private func pair() { pairWindow.show() }

    @objc private func unpair() { app.unpair() }

    @objc private func renameMac() {
        let s = app.store
        if let name = askName(title: "Name this Mac",
                              info: "Shown on your phone. Leave empty to use “\(s.systemName)”.",
                              current: s.customName ?? "") {
            app.renameThisMac(name)
        }
    }

    @objc private func renamePeer() {
        guard let peer = app.peer else { return }
        if let alias = askName(title: "Nickname for your phone",
                               info: "Only used on this Mac. Leave empty to use “\(peer.name)”.",
                               current: peer.alias ?? "") {
            app.renamePeer(alias)
        }
    }

    /// Returns the entered text ("" clears the name), or nil if cancelled.
    private func askName(title: String, info: String, current: String) -> String? {
        let alert = NSAlert()
        alert.messageText = title
        alert.informativeText = info
        alert.addButton(withTitle: "Save")
        alert.addButton(withTitle: "Cancel")
        let field = NSTextField(string: current)
        field.frame = NSRect(x: 0, y: 0, width: 260, height: 24)
        field.placeholderString = "Name"
        alert.accessoryView = field
        alert.window.initialFirstResponder = field
        NSApp.activate(ignoringOtherApps: true)
        guard alert.runModal() == .alertFirstButtonReturn else { return nil }
        return String(field.stringValue.prefix(40))
    }

    @objc private func openSettings() { settings.show() }
    @objc private func openGuide() { settings.show(.guide) }

    @objc private func checkForUpdates() { Updater.shared.check(manual: true) }

    @objc private func quit() { NSApp.terminate(nil) }
}
