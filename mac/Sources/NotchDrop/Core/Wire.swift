import CryptoKit
import Foundation
import Network

/// Wire protocol shared with the Android app. See PROTOCOL.md at the repo root.
enum Wire {
    static let version = 1
    static let macPort: UInt16 = 47820
    static let phonePort: UInt16 = 47821
    static let serviceType = "_notchdrop._tcp"
    static let maxFrame = 4 * 1024 * 1024
    static let chunkSize = 256 * 1024
    /// A transfer that makes no progress for this long has lost its connection.
    static let stallTimeout: TimeInterval = 20

    /// Sent by either side to stop a running transfer.
    static func cancelFrame(_ note: String) -> [String: Any] {
        ["t": "error", "message": note, "cancelled": true]
    }
}

enum NDError: LocalizedError {
    case closed
    case timeout
    case badFrame(String)
    case rejected(String)
    case unreachable
    case cancelled(String)
    case connectionLost

    var errorDescription: String? {
        switch self {
        case .closed: return "Connection closed"
        case .timeout: return "Timed out"
        case .badFrame(let s): return "Protocol error: \(s)"
        case .rejected(let s): return s
        case .unreachable: return "Phone not reachable"
        case .cancelled(let s): return s
        case .connectionLost: return "Connection lost. Check that both devices are still on the same network."
        }
    }
}

func withTimeout<T>(_ seconds: TimeInterval, _ op: @escaping () async throws -> T) async throws -> T {
    try await withThrowingTaskGroup(of: T.self) { group in
        group.addTask { try await op() }
        group.addTask {
            try await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
            throw NDError.timeout
        }
        defer { group.cancelAll() }
        return try await group.next()!
    }
}

func randomBytes(_ n: Int) -> Data {
    var d = Data(count: n)
    _ = d.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, n, $0.baseAddress!) }
    return d
}

// MARK: - Length-prefixed frames over a TCP NWConnection

final class FrameConn: @unchecked Sendable {
    let conn: NWConnection
    private let queue = DispatchQueue(label: "notchdrop.conn")

    init(_ conn: NWConnection) { self.conn = conn }

    var remoteHost: String? {
        if case .hostPort(let host, _) = conn.currentPath?.remoteEndpoint {
            return host.debugDescription.components(separatedBy: "%").first
        }
        return nil
    }

    func open(timeout: TimeInterval) async throws {
        let conn = self.conn
        let lock = NSLock()
        var done = false
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
                let finish: (Error?) -> Void = { err in
                    lock.lock()
                    let first = !done
                    done = true
                    lock.unlock()
                    guard first else { return }
                    if let err { cont.resume(throwing: err) } else { cont.resume() }
                }
                conn.stateUpdateHandler = { state in
                    switch state {
                    case .ready: finish(nil)
                    // Also after `.ready`: cancelling wakes any pending read or write when the network goes away.
                    case .failed(let e): finish(e); conn.cancel()
                    case .waiting(let e): finish(e); conn.cancel()
                    case .cancelled: finish(NDError.closed)
                    default: break
                    }
                }
                conn.start(queue: queue)
                queue.asyncAfter(deadline: .now() + timeout) {
                    lock.lock()
                    let pending = !done
                    lock.unlock()
                    if pending { finish(NDError.timeout); conn.cancel() }
                }
            }
        } onCancel: {
            conn.cancel()
        }
    }

    func write(_ data: Data) async throws {
        let conn = self.conn
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
                conn.send(content: data, completion: .contentProcessed { err in
                    if let err { cont.resume(throwing: err) } else { cont.resume() }
                })
            }
        } onCancel: {
            conn.cancel()
        }
    }

    func read(_ n: Int) async throws -> Data {
        let conn = self.conn
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Data, Error>) in
                conn.receive(minimumIncompleteLength: n, maximumLength: n) { data, _, _, err in
                    if let err { cont.resume(throwing: err); return }
                    if let data, data.count == n { cont.resume(returning: data); return }
                    cont.resume(throwing: NDError.closed)
                }
            }
        } onCancel: {
            conn.cancel()
        }
    }

    func writeFrame(_ payload: Data) async throws {
        let n = UInt32(payload.count)
        var d = Data([UInt8(n >> 24), UInt8((n >> 16) & 0xff), UInt8((n >> 8) & 0xff), UInt8(n & 0xff)])
        d.append(payload)
        try await write(d)
    }

    func readFrame() async throws -> Data {
        let h = [UInt8](try await read(4))
        let n = Int(h[0]) << 24 | Int(h[1]) << 16 | Int(h[2]) << 8 | Int(h[3])
        guard n > 0, n <= Wire.maxFrame else { throw NDError.badFrame("frame size \(n)") }
        return try await read(n)
    }

    func close() { conn.cancel() }
}

// MARK: - AES-256-GCM with per-direction counter nonces

struct FrameCipher {
    let key: SymmetricKey
    private var counter: UInt64 = 0

    init(key: SymmetricKey) { self.key = key }

    static func nonce(_ counter: UInt64) -> Data {
        var d = Data(count: 4)
        withUnsafeBytes(of: counter.bigEndian) { d.append(contentsOf: $0) }
        return d
    }

    mutating func seal(_ plain: Data) throws -> Data {
        let box = try AES.GCM.seal(plain, using: key, nonce: AES.GCM.Nonce(data: Self.nonce(counter)))
        counter += 1
        return box.ciphertext + box.tag
    }

    mutating func open(_ sealed: Data) throws -> Data {
        guard sealed.count >= 16 else { throw NDError.badFrame("short frame") }
        let box = try AES.GCM.SealedBox(
            nonce: AES.GCM.Nonce(data: Self.nonce(counter)),
            ciphertext: sealed.prefix(sealed.count - 16),
            tag: sealed.suffix(16))
        let plain = try AES.GCM.open(box, using: key)
        counter += 1
        return plain
    }

    /// HKDF-SHA256(master, salt = clientNonce || serverNonce) -> (client->server, server->client)
    static func derive(master: Data, clientNonce: Data, serverNonce: Data) -> (c2s: SymmetricKey, s2c: SymmetricKey) {
        let ikm = SymmetricKey(data: master)
        let salt = clientNonce + serverNonce
        let c2s = HKDF<SHA256>.deriveKey(inputKeyMaterial: ikm, salt: salt, info: Data("notchdrop c2s".utf8), outputByteCount: 32)
        let s2c = HKDF<SHA256>.deriveKey(inputKeyMaterial: ikm, salt: salt, info: Data("notchdrop s2c".utf8), outputByteCount: 32)
        return (c2s, s2c)
    }
}

// MARK: - Encrypted channel

enum WireMessage {
    case json([String: Any])
    case chunk(Data)
}

final class SecureChannel: @unchecked Sendable {
    let conn: FrameConn
    private var tx: FrameCipher
    private var rx: FrameCipher

    init(conn: FrameConn, tx: SymmetricKey, rx: SymmetricKey) {
        self.conn = conn
        self.tx = FrameCipher(key: tx)
        self.rx = FrameCipher(key: rx)
    }

    func send(json: [String: Any]) async throws {
        var p = Data([1])
        p.append(try JSONSerialization.data(withJSONObject: json))
        try await conn.writeFrame(try tx.seal(p))
    }

    func send(chunk: Data) async throws {
        var p = Data([2])
        p.append(chunk)
        try await conn.writeFrame(try tx.seal(p))
    }

    func receive() async throws -> WireMessage {
        let frame = try await conn.readFrame()
        let p: Data
        do { p = try rx.open(frame) } catch { throw NDError.rejected("Authentication failed — re-pair devices") }
        guard let type = p.first else { throw NDError.badFrame("empty message") }
        let body = Data(p.dropFirst())
        switch type {
        case 1:
            guard let obj = try JSONSerialization.jsonObject(with: body) as? [String: Any] else {
                throw NDError.badFrame("bad json")
            }
            if obj["t"] as? String == "error" {
                let message = obj["message"] as? String ?? "Remote error"
                if obj["cancelled"] as? Bool == true { throw NDError.cancelled(message) }
                throw NDError.rejected(message)
            }
            return .json(obj)
        case 2:
            return .chunk(body)
        default:
            throw NDError.badFrame("type \(type)")
        }
    }

    func expect(_ t: String) async throws -> [String: Any] {
        guard case .json(let obj) = try await receive(), obj["t"] as? String == t else {
            throw NDError.badFrame("expected \(t)")
        }
        return obj
    }

    func close() { conn.close() }
}

// MARK: - Handshake

enum Handshake {
    static func client(_ conn: FrameConn, mode: String, myId: String, key: Data) async throws -> SecureChannel {
        let nc = randomBytes(32)
        let hello: [String: Any] = ["v": Wire.version, "mode": mode, "from": myId, "nonce": nc.base64EncodedString()]
        try await conn.writeFrame(try JSONSerialization.data(withJSONObject: hello))
        guard let resp = try JSONSerialization.jsonObject(with: try await conn.readFrame()) as? [String: Any] else {
            throw NDError.badFrame("bad handshake")
        }
        if let e = resp["error"] as? String { throw NDError.rejected(e) }
        guard let s = resp["nonce"] as? String, let ns = Data(base64Encoded: s), ns.count == 32 else {
            throw NDError.badFrame("bad nonce")
        }
        let keys = FrameCipher.derive(master: key, clientNonce: nc, serverNonce: ns)
        return SecureChannel(conn: conn, tx: keys.c2s, rx: keys.s2c)
    }

    /// `keyFor(mode, fromId)` returns the shared key to use, or nil to refuse.
    static func server(_ conn: FrameConn, myId: String, keyFor: (String, String) -> Data?) async throws
        -> (channel: SecureChannel, mode: String, from: String)
    {
        guard let req = try JSONSerialization.jsonObject(with: try await conn.readFrame()) as? [String: Any],
              let mode = req["mode"] as? String, let from = req["from"] as? String,
              let s = req["nonce"] as? String, let nc = Data(base64Encoded: s), nc.count == 32
        else { throw NDError.badFrame("bad handshake") }
        guard let key = keyFor(mode, from) else {
            let msg = mode == "pair" ? "Pairing window is not open on the Mac" : "Not paired with this Mac"
            try? await conn.writeFrame(try JSONSerialization.data(withJSONObject: ["error": msg]))
            throw NDError.rejected(msg)
        }
        let ns = randomBytes(32)
        let resp: [String: Any] = ["v": Wire.version, "from": myId, "nonce": ns.base64EncodedString()]
        try await conn.writeFrame(try JSONSerialization.data(withJSONObject: resp))
        let keys = FrameCipher.derive(master: key, clientNonce: nc, serverNonce: ns)
        return (SecureChannel(conn: conn, tx: keys.s2c, rx: keys.c2s), mode, from)
    }
}
