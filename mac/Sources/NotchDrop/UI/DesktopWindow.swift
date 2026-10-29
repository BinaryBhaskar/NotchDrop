import AppKit
import SwiftUI

/// Runs scrcpy against the phone over wireless debugging. Desktop mode asks Android for a second,
/// desktop-sized display (`--new-display`), the closest thing to DeX a Mac can show.
@MainActor
final class DesktopModel: ObservableObject {
    static let tools = ["/opt/homebrew/bin", "/usr/local/bin"]

    @Published var pairAddress = ""
    @Published var pairCode = ""
    @Published var address = UserDefaults.standard.string(forKey: "desktop.address") ?? "" {
        didSet { UserDefaults.standard.set(address, forKey: "desktop.address") }
    }
    @Published var resolution = UserDefaults.standard.string(forKey: "desktop.resolution") ?? "1920x1080/160" {
        didSet { UserDefaults.standard.set(resolution, forKey: "desktop.resolution") }
    }
    @Published var log = ""
    @Published var busy = false
    @Published var running = false

    private var scrcpy: Process?

    var adb: String? { Self.find("adb") }
    var scrcpyPath: String? { Self.find("scrcpy") }

    static func find(_ name: String) -> String? {
        tools.map { "\($0)/\(name)" }.first { FileManager.default.isExecutableFile(atPath: $0) }
    }

    func pair() {
        guard let adb else { return }
        run(adb, ["pair", pairAddress.trimmingCharacters(in: .whitespaces), pairCode.trimmingCharacters(in: .whitespaces)])
    }

    func connect() {
        guard let adb else { return }
        run(adb, ["connect", address.trimmingCharacters(in: .whitespaces)])
    }

    /// Wireless debugging advertises its connect port over Bonjour; fill it in when there's exactly one phone.
    func discover() {
        guard let adb else { return }
        run(adb, ["mdns", "services"]) { [weak self] out in
            let found = out.split(separator: "\n")
                .filter { $0.contains("_adb-tls-connect") }
                .compactMap { $0.split(whereSeparator: { $0 == " " || $0 == "\t" }).last.map(String.init) }
            if let first = found.first { self?.address = first }
            self?.append(found.isEmpty ? "No phone found. Turn on Wireless debugging and pair first." : "Found \(found.joined(separator: ", "))")
        }
    }

    func launch(desktop: Bool) {
        guard let scrcpyPath, !running else { return }
        var args = ["--window-title=Phone \(desktop ? "Desktop" : "Screen")", "--stay-awake"]
        args += desktop ? ["--new-display=\(resolution)"] : ["--turn-screen-off"]
        let target = address.trimmingCharacters(in: .whitespaces)
        if !target.isEmpty { args += ["-s", target] }
        let p = process(scrcpyPath, args)
        p.terminationHandler = { [weak self] proc in
            Task { @MainActor in
                self?.running = false
                self?.scrcpy = nil
                if proc.terminationStatus != 0 { self?.append("scrcpy exited (\(proc.terminationStatus))") }
            }
        }
        do {
            try p.run()
            scrcpy = p
            running = true
            append("$ scrcpy \(args.joined(separator: " "))")
        } catch {
            append(error.localizedDescription)
        }
    }

    func stop() { scrcpy?.terminate() }

    private func process(_ path: String, _ args: [String]) -> Process {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: path)
        p.arguments = args
        var env = ProcessInfo.processInfo.environment
        env["PATH"] = (Self.tools + [env["PATH"] ?? "/usr/bin:/bin"]).joined(separator: ":")
        if let adb { env["ADB"] = adb }
        p.environment = env
        let pipe = Pipe()
        p.standardOutput = pipe
        p.standardError = pipe
        pipe.fileHandleForReading.readabilityHandler = { [weak self] h in
            let data = h.availableData
            guard !data.isEmpty, let s = String(data: data, encoding: .utf8) else { return }
            Task { @MainActor in self?.append(s.trimmingCharacters(in: .newlines)) }
        }
        return p
    }

    private func run(_ path: String, _ args: [String], done: ((String) -> Void)? = nil) {
        busy = true
        append("$ \((path as NSString).lastPathComponent) \(args.joined(separator: " "))")
        let p = Process()
        p.executableURL = URL(fileURLWithPath: path)
        p.arguments = args
        let pipe = Pipe()
        p.standardOutput = pipe
        p.standardError = pipe
        Task.detached {
            var out = ""
            do {
                try p.run()
                out = String(data: pipe.fileHandleForReading.readDataToEndOfFile(), encoding: .utf8) ?? ""
                p.waitUntilExit()
            } catch {
                out = error.localizedDescription
            }
            let text = out.trimmingCharacters(in: .whitespacesAndNewlines)
            await MainActor.run { [weak self] in
                self?.busy = false
                if let done { done(text) } else if !text.isEmpty { self?.append(text) }
            }
        }
    }

    private func append(_ s: String) {
        log += (log.isEmpty ? "" : "\n") + s
        if log.count > 8000 { log = String(log.suffix(6000)) }
    }
}

@MainActor
final class DesktopWindow {
    static let shared = DesktopWindow()
    private var window: NSWindow?
    private let model = DesktopModel()

    func show() {
        if window == nil {
            let w = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 480, height: 560),
                             styleMask: [.titled, .closable, .miniaturizable, .fullSizeContentView],
                             backing: .buffered, defer: false)
            w.title = "Phone Desktop"
            w.titlebarAppearsTransparent = true
            w.isReleasedWhenClosed = false
            w.contentView = NSHostingView(rootView: DesktopView(model: model))
            w.center()
            window = w
        }
        NSApp.activate(ignoringOtherApps: true)
        window?.makeKeyAndOrderFront(nil)
    }
}

struct DesktopView: View {
    @ObservedObject var model: DesktopModel

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Phone Desktop").font(.title2.weight(.semibold))
            Text("Opens a desktop-sized second screen on your phone in a Mac window, with full mouse and keyboard. Samsung phones show their DeX-style taskbar there on One UI 7 and later; Wireless DeX itself uses Miracast, which a Mac can't receive.")
                .font(.callout).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)

            if model.adb == nil || model.scrcpyPath == nil {
                GroupBox {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("Needs scrcpy (free, open source). In Terminal:").font(.callout)
                        HStack {
                            Text("brew install scrcpy").font(.system(.body, design: .monospaced)).textSelection(.enabled)
                            Spacer()
                            Button("Copy") {
                                NSPasteboard.general.clearContents()
                                NSPasteboard.general.setString("brew install scrcpy", forType: .string)
                            }
                        }
                        Text("Then reopen this window.").font(.caption).foregroundStyle(.secondary)
                    }.frame(maxWidth: .infinity, alignment: .leading).padding(4)
                }
            } else {
                GroupBox("1 · Pair once") {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("Phone: Settings › Developer options › Wireless debugging › Pair device with pairing code.")
                            .font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                        HStack {
                            TextField("IP:port shown in the pairing dialog", text: $model.pairAddress)
                            TextField("Code", text: $model.pairCode).frame(width: 80)
                            Button("Pair") { model.pair() }.disabled(model.busy || model.pairAddress.isEmpty || model.pairCode.isEmpty)
                        }
                    }.padding(4)
                }
                GroupBox("2 · Connect") {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("Use the IP address & Port at the top of Wireless debugging (not the pairing one). Leave empty for a USB cable.")
                            .font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                        HStack {
                            TextField("192.168.1.20:37000", text: $model.address)
                            Button("Find") { model.discover() }.disabled(model.busy)
                            Button("Connect") { model.connect() }.disabled(model.busy || model.address.isEmpty)
                        }
                    }.padding(4)
                }
                GroupBox("3 · Open") {
                    HStack {
                        Picker("", selection: $model.resolution) {
                            Text("1920 × 1080").tag("1920x1080/160")
                            Text("2560 × 1440").tag("2560x1440/240")
                            Text("1440 × 900").tag("1440x900/140")
                        }.labelsHidden().frame(width: 130)
                        Button("Open Desktop") { model.launch(desktop: true) }.buttonStyle(.borderedProminent)
                        Button("Mirror") { model.launch(desktop: false) }
                        Spacer()
                        if model.running { Button("Stop") { model.stop() } }
                    }.disabled(model.busy).padding(4)
                }
            }

            ScrollViewReader { proxy in
                ScrollView {
                    Text(model.log.isEmpty ? " " : model.log)
                        .font(.system(size: 11, design: .monospaced))
                        .textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .id("end")
                }
                .onChange(of: model.log) { proxy.scrollTo("end", anchor: .bottom) }
            }
            .padding(8)
            .background(RoundedRectangle(cornerRadius: 8).fill(Color.primary.opacity(0.05)))
        }
        .padding(.horizontal, 20).padding(.top, 36).padding(.bottom, 20)
        .frame(width: 480, height: 560)
    }
}
