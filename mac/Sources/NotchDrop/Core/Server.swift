import Foundation
import Network

/// Callbacks from incoming sessions. Called off the main thread.
protocol SessionDelegate: AnyObject {
    var myId: String { get }
    func keyFor(mode: String, from: String) -> Data?
    func myHello() -> [String: Any]
    func didReceiveHello(_ hello: [String: Any], mode: String, remoteHost: String?)
    func saveDirectory() -> URL
    func incomingProgress(done: Int64, total: Int64, name: String)
    func incomingFinished(_ batch: ReceivedBatch)
    func incomingFailed(_ error: Error)
    /// Asked before each incoming offer is accepted.
    func shouldAccept(offer: [String: Any]) async -> Bool
}

extension SessionDelegate {
    func shouldAccept(offer: [String: Any]) async -> Bool { true }
}

final class ProtocolServer: @unchecked Sendable {
    struct Advertisement {
        var name: String
        var txt: [String: String]
    }

    private var listener: NWListener?
    private let requestedPort: NWEndpoint.Port
    private var advertisement: Advertisement?
    weak var delegate: SessionDelegate?

    init(port: UInt16, advertise: Advertisement?) {
        requestedPort = port == 0 ? .any : NWEndpoint.Port(rawValue: port)!
        advertisement = advertise
    }

    /// Starts listening and returns the bound port.
    @discardableResult
    func start() async throws -> UInt16 {
        let params = NWParameters.tcp
        params.allowLocalEndpointReuse = true
        let l = try NWListener(using: params, on: requestedPort)
        if let ad = advertisement {
            l.service = NWListener.Service(name: ad.name, type: Wire.serviceType, domain: nil, txtRecord: NWTXTRecord(ad.txt))
        }
        l.newConnectionHandler = { [weak self] conn in
            Task { await self?.handle(conn) }
        }
        listener = l
        return try await withCheckedThrowingContinuation { cont in
            let once = Once()
            l.stateUpdateHandler = { [weak self] state in
                switch state {
                case .ready:
                    if once.claim() { cont.resume(returning: l.port?.rawValue ?? 0) }
                case .failed(let e):
                    if once.claim() { cont.resume(throwing: e) }
                    NSLog("NotchDrop: listener failed: \(e); restarting")
                    l.cancel()
                    let weakSelf = self
                    DispatchQueue.global().asyncAfter(deadline: .now() + 2) {
                        Task { try? await weakSelf?.start() }
                    }
                default: break
                }
            }
            l.start(queue: .global(qos: .userInitiated))
        }
    }

    /// Re-registers the Bonjour service under a new name.
    func readvertise(name: String) {
        guard var ad = advertisement else { return }
        ad.name = name
        advertisement = ad
        listener?.service = NWListener.Service(name: ad.name, type: Wire.serviceType, domain: nil, txtRecord: NWTXTRecord(ad.txt))
    }

    func stop() {
        listener?.cancel()
        listener = nil
    }

    private func handle(_ nw: NWConnection) async {
        guard let d = delegate else { nw.cancel(); return }
        let fc = FrameConn(nw)
        var inTransfer = false
        do {
            try await fc.open(timeout: 5)
            let hs = try await withTimeout(10) {
                try await Handshake.server(fc, myId: d.myId, keyFor: { d.keyFor(mode: $0, from: $1) })
            }
            let ch = hs.channel
            let hello = try await withTimeout(10) { try await ch.expect("hello") }
            try await ch.send(json: d.myHello())
            d.didReceiveHello(hello, mode: hs.mode, remoteHost: fc.remoteHost)

            loop: while true {
                guard case .json(let msg) = try await ch.receive() else { throw NDError.badFrame("unexpected data") }
                switch msg["t"] as? String {
                case "bye":
                    break loop
                case "offer":
                    guard await d.shouldAccept(offer: msg) else {
                        try await ch.send(json: ["t": "error", "message": "Declined on the Mac"])
                        break loop
                    }
                    inTransfer = true
                    let throttle = ProgressThrottle { d.incomingProgress(done: $0, total: $1, name: $2) }
                    let batch = try await Transfer.receive(offer: msg, over: ch, saveDir: d.saveDirectory()) {
                        throttle($0, $1, $2)
                    }
                    inTransfer = false
                    d.incomingFinished(batch)
                case "ping":
                    try await ch.send(json: ["t": "pong"])
                default:
                    break
                }
            }
        } catch {
            if inTransfer { d.incomingFailed(error) }
            if case NDError.closed = error {} else { NSLog("NotchDrop: incoming session ended: \(error.localizedDescription)") }
        }
        fc.close()
    }
}

/// Outbound connections: try every known route to the peer at once, keep the first that authenticates.
enum PeerLink {
    struct Connected: @unchecked Sendable {
        let channel: SecureChannel
        let hello: [String: Any]
        let host: String?
    }

    static func connect(candidates: [NWEndpoint], mode: String, key: Data, myId: String,
                        hello: [String: Any], timeout: TimeInterval = 4) async -> Connected?
    {
        await withTaskGroup(of: Connected?.self) { group in
            for ep in candidates {
                group.addTask { try? await attempt(ep, mode: mode, key: key, myId: myId, hello: hello, timeout: timeout) }
            }
            var winner: Connected?
            for await r in group {
                guard let r else { continue }
                if winner == nil {
                    winner = r
                    group.cancelAll()
                } else {
                    r.channel.close()
                }
            }
            return winner
        }
    }

    static func attempt(_ ep: NWEndpoint, mode: String, key: Data, myId: String,
                        hello: [String: Any], timeout: TimeInterval) async throws -> Connected
    {
        let params = NWParameters.tcp
        if let tcp = params.defaultProtocolStack.transportProtocol as? NWProtocolTCP.Options {
            tcp.connectionTimeout = Int(timeout)
            tcp.enableKeepalive = true
        }
        let fc = FrameConn(NWConnection(to: ep, using: params))
        do {
            try await fc.open(timeout: timeout)
            return try await withTimeout(timeout + 4) {
                let ch = try await Handshake.client(fc, mode: mode, myId: myId, key: key)
                try await ch.send(json: hello)
                let reply = try await ch.expect("hello")
                return Connected(channel: ch, hello: reply, host: fc.remoteHost)
            }
        } catch {
            fc.close()
            throw error
        }
    }
}

/// Thread-safe "first caller wins" flag for resuming continuations exactly once.
final class Once: @unchecked Sendable {
    private let lock = NSLock()
    private var done = false

    func claim() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        if done { return false }
        done = true
        return true
    }
}
