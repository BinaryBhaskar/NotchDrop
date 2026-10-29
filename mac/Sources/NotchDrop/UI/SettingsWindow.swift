import AppKit
import Combine
import ServiceManagement
import SwiftUI

enum SettingsPane: String, CaseIterable, Identifiable {
    case guide, general, notch, shortcuts, receiving, phone, devices, about
    var id: String { rawValue }

    var title: String {
        switch self {
        case .guide: return "Setup Guide"
        case .general: return "General"
        case .notch: return "Notch"
        case .shortcuts: return "Shortcuts"
        case .receiving: return "Receiving"
        case .phone: return "Phone"
        case .devices: return "Devices"
        case .about: return "About"
        }
    }

    var symbol: String {
        switch self {
        case .guide: return "book.fill"
        case .general: return "gearshape.fill"
        case .notch: return "macbook"
        case .shortcuts: return "command"
        case .receiving: return "tray.and.arrow.down.fill"
        case .phone: return "cursorarrow.motionlines"
        case .devices: return "iphone.gen3"
        case .about: return "info.circle.fill"
        }
    }

    var tint: Color {
        switch self {
        case .guide: return .pink
        case .general: return .gray
        case .notch: return .black
        case .shortcuts: return .indigo
        case .receiving: return .green
        case .phone: return .orange
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
        case .guide: GuidePane(nav: nav)
        case .general: GeneralPane(prefs: prefs)
        case .notch: NotchPane(prefs: prefs)
        case .shortcuts: ShortcutsPane(prefs: prefs)
        case .receiving: ReceivingPane(app: app, prefs: prefs)
        case .phone: PhonePane(app: app, prefs: prefs)
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
            Section {
                LabeledContent("Share menu and Services") {
                    Button("Open Extensions Settings…") {
                        let id = "com.apple.ExtensionsPreferences?extensionPointIdentifier=com.apple.share-services"
                        if let url = URL(string: "x-apple.systempreferences:\(id)") { NSWorkspace.shared.open(url) }
                    }
                }
            } footer: {
                Text("Choose NotchDrop from the Share button in Finder, Photos, Safari and other apps, or from Services in the right-click menu. If it isn't listed, turn it on in Extensions settings.")
            }
            Section("Feedback") {
                Toggle("Play sounds", isOn: $prefs.sounds)
                Toggle("Haptic feedback on the trackpad", isOn: $prefs.haptics)
            }
            UpdatesSection(prefs: prefs, updater: .shared)
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

struct UpdatesSection: View {
    @ObservedObject var prefs: Prefs
    @ObservedObject var updater: Updater

    var body: some View {
        Section {
            Toggle("Check for updates automatically", isOn: $prefs.autoCheckUpdates)
            LabeledContent {
                Button("Check Now") { updater.check(manual: true) }
                    .disabled(updater.status == .checking)
            } label: {
                Text("Version \(updater.currentVersion)")
                Text(statusText)
            }
        } header: {
            Text("Updates")
        } footer: {
            Text("Once a day, NotchDrop asks GitHub for the latest release. Nothing else leaves your Mac.")
        }
    }

    private var statusText: String {
        switch updater.status {
        case .idle: return "Not checked yet"
        case .checking: return "Checking…"
        case .upToDate: return "Up to date"
        case .available(let r): return "Version \(r.version) is available"
        case .failed: return "Couldn't reach GitHub"
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
                Toggle("Hide the notch", isOn: $prefs.hideNotch)
                Toggle("Open on the display with the pointer", isOn: $prefs.followPointer)
            } footer: {
                Text("Hidden, nothing is drawn until you drag something to the top of the screen or open it, and it never shows in screenshots, recordings or screen sharing. Drops work the same.")
            }
            .disabled(!prefs.notchEnabled)
            Section {
                Toggle("Open when the pointer rests on the notch", isOn: $prefs.openOnHover)
            } footer: {
                Text("Off: the notch only opens while you're dragging something. On: hovering opens it too, and clicking the box lets you pick files.")
            }
            Section {
                Toggle("Swipe down on the notch to open it", isOn: $prefs.swipeToOpen)
            } footer: {
                Text("With the pointer at the notch, scroll or swipe down with two fingers to open it, and up to close it.")
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
            Section {
                LabeledContent("Open notch") {
                    ShortcutRecorder(shortcut: $prefs.openShortcut)
                }
            } footer: {
                Text("Opens the notch on the display the pointer is on; press again or click elsewhere to close it. In the Shortcuts app, use “Open URL” with notchdrop://open to do the same.")
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

/// Step-by-step setup for every feature, each with a link to the pane that has its settings.
struct GuidePane: View {
    @ObservedObject var nav: SettingsNavigation
    @StateObject private var open = Box(Set<String>())

    private struct Topic {
        let title: String, symbol: String, pane: SettingsPane?, steps: [String]
    }

    private let topics: [Topic] = [
        Topic(title: "Pair your phone", symbol: "qrcode", pane: .devices, steps: [
            "Install NotchDrop on the Android phone (the APK from GitHub Releases).",
            "On the Mac, choose **Pair Phone…** in the menu bar. A QR code appears.",
            "On the phone, tap **Scan to pair** and scan it.",
            "Keep both on the same Wi-Fi, or join the Mac to the phone's hotspot.",
        ]),
        Topic(title: "Send to the phone", symbol: "arrow.up.circle", pane: .shortcuts, steps: [
            "Drag files, folders, links or text up to the notch and drop them on the box that opens.",
            "Or use **Share › NotchDrop** in Finder, Photos and Safari, or **Services › Send to Phone with NotchDrop**.",
            "Record a **Send selection** shortcut in Shortcuts to send whatever is selected, from anywhere.",
            "If the Share item is missing: System Settings › General › Login Items & Extensions › Sharing.",
        ]),
        Topic(title: "Receive from the phone", symbol: "arrow.down.circle", pane: .receiving, steps: [
            "On the phone, use **Share › NotchDrop**, or the **Clipboard** tile.",
            "Files land in ~/Downloads/From <phone>, or the folder set in **Receiving**.",
            "Swipe down on the notch with two fingers (or hover, if turned on) to see recent items; ⌘Y opens History.",
            "Turn on **Receive in the background** on the phone so the Mac can send to it anytime.",
        ]),
        Topic(title: "Notch", symbol: "macbook", pane: .notch, steps: [
            "Record an **Open notch** shortcut in Shortcuts to open it on the screen with the pointer.",
            "**Hide the notch** keeps it out of sight and out of screenshots; drops still work.",
            "Adjust the activation zone with the sliders in **Notch**.",
        ]),
        Topic(title: "Phone camera as a webcam", symbol: "camera", pane: .phone, steps: [
            "Choose **Phone Camera** in the menu bar, or tap **Camera to Mac** on the phone. The phone may ask you to tap a notification first.",
            "Pick lens, zoom, torch and quality (up to 4K) in the window; the shutter saves a full photo here.",
            "For Zoom, FaceTime or Chrome: install OBS (obsproject.com), click **OBS** in the camera window and follow its three steps.",
            "In the call app, choose **OBS Virtual Camera**.",
        ]),
        Topic(title: "Phone screen", symbol: "rectangle.on.rectangle", pane: .phone, steps: [
            "Choose **Phone Screen** in the menu bar, or tap **Mirror screen** on the phone.",
            "On the phone, choose **Entire screen** and tap **Start** (Android asks every time).",
            "To click, scroll and type in the window, turn on **Phone control** in the phone app's settings.",
        ]),
        Topic(title: "Mouse and keyboard on the phone", symbol: "cursorarrow.motionlines", pane: .phone, steps: [
            "On the phone: Settings › **Phone control** › **Turn on**. If it's greyed out, tap **App info**, then ⋮ › **Allow restricted settings**, and try again.",
            "Here, in **Phone**, turn on **Move the pointer to the phone**, then drag the phone in **Arrangement** next to the screen and spot where it sits.",
            "Allow NotchDrop under **Accessibility** when asked. After each update, switch it off and on again there.",
            "Push the pointer into the phone. Come back out the same side, or press ⌘⎋. Right-click is Back.",
            "Drag files into the phone to send them.",
            "If Wi-Fi drops, the pointer and keyboard come back to the Mac on their own within a few seconds.",
        ]),
        Topic(title: "Phone desktop (DeX-style)", symbol: "display", pane: nil, steps: [
            "In Terminal: **brew install scrcpy**.",
            "On the phone, turn on Developer options (About phone › Software information › tap Build number 7 times), then **Wireless debugging**.",
            "Choose **Phone Desktop…** in the menu bar.",
            "On the phone, tap **Pair device with pairing code** and type its IP:port and code into **Pair**.",
            "Click **Find**, then **Connect**, then **Open Desktop**.",
        ]),
    ]

    var body: some View {
        Form {
            Section {
                Text("Set up each feature in a few steps. Click a topic to expand it.")
                    .foregroundStyle(.secondary)
            }
            ForEach(topics, id: \.title) { t in
                Section {
                    DisclosureGroup(isExpanded: Binding(
                        get: { open.value.contains(t.title) },
                        set: { on in if on { open.value.insert(t.title) } else { open.value.remove(t.title) } }
                    )) {
                        VStack(alignment: .leading, spacing: 8) {
                            ForEach(Array(t.steps.enumerated()), id: \.offset) { i, step in
                                HStack(alignment: .firstTextBaseline, spacing: 8) {
                                    Text("\(i + 1)").font(.callout.monospacedDigit().weight(.semibold)).foregroundStyle(.tint)
                                        .frame(width: 14, alignment: .trailing)
                                    Text(.init(step)).font(.callout).fixedSize(horizontal: false, vertical: true)
                                }
                            }
                            HStack {
                                Spacer()
                                if let pane = t.pane {
                                    Button("Open \(pane.title) Settings") { nav.pane = pane }
                                } else {
                                    Button("Open Phone Desktop…") { DesktopWindow.shared.show() }
                                }
                            }
                        }
                        .padding(.top, 6)
                    } label: {
                        // The whole row toggles, not just the chevron.
                        Label(t.title, systemImage: t.symbol)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .contentShape(Rectangle())
                            .onTapGesture {
                                withAnimation(.easeOut(duration: 0.2)) {
                                    if open.value.contains(t.title) { open.value.remove(t.title) } else { open.value.insert(t.title) }
                                }
                            }
                    }
                }
            }
        }
    }
}

struct PhonePane: View {
    @ObservedObject var app: AppModel
    @ObservedObject var prefs: Prefs
    @StateObject private var trusted = Box(SelectionGrabber.accessibilityTrusted)
    private let poll = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        Form {
            Section {
                LabeledContent {
                    Button(app.liveStreams.contains(.camera) ? "Stop" : "Start") {
                        app.liveStreams.contains(.camera) ? app.stopStream(.camera) : app.requestStream(.camera)
                    }
                } label: {
                    Text("Camera")
                    Text("Up to 4K at 60 fps where the phone supports it. Switch lenses, zoom, torch and take photos from the window.")
                }
                LabeledContent {
                    Button(app.liveStreams.contains(.screen) ? "Stop" : "Start") {
                        app.liveStreams.contains(.screen) ? app.stopStream(.screen) : app.requestStream(.screen)
                    }
                } label: {
                    Text("Screen")
                    Text("Mirrors the phone. Click and type in the window when Phone control is on in the phone app.")
                }
                LabeledContent {
                    Button("Open…") { DesktopWindow.shared.show() }
                } label: {
                    Text("Desktop")
                    Text("A separate desktop-sized screen from the phone, through scrcpy and wireless debugging.")
                }
            } header: {
                Text("Camera and screen")
            } footer: {
                Text("To use the camera in Zoom, FaceTime or Chrome: in OBS, add the stream window (or the Media Source the camera window shows) and click Start Virtual Camera, then pick “OBS Virtual Camera”.")
            }
            Section {
                Toggle("Move the pointer to the phone", isOn: $prefs.controlEnabled)
                VStack(alignment: .leading, spacing: 6) {
                    Text("Arrangement")
                    Text("Drag the phone to the edge of the screen it sits next to. The pointer crosses over only along the phone.")
                        .font(.caption).foregroundStyle(.secondary)
                    ArrangementView(prefs: prefs)
                }
                .disabled(!prefs.controlEnabled)
                .opacity(prefs.controlEnabled ? 1 : 0.5)
                LabeledContent {
                    if trusted.value {
                        Label("Allowed", systemImage: "checkmark.circle.fill").foregroundStyle(.green)
                    } else {
                        Button("Open Settings…") { SelectionGrabber.openAccessibilitySettings() }
                    }
                } label: {
                    Text("Accessibility")
                    Text("Needed to take over the mouse and keyboard. After installing an update, turn NotchDrop off and on again in the list.")
                }
            } header: {
                Text("Mouse and keyboard")
            } footer: {
                Text("""
                Push the pointer into the phone: the mouse and keyboard work the phone until you move back out the same side, or press ⌘⎋. If the connection drops, the pointer comes back on its own within a few seconds. Right-click goes Back, ⌘H goes Home, ⌘V pastes the Mac clipboard.
                Drag files past the edge to send them to the phone.
                The phone needs Phone control turned on in NotchDrop's settings there.
                """)
            }
        }
        .onReceive(poll) { _ in trusted.value = SelectionGrabber.accessibilityTrusted }
    }
}

/// The Mac's displays and the phone, like System Settings › Displays. Drag the phone; it snaps to the nearest free edge.
struct ArrangementView: View {
    @ObservedObject var prefs: Prefs
    @StateObject private var screens = Box(PhonePlacement.screens())
    @StateObject private var drag = Box(CGSize.zero)
    private let changed = NotificationCenter.default.publisher(for: NSApplication.didChangeScreenParametersNotification)

    var body: some View {
        GeometryReader { geo in
            let all = screens.value
            let phone = PhonePlacement.phoneSize(all)
            let bounds = all.reduce(CGRect.null) { $0.union($1.frame) }
                .insetBy(dx: -phone.height - 40, dy: -phone.height - 40)
            let scale = min(geo.size.width / max(bounds.width, 1), geo.size.height / max(bounds.height, 1))
            let ox = (geo.size.width - bounds.width * scale) / 2, oy = (geo.size.height - bounds.height * scale) / 2
            let map = { (r: CGRect) in
                CGRect(x: ox + (r.minX - bounds.minX) * scale, y: oy + (r.minY - bounds.minY) * scale,
                       width: r.width * scale, height: r.height * scale)
            }
            let placed = placement(all, phone)
            let p = map(placed.rect)

            ZStack(alignment: .topLeading) {
                ForEach(all, id: \.uuid) { s in
                    let r = map(s.frame)
                    let selected = s.uuid == placed.uuid
                    RoundedRectangle(cornerRadius: 4)
                        .fill(LinearGradient(colors: [Color.blue.opacity(0.55), Color.indigo.opacity(0.55)], startPoint: .top, endPoint: .bottom))
                        .overlay(RoundedRectangle(cornerRadius: 4).strokeBorder(selected ? Color.accentColor : .white.opacity(0.25), lineWidth: selected ? 2 : 1))
                        .overlay(Text(s.name).font(.caption2.weight(.medium)).foregroundStyle(.white).lineLimit(1).padding(4))
                        .frame(width: r.width, height: r.height)
                        .offset(x: r.minX, y: r.minY)
                }
                RoundedRectangle(cornerRadius: max(4, p.width * 0.16))
                    .fill(Color.orange.gradient)
                    .overlay(Image(systemName: "iphone").font(.system(size: min(p.width, p.height) * 0.45)).foregroundStyle(.white))
                    .overlay(RoundedRectangle(cornerRadius: max(4, p.width * 0.16)).strokeBorder(.white.opacity(0.6), lineWidth: 1))
                    .shadow(color: .black.opacity(drag.value == .zero ? 0.15 : 0.35), radius: drag.value == .zero ? 2 : 6)
                    .frame(width: p.width, height: p.height)
                    .offset(x: p.minX + drag.value.width, y: p.minY + drag.value.height)
                    .gesture(
                        DragGesture(minimumDistance: 1)
                            .onChanged { drag.value = $0.translation }
                            .onEnded { v in
                                let moved = placed.rect.offsetBy(dx: v.translation.width / scale, dy: v.translation.height / scale)
                                withAnimation(.spring(response: 0.35, dampingFraction: 0.8)) {
                                    if let snap = PhonePlacement.snap(moved, screens: all, phone: phone) {
                                        prefs.controlDisplay = snap.uuid
                                        prefs.controlEdge = snap.edge.rawValue
                                        prefs.controlOffset = snap.offset
                                    }
                                    drag.value = .zero
                                }
                            }
                    )
                    .help("Drag to where the phone sits")
            }
        }
        .frame(height: 200)
        .background(RoundedRectangle(cornerRadius: 8).fill(Color.primary.opacity(0.05)))
        .onReceive(changed) { _ in screens.value = PhonePlacement.screens() }
    }

    private func placement(_ all: [PhonePlacement.Screen], _ phone: CGSize) -> (uuid: String, rect: CGRect) {
        let edge = UniversalControl.Edge(rawValue: prefs.controlEdge) ?? .right
        guard let s = all.first(where: { $0.uuid == prefs.controlDisplay }) ?? all.first(where: { CGDisplayIsMain($0.id) != 0 }) ?? all.first
        else { return ("", .zero) }
        return (s.uuid, PhonePlacement.phoneRect(s, edge, offset: prefs.controlOffset, phone: phone))
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
        Hotkey.shared.suspend()
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
            Hotkey.shared.registerAll()
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
            Section {
                Toggle("Show a notification for received items", isOn: $prefs.notify)
                    .disabled(!prefs.notchEnabled)
            } footer: {
                Text(prefs.notchEnabled
                     ? "With Show in Finder and Open for files, Copy and Open Link for text."
                     : "Always on while the notch is turned off, since nothing else would show what arrived.")
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
            Link(destination: URL(string: "https://amurot.com")!) {
                HStack(spacing: 8) {
                    AmurotLogo().frame(width: 18, height: 18)
                    Text("Created by **Amurot**")
                }
                .foregroundStyle(.secondary)
            }
            .buttonStyle(.plain)
            .help("amurot.com")
        }
        .frame(maxWidth: .infinity)
        .padding()
    }
}
