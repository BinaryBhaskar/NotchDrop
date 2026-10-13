import AppKit
import Network

enum Activity: Equatable {
    case idle
    case connecting(count: Int)
    case sending(name: String, fraction: Double, count: Int)
    case sent(count: Int)
    case receiving(name: String, fraction: Double)
    case received(files: [URL], texts: [String])
    case failed(String)
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
    @Published private(set) var pairing: PairingInfo?
    @Published private(set) var justPaired = false

    let store = Store.shared
    private let browser = PeerBrowser()
    private var server: ProtocolServer?
    private var bridge: Bridge?
    private var pathMonitor: NWPathMonitor?
    private var pingTimer: Timer?
    private var resetToken = 0
    private var pinging = false

    var peerName: String { peer?.displayName ?? "Phone" }
    var isBusy: Bool {
        switch activity {
        case .connecting, .sending, .receiving: return true
        default: return false
        }
    }

    // MARK: Lifecycle

    func start() {
        peer = store.peer
        let bridge = Bridge(model: self, store: store)
        self.bridge = bridge
        let server = ProtocolServer(port: Wire.macPort, advertise: .init(
            name: store.deviceName, txt: ["id": store.deviceId, "role": "mac"]))
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
        guard store.peer != nil, !isBusy, !(urls.isEmpty && texts.isEmpty) else { return }
        let count = urls.count + texts.count
        setActivity(.connecting(count: count))
        Task {
            do {
                let items = try await Task.detached { try Transfer.prepare(urls: urls, texts: texts) }.value
                guard !items.isEmpty else { setActivity(.idle); return }
                guard let c = await connect() else { throw NDError.unreachable }
                defer { c.channel.close() }
                let throttle = ProgressThrottle { done, total, name in
                    let f = total > 0 ? Double(done) / Double(total) : 1
                    Task { @MainActor in self.setActivity(.sending(name: name, fraction: f, count: items.count)) }
                }
                setActivity(.sending(name: "", fraction: 0, count: items.count))
                try await Transfer.send(items, over: c.channel) { throttle($0, $1, $2) }
                try? await c.channel.send(json: ["t": "bye"])
                setActivity(.sent(count: items.count), resetAfter: 2.2)
            } catch {
                setActivity(.failed(failureMessage(error)), resetAfter: 4)
            }
        }
    }

    func sendClipboard() {
        let pb = NSPasteboard.general
        if let urls = pb.readObjects(forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true]) as? [URL], !urls.isEmpty {
            send(urls: urls, texts: [])
        } else if let s = pb.string(forType: .string), !s.isEmpty {
            send(urls: [], texts: [s])
        }
    }

    private func failureMessage(_ error: Error) -> String {
        if case NDError.unreachable = error {
            return "Can't reach \(peerName). Is NotchDrop running on it, on the same Wi-Fi or hotspot?"
        }
        return error.localizedDescription
    }

    // MARK: Receiving (called by Bridge on main)

    fileprivate func incomingProgress(_ f: Double, name: String) {
        setActivity(.receiving(name: name, fraction: f))
    }

    fileprivate func incomingFinished(_ batch: ReceivedBatch) {
        if let text = batch.texts.last {
            NSPasteboard.general.clearContents()
            NSPasteboard.general.setString(text, forType: .string)
        }
        setActivity(.received(files: batch.files, texts: batch.texts), resetAfter: 6)
    }

    fileprivate func incomingFailed(_ error: Error) {
        setActivity(.failed("Receive failed: \(error.localizedDescription)"), resetAfter: 4)
    }

    func dismissActivity() { setActivity(.idle) }

    private func setActivity(_ a: Activity, resetAfter: TimeInterval? = nil) {
        activity = a
        resetToken += 1
        guard let resetAfter else { return }
        let token = resetToken
        Task {
            try? await Task.sleep(nanoseconds: UInt64(resetAfter * 1_000_000_000))
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
        let f = total > 0 ? Double(done) / Double(total) : 1
        Task { @MainActor in self.model?.incomingProgress(f, name: name) }
    }

    func incomingFinished(_ batch: ReceivedBatch) {
        Task { @MainActor in self.model?.incomingFinished(batch) }
    }

    func incomingFailed(_ error: Error) {
        Task { @MainActor in self.model?.incomingFailed(error) }
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
