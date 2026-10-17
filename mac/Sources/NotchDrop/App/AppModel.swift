import AppKit
import Network

enum Activity: Equatable {
    case idle
    case connecting(count: Int)
    case sending(name: String, fraction: Double, count: Int)
    case sent(count: Int)
    case receiving(name: String, fraction: Double)
    case received(files: [URL], texts: [String])
    case incoming(summary: String)
    case failed(String)
    /// Stopped by either side; the message says which.
    case cancelled(String)

    var isTransferring: Bool {
        switch self {
        case .sending, .receiving: return true
        default: return false
        }
    }

    var isConnecting: Bool {
        if case .connecting = self { return true }
        return false
    }
}

enum Route: String {
    case wifi = "Wi-Fi"
    case hotspot = "Hotspot"
}

struct PairingInfo: Equatable {
    var key: Data
    var uri: String
}

@MainActor
final class AppModel: ObservableObject {
    static let shared = AppModel()

    @Published private(set) var peer: PairedPeer?
    @Published private(set) var reachable = false
    @Published private(set) var route: Route?
    @Published private(set) var activity: Activity = .idle
    /// The last send, if it failed partway; offered as Resume, never retried on its own.
    @Published private(set) var resumable: PendingSend?
    /// Set while the pointer is over a finished transfer (or dragging a file out of it), so it doesn't vanish mid-use.
    var holdActivity = false
    private(set) lazy var recents = Recents(file: store.recentsFile)
    private(set) lazy var history = History(file: store.historyFile)
    /// Live speed and time left of the running transfer, e.g. "12 MB/s · 8 s left".
    @Published private(set) var speed: String?
    /// Sends dropped while busy, started one after another.
    @Published private(set) var queuedCount = 0
    @Published private(set) var pairing: PairingInfo?
    @Published private(set) var justPaired = false

    let store = Store.shared
    let prefs = Prefs.shared
    private var pendingConfirm: CheckedContinuation<Bool, Never>?
    private let browser = PeerBrowser()
    private var server: ProtocolServer?
    private var bridge: Bridge?
    private var pathMonitor: NWPathMonitor?
    private var pingTimer: Timer?
    private var resetToken = 0
    private var pinging = false
    /// The running transfer, so the notch's Cancel button can stop it.
    private var transfer: TransferControl?
    private var queue: [(urls: [URL], texts: [String])] = [] { didSet { queuedCount = queue.count } }
    private var meter = RateMeter()

    var peerName: String { peer?.displayName ?? "Phone" }
    var isBusy: Bool {
        switch activity {
        case .connecting, .sending, .receiving, .incoming: return true
        default: return false
        }
    }

    // MARK: Lifecycle

    func start() {
        // Zipped folders, dropped promises and shared items from earlier runs; a failed send isn't resumable after a relaunch.
        try? FileManager.default.removeItem(at: Transfer.tempDirectory)
        peer = store.peer
        let bridge = Bridge(model: self, store: store)
        self.bridge = bridge
        let resume = ResumeStore(file: store.resumeFile)
        resume.cleanUp(folders: [store.receiveDirectory])
        let server = ProtocolServer(port: Wire.macPort, advertise: .init(
            name: store.deviceName, txt: ["id": store.deviceId, "role": "mac"]), resume: resume)
        server.delegate = bridge
        self.server = server
        Task {
            do { try await server.start() } catch { NSLog("NotchDrop: cannot listen: \(error)") }
        }

        browser.onChange = { [weak self] id in
            guard let self, id == self.peer?.id, !self.reachable else { return }
            self.ping()
        }
        browser.start()

        let monitor = NWPathMonitor()
        monitor.pathUpdateHandler = { [weak self] _ in
            Task { @MainActor in
                try? await Task.sleep(nanoseconds: 1_500_000_000) // let DHCP settle
                self?.ping()
            }
        }
        monitor.start(queue: .main)
        pathMonitor = monitor

        pingTimer = Timer.scheduledTimer(withTimeInterval: 45, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.ping() }
        }
        ping()
    }

    func myHello() -> [String: Any] {
        ["t": "hello", "id": store.deviceId, "name": store.deviceName, "platform": "mac",
         "addrs": NetUtil.ipv4Addresses(), "port": Int(Wire.macPort)]
    }

    // MARK: Pairing

    func beginPairing() {
        let key = randomBytes(32)
        store.pendingPairKey = key
        justPaired = false
        var c = URLComponents()
        c.scheme = "notchdrop"
        c.host = "pair"
        c.queryItems = [
            .init(name: "v", value: "\(Wire.version)"),
            .init(name: "id", value: store.deviceId),
            .init(name: "n", value: store.deviceName),
            .init(name: "k", value: key.base64EncodedString()
                .replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
                .replacingOccurrences(of: "=", with: "")),
            .init(name: "p", value: "\(Wire.macPort)"),
            .init(name: "a", value: NetUtil.ipv4Addresses().joined(separator: ",")),
        ]
        pairing = PairingInfo(key: key, uri: c.url!.absoluteString)
    }

    func endPairing() {
        store.pendingPairKey = nil
        pairing = nil
    }

    /// Renames this Mac as the phone sees it; nil restores the computer name.
    func renameThisMac(_ name: String?) {
        store.customName = name
        server?.readvertise(name: store.deviceName)
        objectWillChange.send()
        ping() // the phone learns the new name from our hello
    }

    /// Sets a local nickname for the paired phone; nil goes back to the phone's own name.
    func renamePeer(_ alias: String?) {
        guard var p = store.peer else { return }
        p.alias = alias?.trimmed
        store.peer = p
        peer = p
    }

    func unpair() {
        store.peer = nil
        peer = nil
        reachable = false
        route = nil
    }

    fileprivate func didPair(_ p: PairedPeer, host: String?) {
        store.peer = p
        store.pendingPairKey = nil
        peer = p
        pairing = nil
        justPaired = true
        markReachable(host: host)
    }

    // MARK: Presence

    fileprivate func markReachable(host: String?) {
        reachable = true
        if let host, let gw = NetUtil.defaultGateway(), host == gw {
            route = .hotspot
        } else {
            route = .wifi
        }
        peer = store.peer
    }

    private func candidates(for p: PairedPeer) -> [NWEndpoint] {
        var eps = browser.endpoints(for: p.id)
        var hosts = p.addrs
        if let gw = NetUtil.defaultGateway(), !hosts.contains(gw) { hosts.append(gw) }
        let port = NWEndpoint.Port(rawValue: p.port) ?? NWEndpoint.Port(rawValue: Wire.phonePort)!
        eps += hosts.map { NWEndpoint.hostPort(host: NWEndpoint.Host($0), port: port) }
        return eps
    }

    private func connect() async -> PeerLink.Connected? {
        guard let p = store.peer else { return nil }
        let c = await PeerLink.connect(candidates: candidates(for: p), mode: "session", key: p.key,
                                       myId: store.deviceId, hello: myHello())
        if let c {
            let addrs = c.hello["addrs"] as? [String] ?? []
            let port = (c.hello["port"] as? NSNumber).map { UInt16(truncating: $0) }
            store.updatePeerAddresses(addrs, port: port, name: c.hello["name"] as? String)
            markReachable(host: c.host)
        } else {
            reachable = false
            route = nil
        }
        return c
    }

    func ping() {
        guard store.peer != nil, !pinging, !isBusy else { return }
        pinging = true
        Task {
            if let c = await connect() {
                try? await c.channel.send(json: ["t": "bye"])
                c.channel.close()
            }
            pinging = false
        }
    }

    // MARK: Sending

    func send(urls: [URL], texts: [String]) {
        guard store.peer != nil, !(urls.isEmpty && texts.isEmpty) else { return }
        if isBusy {
            queue.append((urls, texts))
            prefs.play("Tink")
            return
        }
        let count = urls.count + texts.count
        transmit(count: count) { try await Task.detached { try Transfer.prepare(urls: urls, texts: texts) }.value }
    }

    /// Sends the failed batch again under the same id; the phone skips what it already has.
    func resumeSend() {
        guard let p = resumable, store.peer != nil, !isBusy else { return }
        queue = []
        transmit(count: p.items.count, id: p.id, sentAny: p.sentAny) { p.items }
    }

    /// `prepare` runs after the notch shows "connecting", since zipping a folder can take a moment.
    private func transmit(count: Int, id: String = UUID().uuidString, sentAny: Bool = false,
                          prepare: @escaping () async throws -> [OutgoingItem]) {
        resumable = nil
        let control = TransferControl(note: cancelNote)
        transfer = control
        setActivity(.connecting(count: count))
        Task {
            defer { if transfer === control { transfer = nil } }
            var prepared: [OutgoingItem] = []
            let sent = SentFlag(sentAny)
            do {
                let items = try await prepare()
                prepared = items
                guard !control.isCancelled else { return }
                guard !items.isEmpty else { setActivity(.idle); return }
                guard let c = await connect() else { throw NDError.unreachable }
                defer { c.channel.close() }
                guard !control.isCancelled else { return }
                control.attach(c.channel, receiving: false)
                let throttle = ProgressThrottle { done, total, name in
                    sent.report(done)
                    let f = total > 0 ? Double(done) / Double(total) : 1
                    Task { @MainActor in
                        guard !control.isCancelled else { return }
                        self.setActivity(.sending(name: name, fraction: f, count: items.count))
                        self.speed = self.meter.update(done: done, total: total)
                    }
                }
                setActivity(.sending(name: "", fraction: 0, count: items.count))
                try await Transfer.send(items, over: c.channel, id: id, control: control) { throttle($0, $1, $2) }
                try? await c.channel.send(json: ["t": "bye"])
                logSent(items)
                setActivity(.sent(count: items.count), resetAfter: 2.2)
                prefs.play("Pop")
                Transfer.removeTemporaries(items)
                sendNextQueued(after: 0.8)
            } catch {
                queue = [] // a failed or stopped send would fail the rest the same way
                guard !control.isCancelled else { return } // already showing "cancelled"
                if case NDError.cancelled(let m) = error {
                    setActivity(.cancelled(m), resetAfter: 4)
                } else if !prepared.isEmpty {
                    // Kept until Resume or OK; never retried on its own.
                    resumable = PendingSend(items: prepared, id: id, sentAny: sent.value)
                    setActivity(.failed(failureMessage(error)))
                } else {
                    setActivity(.failed(failureMessage(error)), resetAfter: 5)
                }
                prefs.play("Funk")
            }
        }
    }

    private func logSent(_ items: [OutgoingItem]) {
        var files: [(url: URL?, name: String, size: Int64?)] = [], texts: [String] = []
        let tmp = Transfer.tempDirectory.standardizedFileURL.path + "/"
        for item in items {
            switch item {
            // Zips and copies made for the send are deleted afterwards, so they're logged without a path.
            case .file(let url, let name, let size, _):
                files.append((url.standardizedFileURL.path.hasPrefix(tmp) ? nil : url, name, size))
            case .text(let s): texts.append(s)
            }
        }
        history.log(incoming: false, files: files, texts: texts)
    }

    private func fileSize(_ url: URL) -> Int64? {
        (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize).flatMap { $0.map(Int64.init) }
    }

    /// Sends a history item again, if its file is still there.
    func resend(_ e: HistoryEntry) {
        if let url = e.url, e.fileExists { send(urls: [url], texts: []) }
        else if let t = e.text { send(urls: [], texts: [t]) }
        else { NSSound.beep() }
    }

    private func sendNextQueued(after delay: TimeInterval) {
        guard !queue.isEmpty else { return }
        DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak self] in
            guard let self, !self.isBusy, !self.queue.isEmpty else { return }
            let next = self.queue.removeFirst()
            self.send(urls: next.urls, texts: next.texts)
        }
    }

    /// Items handed over by the Share menu extension, which can't reach the phone itself: it leaves
    /// them on a private pasteboard and opens `notchdrop://share`.
    func sendShared() {
        let pb = NSPasteboard(name: ShareHandoff.pasteboard)
        var urls: [URL] = [], texts: [String] = []
        let dir = Transfer.tempDirectory.appendingPathComponent("shared-\(UUID().uuidString)", isDirectory: true)
        for item in pb.pasteboardItems ?? [] {
            if let s = item.string(forType: .fileURL), let url = URL(string: s), url.isFileURL {
                urls.append(url)
            } else if let data = item.data(forType: ShareHandoff.dataType) {
                let name = item.string(forType: ShareHandoff.nameType).flatMap { ($0 as NSString).lastPathComponent.trimmed } ?? "Shared item"
                try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
                let dest = dir.appendingPathComponent(name)
                if (try? data.write(to: dest)) != nil { urls.append(dest) }
            } else if let s = item.string(forType: .string), !s.isEmpty {
                texts.append(s)
            }
        }
        pb.clearContents()
        guard !(urls.isEmpty && texts.isEmpty) else { return }
        guard store.peer != nil else { NSSound.beep(); return }
        send(urls: urls, texts: texts)
    }

    /// Stops the transfer in progress, in either direction.
    func cancelTransfer() {
        guard let t = transfer, !t.isCancelled else { return }
        t.cancel() // kept as `transfer` until it winds down, so its late progress is ignored
        setActivity(.cancelled(TransferControl.youCancelled), resetAfter: 2.5)
    }

    private var cancelNote: String { "Stopped on \(store.deviceName)." }

    func sendClipboard() {
        send(SelectionGrabber.read(.general))
    }

    func send(pasteboard: NSPasteboard) {
        send(SelectionGrabber.read(pasteboard))
    }

    /// Hotkey action: Finder selection, else the front app's selected text, else the clipboard.
    func sendSelection() {
        guard store.peer != nil else { NSSound.beep(); return }
        Task { send(await SelectionGrabber.grab()) }
    }

    private func send(_ g: SelectionGrabber.Grab) {
        switch g {
        case .files(let urls): send(urls: urls, texts: [])
        case .texts(let texts): send(urls: [], texts: texts)
        case .nothing: NSSound.beep()
        }
    }

    private func failureMessage(_ error: Error) -> String {
        if case NDError.unreachable = error {
            return "Can't reach \(peerName). Is NotchDrop running on it, on the same Wi-Fi or hotspot?"
        }
        return error.localizedDescription
    }

    // MARK: Receiving (called by Bridge on main)

    fileprivate func incomingStarted(_ control: TransferControl) {
        transfer = control
    }

    fileprivate func incomingProgress(done: Int64, total: Int64, name: String) {
        guard transfer?.isCancelled != true else { return }
        let f = total > 0 ? Double(done) / Double(total) : 1
        setActivity(.receiving(name: name, fraction: f))
        speed = meter.update(done: done, total: total)
    }

    fileprivate func incomingFinished(_ batch: ReceivedBatch) {
        transfer = nil
        if let text = batch.texts.last, prefs.copyText {
            NSPasteboard.general.clearContents()
            NSPasteboard.general.setString(text, forType: .string)
        }
        if prefs.openLinks {
            for t in batch.texts {
                if let url = URL(string: t.trimmingCharacters(in: .whitespacesAndNewlines)), url.scheme?.hasPrefix("http") == true {
                    NSWorkspace.shared.open(url)
                }
            }
        }
        recents.add(files: batch.files, texts: batch.texts)
        history.log(incoming: true, files: batch.files.map { ($0, $0.lastPathComponent, fileSize($0)) }, texts: batch.texts)
        if prefs.notify || !prefs.notchEnabled { Notifier.shared.received(batch, from: peerName) }
        setActivity(.received(files: batch.files, texts: batch.texts), resetAfter: batch.files.isEmpty ? 6 : 10)
        prefs.play("Glass")
        sendNextQueued(after: 1.5)
    }

    /// Shows Accept/Decline in the notch (or an alert when the notch is off). Declines after 45 s.
    fileprivate func confirmIncoming(_ summary: String) async -> Bool {
        guard prefs.confirmReceive else { return true }
        pendingConfirm?.resume(returning: false)
        if !prefs.notchEnabled { return confirmWithAlert(summary) }
        prefs.play("Tink")
        setActivity(.incoming(summary: summary))
        let token = resetToken
        Task {
            try? await Task.sleep(nanoseconds: 45_000_000_000)
            if token == resetToken { answerIncoming(false) }
        }
        return await withCheckedContinuation { pendingConfirm = $0 }
    }

    func answerIncoming(_ accept: Bool) {
        guard let c = pendingConfirm else { return }
        pendingConfirm = nil
        setActivity(accept ? .receiving(name: "", fraction: 0) : .idle)
        c.resume(returning: accept)
    }

    private func confirmWithAlert(_ summary: String) -> Bool {
        let alert = NSAlert()
        alert.messageText = "\(peerName) wants to send \(summary)"
        alert.addButton(withTitle: "Accept")
        alert.addButton(withTitle: "Decline")
        let timer = Timer(timeInterval: 45, repeats: false) { _ in NSApp.abortModal() }
        RunLoop.main.add(timer, forMode: .modalPanel)
        NSApp.activate(ignoringOtherApps: true)
        defer { timer.invalidate() }
        return alert.runModal() == .alertFirstButtonReturn
    }

    fileprivate func incomingFailed(_ error: Error) {
        transfer = nil
        if case NDError.cancelled(let m) = error {
            // Our own cancel is already on screen.
            if m != TransferControl.youCancelled { setActivity(.cancelled(m), resetAfter: 4) }
            return
        }
        setActivity(.failed(error.localizedDescription), resetAfter: 5)
        prefs.play("Funk")
        sendNextQueued(after: 1.5)
    }

    func dismissActivity() {
        if case .failed = activity { resumable = nil }
        setActivity(.idle)
    }

    /// ND_DEMO=transfer: a fake 8-second receive, for checking the full → sliver → done sequence.
    func demoTransfer() {
        Task {
            for i in 0...100 {
                setActivity(.receiving(name: "Holiday video.mov", fraction: Double(i) / 100))
                try? await Task.sleep(nanoseconds: 80_000_000)
            }
            setActivity(.received(files: [], texts: ["Holiday video.mov"]), resetAfter: 6)
        }
    }

    private func setActivity(_ a: Activity, resetAfter: TimeInterval? = nil) {
        if !a.isTransferring, speed != nil || meter.running {
            speed = nil
            meter = RateMeter()
        }
        activity = a
        resetToken += 1
        guard let resetAfter else { return }
        let token = resetToken
        Task {
            try? await Task.sleep(nanoseconds: UInt64(resetAfter * 1_000_000_000))
            while token == resetToken, holdActivity { try? await Task.sleep(nanoseconds: 500_000_000) }
            if token == resetToken { activity = .idle }
        }
    }
}

/// Receives server callbacks on background threads and hops to the main actor.
private final class Bridge: SessionDelegate, @unchecked Sendable {
    weak var model: AppModel?
    let store: Store
    init(model: AppModel, store: Store) { self.model = model; self.store = store }

    var myId: String { store.deviceId }

    func keyFor(mode: String, from: String) -> Data? { store.key(mode: mode, from: from) }

    func myHello() -> [String: Any] {
        ["t": "hello", "id": store.deviceId, "name": store.deviceName, "platform": "mac",
         "addrs": NetUtil.ipv4Addresses(), "port": Int(Wire.macPort)]
    }

    func didReceiveHello(_ hello: [String: Any], mode: String, remoteHost: String?) {
        let id = hello["id"] as? String ?? ""
        let name = hello["name"] as? String ?? "Phone"
        let addrs = hello["addrs"] as? [String] ?? []
        let port = (hello["port"] as? NSNumber).map { UInt16(truncating: $0) } ?? Wire.phonePort
        if mode == "pair", let key = store.pendingPairKey {
            let p = PairedPeer(id: id, name: name, key: key, addrs: addrs, port: port)
            Task { @MainActor in self.model?.didPair(p, host: remoteHost) }
        } else {
            store.updatePeerAddresses(addrs, port: port, name: name)
            Task { @MainActor in self.model?.markReachable(host: remoteHost) }
        }
    }

    func saveDirectory() -> URL { store.receiveDirectory }

    func incomingProgress(done: Int64, total: Int64, name: String) {
        Task { @MainActor in self.model?.incomingProgress(done: done, total: total, name: name) }
    }

    func incomingFinished(_ batch: ReceivedBatch) {
        Task { @MainActor in self.model?.incomingFinished(batch) }
    }

    func incomingFailed(_ error: Error) {
        Task { @MainActor in self.model?.incomingFailed(error) }
    }

    func incomingStarted(_ control: TransferControl) {
        Task { @MainActor in self.model?.incomingStarted(control) }
    }

    func shouldAccept(offer: [String: Any]) async -> Bool {
        guard let model else { return false }
        return await model.confirmIncoming(Self.summary(offer))
    }

    /// "photo.jpg", "3 files · 12 MB", or "a link".
    static func summary(_ offer: [String: Any]) -> String {
        let items = offer["items"] as? [[String: Any]] ?? []
        let files = items.filter { $0["kind"] as? String == "file" }
        let size = files.reduce(Int64(0)) { $0 + ((($1["size"] as? NSNumber)?.int64Value) ?? 0) }
        let bytes = ByteCountFormatter.string(fromByteCount: size, countStyle: .file)
        switch (files.count, items.count - files.count) {
        case (0, 1): return "a link or text"
        case (0, let t): return "\(t) texts"
        case (1, 0): return "\(files[0]["name"] as? String ?? "a file") (\(bytes))"
        default: return "\(items.count) items · \(bytes)"
        }
    }
}

/// Bonjour browser that maps NotchDrop device ids to their service endpoints.
@MainActor
final class PeerBrowser {
    private var browser: NWBrowser?
    private var map: [String: [NWEndpoint]] = [:]
    var onChange: ((String) -> Void)?

    func start() {
        let b = NWBrowser(for: .bonjourWithTXTRecord(type: Wire.serviceType, domain: nil), using: .tcp)
        b.browseResultsChangedHandler = { [weak self] results, _ in
            var m: [String: [NWEndpoint]] = [:]
            for r in results {
                if case .bonjour(let txt) = r.metadata, let id = txt["id"], txt["role"] == "phone" {
                    m[id, default: []].append(r.endpoint)
                }
            }
            Task { @MainActor in
                guard let self else { return }
                let added = Set(m.keys).subtracting(self.map.keys)
                self.map = m
                added.forEach { self.onChange?($0) }
            }
        }
        b.stateUpdateHandler = { state in
            if case .failed(let e) = state { NSLog("NotchDrop: browser failed \(e)") }
        }
        b.start(queue: .main)
        browser = b
    }

    func endpoints(for id: String) -> [NWEndpoint] { map[id] ?? [] }
}

/// A failed send kept so it can be resumed: the same `id` lets the phone answer with what it already has.
struct PendingSend {
    let items: [OutgoingItem]
    let id: String
    /// False when nothing got through, so the button says Retry rather than Resume.
    let sentAny: Bool
}

/// Whether any new bytes went out, from the progress callback; read when the send fails.
private final class SentFlag: @unchecked Sendable {
    private let lock = NSLock()
    private var _value: Bool
    private var first = true
    init(_ v: Bool) { _value = v }
    var value: Bool { lock.withLock { _value } }

    func report(_ done: Int64) {
        lock.withLock {
            // The first report is where a resume starts, not new bytes.
            if !first, done > 0 { _value = true }
            first = false
        }
    }
}

/// Smoothed transfer speed and time left, from progress callbacks.
struct RateMeter {
    private var lastTime: Date?
    private var lastDone: Int64 = 0
    private var started: Date?
    private var rate: Double = 0
    var running: Bool { started != nil }

    /// nil for the first second, while the speed is still settling.
    mutating func update(done: Int64, total: Int64) -> String? {
        let now = Date()
        guard let t = lastTime else {
            lastTime = now; lastDone = done; started = now
            return nil
        }
        let dt = now.timeIntervalSince(t)
        if dt >= 0.25 {
            let inst = Double(max(0, done - lastDone)) / dt
            rate = rate == 0 ? inst : rate * 0.75 + inst * 0.25
            lastTime = now
            lastDone = done
        }
        guard let started, now.timeIntervalSince(started) >= 1, rate > 0, done < total else { return nil }
        let speed = ByteCountFormatter.string(fromByteCount: Int64(rate), countStyle: .file) + "/s"
        return "\(speed) · \(Self.eta(Double(total - done) / rate)) left"
    }

    private static func eta(_ s: Double) -> String {
        if s < 60 { return "\(max(1, Int(s.rounded()))) s" }
        if s < 3600 { return "\(Int((s / 60).rounded())) min" }
        return String(format: "%.1f h", s / 3600)
    }
}

/// How the Share menu extension hands items to the app. Must match `mac/ShareExtension`.
enum ShareHandoff {
    static let pasteboard = NSPasteboard.Name("com.amurot.notchdrop.share")
    static let dataType = NSPasteboard.PasteboardType("com.amurot.notchdrop.data")
    static let nameType = NSPasteboard.PasteboardType("com.amurot.notchdrop.name")
}
