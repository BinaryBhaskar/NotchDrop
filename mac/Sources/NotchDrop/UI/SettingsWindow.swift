import AppKit
import Combine
import ServiceManagement
import SwiftUI

enum SettingsPane: String, CaseIterable, Identifiable {
    case general, notch, shortcuts, receiving, devices, about
    var id: String { rawValue }

    var title: String {
        switch self {
        case .general: return "General"
        case .notch: return "Notch"
        case .shortcuts: return "Shortcuts"
        case .receiving: return "Receiving"
        case .devices: return "Devices"
        case .about: return "About"
        }
    }

    var symbol: String {
        switch self {
        case .general: return "gearshape.fill"
        case .notch: return "macbook"
        case .shortcuts: return "command"
        case .receiving: return "tray.and.arrow.down.fill"
        case .devices: return "iphone.gen3"
        case .about: return "info.circle.fill"
        }
    }

    var tint: Color {
        switch self {
        case .general: return .gray
        case .notch: return .black
        case .shortcuts: return .indigo
        case .receiving: return .green
        case .devices: return .blue
        case .about: return .teal
        }
    }
}

@MainActor
final class SettingsNavigation: ObservableObject {
    @Published var pane: SettingsPane? = .general
}

@MainActor
final class SettingsWindowController: NSObject, NSWindowDelegate {
    private var window: NSWindow?
    private let app: AppModel
    private let nav = SettingsNavigation()
    var onPair: (() -> Void)?

    init(app: AppModel) { self.app = app }

    func show(_ pane: SettingsPane? = nil) {
        if let pane { nav.pane = pane }
        if window == nil {
            let w = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 720, height: 500),
                             styleMask: [.titled, .closable, .miniaturizable, .fullSizeContentView], backing: .buffered, defer: false)
            w.title = "NotchDrop Settings"
            w.titlebarAppearsTransparent = true
            w.toolbarStyle = .unified
            w.isReleasedWhenClosed = false
            w.delegate = self
            w.contentView = NSHostingView(rootView: SettingsView(app: app, prefs: .shared, nav: nav) { [weak self] in self?.onPair?() })
            w.setContentSize(NSSize(width: 720, height: 500))
            w.center()
            window = w
        }
        NSApp.activate(ignoringOtherApps: true)
        window?.makeKeyAndOrderFront(nil)
        if nav.pane == .notch { Prefs.shared.previewZone = true }
    }

    func windowWillClose(_ notification: Notification) {
        Prefs.shared.previewZone = false
    }
}

struct SettingsView: View {
    @ObservedObject var app: AppModel
    @ObservedObject var prefs: Prefs
    @ObservedObject var nav: SettingsNavigation
    var pair: () -> Void

    var body: some View {
        NavigationSplitView {
            List(SettingsPane.allCases, selection: $nav.pane) { pane in
                Label {
                    Text(pane.title)
                } icon: {
                    Image(systemName: pane.symbol)
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(.white)
                        .frame(width: 22, height: 22)
                        .background(RoundedRectangle(cornerRadius: 6, style: .continuous).fill(pane.tint.gradient))
                }
                .tag(pane)
            }
            .navigationSplitViewColumnWidth(200)
            .toolbar(removing: .sidebarToggle)
        } detail: {
            detail
                .formStyle(.grouped)
                .navigationTitle(nav.pane?.title ?? "")
        }
        .frame(minWidth: 680, minHeight: 460)
        .onChange(of: nav.pane) { _, pane in prefs.previewZone = pane == .notch }
    }

    @ViewBuilder private var detail: some View {
        switch nav.pane ?? .general {
        case .general: GeneralPane(prefs: prefs)
        case .notch: NotchPane(prefs: prefs)
        case .shortcuts: ShortcutsPane(prefs: prefs)
        case .receiving: ReceivingPane(app: app, prefs: prefs)
        case .devices: DevicesPane(app: app, pair: pair)
        case .about: AboutPane()
        }
    }
}

// MARK: - Panes

/// View-local state. (`@State` is a macro in newer SDKs, which the Command Line Tools can't expand.)
final class Box<T>: ObservableObject {
    @Published var value: T
    init(_ value: T) { self.value = value }
}

struct GeneralPane: View {
    @ObservedObject var prefs: Prefs
    @StateObject private var launchAtLogin = Box(SMAppService.mainApp.status == .enabled)
    @StateObject private var loginError = Box<String?>(nil)

    var body: some View {
        Form {
            Section {
                Toggle("Launch at login", isOn: $launchAtLogin.value)
                    .onChange(of: launchAtLogin.value) { _, on in setLogin(on) }
                if let loginError = loginError.value {
                    Text(loginError).font(.caption).foregroundStyle(.red)
                }
                Toggle("Show icon in menu bar", isOn: $prefs.showMenuBarIcon)
                if !prefs.showMenuBarIcon {
                    Text("Open NotchDrop again from Spotlight or Finder to get back to Settings.")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
            Section("Feedback") {
                Toggle("Play sounds", isOn: $prefs.sounds)
                Toggle("Haptic feedback on the trackpad", isOn: $prefs.haptics)
            }
        }
    }

    private func setLogin(_ on: Bool) {
        do {
            if on { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() }
            loginError.value = nil
        } catch {
            loginError.value = "Couldn't change the login item: \(error.localizedDescription)"
            launchAtLogin.value = SMAppService.mainApp.status == .enabled
        }
    }
}

struct NotchPane: View {
    @ObservedObject var prefs: Prefs

    var body: some View {
        Form {
            Section {
                Toggle("Use the notch drop zone", isOn: $prefs.notchEnabled)
            } footer: {
                Text(prefs.notchEnabled
                     ? "Drag files toward the notch and it opens into a drop box."
                     : "The notch stays untouched. Send from the menu bar or with your shortcut.")
            }
            Section {
                Toggle("Open when the pointer rests on the notch", isOn: $prefs.openOnHover)
            } footer: {
                Text("Off: the notch only opens while you're dragging something. On: hovering opens it too, and clicking the box lets you pick files.")
            }
            Section {
                ZonePreview(width: prefs.zoneWidth, height: prefs.zoneHeight)
                    .frame(height: 120)
                    .listRowInsets(EdgeInsets())
                LabeledSlider(title: "Width", value: $prefs.zoneWidth, range: Prefs.zoneWidthRange)
                LabeledSlider(title: "Height", value: $prefs.zoneHeight, range: Prefs.zoneHeightRange)
                HStack {
                    Spacer()
                    Button("Reset to Default") { prefs.resetZone() }
                }
            } header: {
                Text("Drag activation zone")
            } footer: {
                Text("How close to the notch a drag has to come before the drop box opens. The zone is outlined at the top of your screen while this page is open.")
            }
            .disabled(!prefs.notchEnabled)
        }
    }
}

struct LabeledSlider: View {
    var title: String
    @Binding var value: Double
    var range: ClosedRange<Double>

    var body: some View {
        HStack {
            Text(title).frame(width: 52, alignment: .leading)
            // No `step`: a stepped Slider draws tick marks. Round to whole points instead.
            Slider(value: Binding(get: { value }, set: { value = $0.rounded() }), in: range)
            Text("\(Int(value)) pt").monospacedDigit().foregroundStyle(.secondary).frame(width: 56, alignment: .trailing)
        }
    }
}

/// Miniature screen top with the notch and the zone drawn to scale.
struct ZonePreview: View {
    var width: Double
    var height: Double

    var body: some View {
        GeometryReader { g in
            // Scale so the largest zone fits with a little margin.
            let s = min((g.size.width - 40) / Prefs.zoneWidthRange.upperBound,
                        (g.size.height - 6) / Prefs.zoneHeightRange.upperBound)
            ZStack(alignment: .top) {
                LinearGradient(colors: [Color(red: 0.2, green: 0.25, blue: 0.45), Color(red: 0.45, green: 0.3, blue: 0.55)],
                               startPoint: .topLeading, endPoint: .bottomTrailing)
                Rectangle().fill(.black.opacity(0.35)).frame(height: 8)
                Rectangle()
                    .fill(Palette.accent.opacity(0.22))
                    .overlay(Rectangle().strokeBorder(Palette.accent, style: StrokeStyle(lineWidth: 1.5, dash: [5, 4])))
                    .frame(width: width * s, height: height * s)
                UnevenRoundedRectangle(bottomLeadingRadius: 5, bottomTrailingRadius: 5)
                    .fill(.black)
                    .frame(width: 185 * s, height: 32 * s)
            }
            .animation(.easeOut(duration: 0.12), value: width)
            .animation(.easeOut(duration: 0.12), value: height)
        }
    }
}

struct ShortcutsPane: View {
    @ObservedObject var prefs: Prefs
    @StateObject private var trusted = Box(SelectionGrabber.accessibilityTrusted)
    private let poll = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        Form {
            Section {
                LabeledContent("Send selection") {
                    ShortcutRecorder(shortcut: $prefs.shortcut)
                }
            } footer: {
                Text("""
                Press it anywhere to send straight to your phone:
                • In Finder, the selected files.
                • In other apps, the selected text.
                • Otherwise, whatever is on the clipboard.
                """)
            }
            Section("Permissions") {
                LabeledContent {
                    if trusted.value {
                        Label("Allowed", systemImage: "checkmark.circle.fill").foregroundStyle(.green)
                    } else {
                        Button("Open Settings…") { SelectionGrabber.openAccessibilitySettings() }
                    }
                } label: {
                    Text("Accessibility")
                    Text("Lets NotchDrop press ⌘C for you to read selected text. Without it, the shortcut sends the clipboard.")
                }
                LabeledContent {
                    EmptyView()
                } label: {
                    Text("Finder automation")
                    Text("macOS asks the first time you use the shortcut in Finder, so NotchDrop can read which files are selected.")
                }
            }
        }
        .onReceive(poll) { _ in trusted.value = SelectionGrabber.accessibilityTrusted }
    }
}

/// Click, then press a key combination. Esc cancels, Delete clears.
struct ShortcutRecorder: View {
    @Binding var shortcut: Shortcut?
    @StateObject private var rec = RecorderState()

    var body: some View {
        HStack(spacing: 6) {
            Button {
                rec.recording ? rec.stop() : rec.start { shortcut = $0 ?? shortcut } clear: { shortcut = nil }
            } label: {
                Text(rec.recording ? "Type shortcut…" : (shortcut?.display ?? "Record Shortcut"))
                    .font(.system(size: 13, weight: shortcut == nil ? .regular : .semibold, design: .rounded))
                    .frame(minWidth: 120)
            }
            .buttonStyle(.bordered)
            .tint(rec.recording ? .accentColor : nil)
            if shortcut != nil && !rec.recording {
                Button { shortcut = nil } label: { Image(systemName: "xmark.circle.fill") }
                    .buttonStyle(.plain)
                    .foregroundStyle(.secondary)
                    .help("Remove shortcut")
            }
        }
        .onDisappear { rec.stop() }
    }
}

@MainActor
final class RecorderState: ObservableObject {
    @Published var recording = false
    private var monitor: Any?

    func start(done: @escaping (Shortcut?) -> Void, clear: @escaping () -> Void) {
        recording = true
        Hotkey.shared.register(nil) // so pressing the current combo records it instead of firing
        monitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { [weak self] e in
            let key = Int(e.keyCode), shortcut = Shortcut(event: e)
            MainActor.assumeIsolated {
                guard let self else { return }
                switch key {
                case 53: // Esc
                    self.stop(); done(nil)
                case 51, 117: // Delete, Forward Delete
                    self.stop(); clear()
                default:
                    guard let shortcut else { NSSound.beep(); return }
                    self.stop(); done(shortcut)
                }
            }
            return nil
        }
    }

    func stop() {
        if let monitor { NSEvent.removeMonitor(monitor) }
        monitor = nil
        if recording {
            recording = false
            Hotkey.shared.register(Prefs.shared.shortcut)
        }
    }
}

struct ReceivingPane: View {
    @ObservedObject var app: AppModel
    @ObservedObject var prefs: Prefs

    var body: some View {
        Form {
            Section("Files") {
                LabeledContent("Save to") {
                    HStack {
                        Image(nsImage: NSWorkspace.shared.icon(forFile: app.store.receiveDirectory.deletingLastPathComponent().path))
                            .resizable().frame(width: 16, height: 16)
                        Text(displayPath).lineLimit(1).truncationMode(.middle).foregroundStyle(.secondary)
                    }
                }
                HStack {
                    Spacer()
                    if prefs.saveFolder != nil { Button("Use Default") { prefs.saveFolder = nil } }
                    Button("Choose…", action: choose)
                    Button("Show in Finder") {
                        let dir = app.store.receiveDirectory
                        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
                        NSWorkspace.shared.open(dir)
                    }
                }
                Toggle("Ask before accepting", isOn: $prefs.confirmReceive)
            }
            Section("Text and links") {
                Toggle("Copy received text to the clipboard", isOn: $prefs.copyText)
                Toggle("Open received links in the browser", isOn: $prefs.openLinks)
            }
        }
    }

    private var displayPath: String {
        (app.store.receiveDirectory.path as NSString).abbreviatingWithTildeInPath
    }

    private func choose() {
        let panel = NSOpenPanel()
        panel.canChooseDirectories = true
        panel.canChooseFiles = false
        panel.canCreateDirectories = true
        panel.prompt = "Use Folder"
        panel.directoryURL = app.store.receiveDirectory
        if panel.runModal() == .OK, let url = panel.url { prefs.saveFolder = url }
    }
}

struct DevicesPane: View {
    @ObservedObject var app: AppModel
    var pair: () -> Void
    @StateObject private var macName = Box("")
    @StateObject private var nickname = Box("")

    var body: some View {
        Form {
            Section {
                TextField("Name", text: $macName.value, prompt: Text(app.store.systemName))
                    .onSubmit { app.renameThisMac(macName.value.trimmed) }
            } header: {
                Text("This Mac")
            } footer: {
                Text("The name your phone shows. Leave empty to use the computer name.")
            }
            Section("Phone") {
                if let peer = app.peer {
                    HStack(spacing: 12) {
                        PhoneGlyph(color: .primary, size: 30)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(peer.displayName).font(.headline)
                            Text(app.reachable ? "Connected · \(app.route?.rawValue ?? "Wi-Fi")" : "Not reachable right now")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer()
                        Circle().fill(app.reachable ? Color.green : Color.orange).frame(width: 8, height: 8)
                    }
                    TextField("Nickname", text: $nickname.value, prompt: Text(peer.name))
                        .onSubmit { app.renamePeer(nickname.value.trimmed) }
                    HStack {
                        Spacer()
                        Button("Pair a Different Phone…", action: pair)
                        Button("Unpair", role: .destructive) { app.unpair() }
                    }
                } else {
                    HStack {
                        Text("No phone paired").foregroundStyle(.secondary)
                        Spacer()
                        Button("Pair Phone…", action: pair).buttonStyle(.borderedProminent)
                    }
                }
            }
        }
        .onAppear {
            macName.value = app.store.customName ?? ""
            nickname.value = app.peer?.alias ?? ""
            app.ping()
        }
        .onDisappear {
            // Commit edits that weren't confirmed with Return.
            if macName.value.trimmed != app.store.customName { app.renameThisMac(macName.value.trimmed) }
            if app.peer != nil, nickname.value.trimmed != app.peer?.alias { app.renamePeer(nickname.value.trimmed) }
        }
    }
}

struct AboutPane: View {
    private var version: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "dev"
    }

    var body: some View {
        VStack(spacing: 14) {
            Spacer()
            Image(nsImage: NSApp.applicationIconImage)
                .resizable().frame(width: 96, height: 96)
            Text("NotchDrop").font(.system(size: 22, weight: .bold, design: .rounded))
            Text("Version \(version)").foregroundStyle(.secondary)
            Text("Share files, text and links between your Mac and Android phone over Wi-Fi or hotspot. No internet, no accounts — everything is end-to-end encrypted on your local network.")
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
                .frame(maxWidth: 380)
            Link("github.com/BinaryBhaskar/NotchDrop", destination: URL(string: "https://github.com/BinaryBhaskar/NotchDrop")!)
            Text("MIT License").font(.caption).foregroundStyle(.tertiary)
            Spacer()
        }
        .frame(maxWidth: .infinity)
        .padding()
    }
}
