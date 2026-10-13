import AppKit
import Combine
import ServiceManagement

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate, NSMenuDelegate {
    private let app = AppModel.shared
    private var notch: NotchController!
    private var pairWindow: PairWindowController!
    private var statusItem: NSStatusItem!
    private var bag = Set<AnyCancellable>()

    func applicationDidFinishLaunching(_ notification: Notification) {
        app.start()
        notch = NotchController(app: app)
        pairWindow = PairWindowController(app: app)

        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)
        let menu = NSMenu()
        menu.delegate = self
        statusItem.menu = menu
        app.$reachable.combineLatest(app.$peer).sink { [weak self] reachable, peer in
            self?.updateIcon(paired: peer != nil, reachable: reachable)
        }.store(in: &bag)

        if app.peer == nil { pairWindow.show() }
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
            menu.addItem(item("Send Clipboard", #selector(sendClipboard), "v"))
            menu.addItem(item("Open Received Folder", #selector(openFolder), ""))
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
        let login = item("Launch at Login", #selector(toggleLogin), "")
        login.state = SMAppService.mainApp.status == .enabled ? .on : .off
        menu.addItem(login)
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

    @objc private func sendFiles() {
        let panel = NSOpenPanel()
        panel.allowsMultipleSelection = true
        panel.canChooseDirectories = true
        panel.prompt = "Send to \(app.peerName)"
        NSApp.activate(ignoringOtherApps: true)
        if panel.runModal() == .OK { app.send(urls: panel.urls, texts: []) }
    }

    @objc private func sendClipboard() { app.sendClipboard() }

    @objc private func openFolder() {
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

    @objc private func toggleLogin() {
        do {
            if SMAppService.mainApp.status == .enabled {
                try SMAppService.mainApp.unregister()
            } else {
                try SMAppService.mainApp.register()
            }
        } catch {
            NSLog("NotchDrop: login item error \(error)")
        }
    }

    @objc private func quit() { NSApp.terminate(nil) }
}
