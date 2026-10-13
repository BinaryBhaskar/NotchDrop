import AppKit

/// Looks for a newer NotchDrop on GitHub Releases. Off unless the user opts in; manual checks always work.
@MainActor
final class Updater: ObservableObject {
    static let shared = Updater()
    nonisolated static let latestURL = URL(string: "https://api.github.com/repos/BinaryBhaskar/NotchDrop/releases/latest")!

    struct Release: Equatable {
        var version: String
        var page: URL
        /// The DMG asset, when the release has one.
        var download: URL?
        var notes: String
    }

    enum Status: Equatable {
        case idle, checking, upToDate, available(Release), failed(String)
    }

    @Published private(set) var status = Status.idle

    private enum Key {
        static let asked = "updates.asked"
        static let lastCheck = "updates.lastCheck"
        static let skipped = "updates.skippedVersion"
    }

    private let d = UserDefaults.standard
    private let prefs = Prefs.shared
    private var timer: Timer?
    private static let interval: TimeInterval = 24 * 60 * 60

    var currentVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0"
    }

    /// Asks once whether to check automatically, then checks whenever a day has passed.
    func start() {
        if !d.bool(forKey: Key.asked) {
            d.set(true, forKey: Key.asked)
            askToOptIn()
        }
        checkIfDue()
        timer = Timer.scheduledTimer(withTimeInterval: 60 * 60, repeats: true) { _ in
            MainActor.assumeIsolated { Updater.shared.checkIfDue() }
        }
    }

    private func askToOptIn() {
        let alert = NSAlert()
        alert.messageText = "Check for updates automatically?"
        alert.informativeText = "NotchDrop can look for a new version on GitHub once a day and let you know. You can change this in Settings."
        alert.addButton(withTitle: "Check Automatically")
        alert.addButton(withTitle: "Not Now")
        NSApp.activate(ignoringOtherApps: true)
        prefs.autoCheckUpdates = alert.runModal() == .alertFirstButtonReturn
    }

    func checkIfDue() {
        guard prefs.autoCheckUpdates else { return }
        let last = d.double(forKey: Key.lastCheck)
        guard Date().timeIntervalSince1970 - last >= Self.interval else { return }
        check(manual: false)
    }

    /// A manual check reports "up to date" and failures; an automatic one only speaks up for a new version
    /// the user hasn't skipped.
    func check(manual: Bool) {
        guard status != .checking else { return }
        status = .checking
        Task {
            let result = await Self.fetchLatest()
            d.set(Date().timeIntervalSince1970, forKey: Key.lastCheck)
            switch result {
            case .success(let r) where Self.isNewer(r.version, than: currentVersion):
                status = .available(r)
                if manual || d.string(forKey: Key.skipped) != r.version { prompt(r) }
            case .success:
                status = .upToDate
                if manual { info("You're up to date", "NotchDrop \(currentVersion) is the latest version.") }
            case .failure(let e):
                status = .failed(e.localizedDescription)
                if manual { info("Couldn't check for updates", e.localizedDescription) }
            }
        }
    }

    private func prompt(_ r: Release) {
        let alert = NSAlert()
        alert.messageText = "NotchDrop \(r.version) is available"
        var text = "You have \(currentVersion)."
        let notes = r.notes.trimmingCharacters(in: .whitespacesAndNewlines)
        if !notes.isEmpty { text += "\n\n" + String(notes.prefix(600)) }
        alert.informativeText = text
        alert.addButton(withTitle: "Download")
        alert.addButton(withTitle: "Later")
        alert.addButton(withTitle: "Skip This Version")
        NSApp.activate(ignoringOtherApps: true)
        switch alert.runModal() {
        case .alertFirstButtonReturn: NSWorkspace.shared.open(r.download ?? r.page)
        case .alertThirdButtonReturn: d.set(r.version, forKey: Key.skipped)
        default: break
        }
    }

    private func info(_ title: String, _ text: String) {
        let alert = NSAlert()
        alert.messageText = title
        alert.informativeText = text
        NSApp.activate(ignoringOtherApps: true)
        alert.runModal()
    }

    nonisolated static func fetchLatest() async -> Result<Release, Error> {
        var req = URLRequest(url: latestURL, timeoutInterval: 15)
        req.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        do {
            let (data, resp) = try await URLSession.shared.data(for: req)
            guard (resp as? HTTPURLResponse)?.statusCode == 200,
                  let o = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let tag = o["tag_name"] as? String,
                  let page = (o["html_url"] as? String).flatMap(URL.init(string:))
            else { throw URLError(.badServerResponse) }
            let assets = o["assets"] as? [[String: Any]] ?? []
            let dmg = assets.first { ($0["name"] as? String)?.hasSuffix(".dmg") == true }
            return .success(Release(version: normalize(tag), page: page,
                                    download: (dmg?["browser_download_url"] as? String).flatMap(URL.init(string:)),
                                    notes: o["body"] as? String ?? ""))
        } catch {
            return .failure(error)
        }
    }

    nonisolated static func normalize(_ tag: String) -> String {
        tag.hasPrefix("v") || tag.hasPrefix("V") ? String(tag.dropFirst()) : tag
    }

    /// Numeric dotted comparison: "1.10.0" > "1.9.2", "1.2" == "1.2.0".
    nonisolated static func isNewer(_ a: String, than b: String) -> Bool {
        func parts(_ s: String) -> [Int] {
            normalize(s).split(separator: "-")[0].split(separator: ".").map { Int($0) ?? 0 }
        }
        let x = parts(a), y = parts(b)
        for i in 0..<max(x.count, y.count) {
            let l = i < x.count ? x[i] : 0, r = i < y.count ? y[i] : 0
            if l != r { return l > r }
        }
        return false
    }
}
