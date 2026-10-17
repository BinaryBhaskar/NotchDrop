import CryptoKit
import Foundation
import Network

/// `NotchDrop --selftest`: runs a server and a client over loopback and sends a file + text through the full protocol.
/// `NotchDrop --vectors`: prints crypto test vectors that the Android unit tests assert against.
enum SelfTest {
    final class Sink: SessionDelegate {
        let myId = "server-id"
        let key: Data
        let dir: URL
        var finished: ((ReceivedBatch) -> Void)?
        var failed: ((Error) -> Void)?
        /// Cancels the incoming transfer once this many bytes have arrived.
        var cancelAfter: Int64?
        /// Where the latest batch's progress started; above 0 when it resumed.
        var start: Int64?
        private var control: TransferControl?
        init(key: Data, dir: URL) { self.key = key; self.dir = dir }
        func keyFor(mode: String, from: String) -> Data? { from == "client-id" ? key : nil }
        func myHello() -> [String: Any] { ["t": "hello", "id": myId, "name": "Server", "platform": "mac", "addrs": [], "port": 0] }
        func didReceiveHello(_ hello: [String: Any], mode: String, remoteHost: String?) {}
        func saveDirectory() -> URL { dir }
        func incomingStarted(_ control: TransferControl) { self.control = control; start = nil }
        func incomingProgress(done: Int64, total: Int64, name: String) {
            if start == nil { start = done }
            if let n = cancelAfter, done >= n { control?.cancel() }
        }
        func incomingFinished(_ batch: ReceivedBatch) { finished?(batch) }
        func incomingFailed(_ error: Error) { failed?(error) }
    }

    static func run() async -> Int32 {
        let versions: [(String, String, Bool)] = [("v1.2.0", "1.1.1", true), ("1.10.0", "1.9.9", true), ("1.1.1", "1.1.1", false),
                                                  ("1.1", "1.1.0", false), ("v1.0.9", "1.1.0", false), ("2.0.0-beta", "1.9", true)]
        for (a, b, want) in versions where Updater.isNewer(a, than: b) != want {
            print("FAIL: isNewer(\(a), \(b)) != \(want)"); return 1
        }
        let tmp = FileManager.default.temporaryDirectory.appendingPathComponent("nd-selftest-\(UUID().uuidString)")
        let inDir = tmp.appendingPathComponent("in")
        try? FileManager.default.createDirectory(at: tmp, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tmp) }
        if let problem = resumeStoreSurvivesRestart(in: tmp) { print("FAIL: resume store: \(problem)"); return 1 }

        let key = randomBytes(32)
        let sink = Sink(key: key, dir: inDir)
        let server = ProtocolServer(port: 0, advertise: nil)
        server.delegate = sink
        do {
            let port = try await server.start()
            print("server on port \(port)")

            // 3 MB of random data exercises multi-chunk transfer.
            let src = tmp.appendingPathComponent("photo.jpg")
            let payload = randomBytes(3 * 1024 * 1024 + 123)
            try payload.write(to: src)
            let folder = tmp.appendingPathComponent("Folder")
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            try Data("hello".utf8).write(to: folder.appendingPathComponent("a.txt"))

            let ep = NWEndpoint.hostPort(host: "127.0.0.1", port: NWEndpoint.Port(rawValue: port)!)
            let hello: [String: Any] = ["t": "hello", "id": "client-id", "name": "Client", "platform": "mac", "addrs": [], "port": 0]

            // Wrong key must be refused.
            if await PeerLink.connect(candidates: [ep], mode: "session", key: randomBytes(32), myId: "client-id", hello: hello) != nil {
                print("FAIL: wrong key accepted"); return 1
            }
            // Unknown device must be refused.
            if await PeerLink.connect(candidates: [ep], mode: "session", key: key, myId: "stranger", hello: hello) != nil {
                print("FAIL: unknown device accepted"); return 1
            }
            // Unreachable candidate + good candidate: race must pick the good one.
            let dead = NWEndpoint.hostPort(host: "127.0.0.1", port: 1)
            guard let c = await PeerLink.connect(candidates: [dead, ep], mode: "session", key: key, myId: "client-id", hello: hello) else {
                print("FAIL: could not connect"); return 1
            }
            print("connected, remote hello: \(c.hello["name"] ?? "?")")

            let received: ReceivedBatch = try await withTimeout(20) {
                try await withCheckedThrowingContinuation { cont in
                    let once = Once()
                    sink.finished = { b in if once.claim() { cont.resume(returning: b) } }
                    sink.failed = { e in if once.claim() { cont.resume(throwing: e) } }
                    Task {
                        do {
                            let items = try Transfer.prepare(urls: [src, folder], texts: ["https://example.com"])
                            try await Transfer.send(items, over: c.channel) { _, _, _ in }
                            try await c.channel.send(json: ["t": "bye"])
                        } catch { if once.claim() { cont.resume(throwing: error) } }
                    }
                }
            }
            c.channel.close()

            let names = received.files.map(\.lastPathComponent).sorted()
            print("received files: \(names), texts: \(received.texts)")
            guard names == ["Folder.zip", "photo.jpg"], received.texts == ["https://example.com"],
                  try Data(contentsOf: inDir.appendingPathComponent("photo.jpg")) == payload
            else { print("FAIL: content mismatch"); return 1 }
            print("PASS")
            return 0
        } catch {
            print("FAIL: \(error)")
            return 1
        }
    }

    /// A kept batch reloads after a restart; partial files nothing needs are deleted; another offer drops the batch.
    private static func resumeStoreSurvivesRestart(in tmp: URL) -> String? {
        let fm = FileManager.default
        let dir = tmp.appendingPathComponent("resume")
        try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
        let record = dir.appendingPathComponent("resume.json")
        let kept = dir.appendingPathComponent(".kept\(ResumeStore.partSuffix)")
        let crashed = dir.appendingPathComponent(".crashed\(ResumeStore.partSuffix)")
        let untracked = dir.appendingPathComponent(".old\(ResumeStore.partSuffix)")
        for f in [kept, crashed, untracked] { fm.createFile(atPath: f.path, contents: Data(count: 100)) }
        let sig = ["file:a.bin:1000", "text"]

        let first = ResumeStore(file: record)
        first.track(kept)
        first.track(crashed)
        let b = PartialBatch(id: "batch-1", signature: sig)
        b.parts[0] = (kept, 100)
        first.keep(b)

        let second = ResumeStore(file: record)
        second.cleanUp(folders: [dir])
        if fm.fileExists(atPath: crashed.path) || fm.fileExists(atPath: untracked.path) { return "strays not deleted" }
        guard fm.fileExists(atPath: kept.path) else { return "kept file deleted" }
        guard let back = ResumeStore(file: record).take(id: "batch-1", signature: sig), back.offset(for: 0) == 100
        else { return "batch not reloaded" }
        // Taken means in use: no longer offered, but still tracked in case this attempt crashes too.
        if ResumeStore(file: record).take(id: "batch-1", signature: sig) != nil { return "batch offered twice" }
        guard fm.fileExists(atPath: kept.path) else { return "in-use file deleted" }

        let third = ResumeStore(file: record)
        third.keep(back)
        if ResumeStore(file: record).take(id: "batch-2", signature: sig) != nil || fm.fileExists(atPath: kept.path) {
            return "other offer didn't drop the batch"
        }
        return nil
    }

    /// `--interop-server <keyhex> [cancel-after-bytes|-] [resume]`: accepts one batch from "client-id" and prints it, for
    /// the Android JVM tests. With `resume`, a failed batch is kept and the server waits for one more attempt.
    static func interopServer(keyHex: String, cancelAfter: Int64? = nil, resumable: Bool = false) async -> Int32 {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("nd-interop-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: dir) }
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let sink = Sink(key: Data(hex: keyHex), dir: dir)
        sink.cancelAfter = cancelAfter
        let server = ProtocolServer(port: 0, advertise: nil, resume: ResumeStore(file: dir.appendingPathComponent("resume.json")))
        server.delegate = sink
        do {
            let port = try await server.start()
            print("port=\(port)"); fflush(stdout)
            var attempts = resumable ? 2 : 1
            while true {
                attempts -= 1
                do {
                    let batch: ReceivedBatch = try await withTimeout(45) {
                        try await withCheckedThrowingContinuation { cont in
                            let once = Once()
                            sink.finished = { b in if once.claim() { cont.resume(returning: b) } }
                            sink.failed = { e in if once.claim() { cont.resume(throwing: e) } }
                        }
                    }
                    if let s = sink.start, s > 0 { print("start=\(s)") }
                    for f in batch.files {
                        let d = try Data(contentsOf: f)
                        print("file=\(f.lastPathComponent) sha=\(Transfer.hex(Array(SHA256.hash(data: d))))")
                    }
                    for t in batch.texts { print("text=\(t)") }
                    print("DONE"); fflush(stdout)
                    return 0
                } catch where attempts > 0 {
                    print("FAIL: \(error)"); fflush(stdout)
                }
            }
        } catch {
            print("FAIL: \(error)")
            return 1
        }
    }

    /// `--interop-send <port> <keyhex> <file> <text> [cancel-after-bytes|-] [batch-id] [drop-after-bytes]`: connects to
    /// 127.0.0.1 as "client-id" and sends a file and a text. Dropping closes the connection without a word, like Wi-Fi going away.
    static func interopSend(port: UInt16, keyHex: String, file: String, text: String, cancelAfter: Int64? = nil,
                            id: String? = nil, dropAfter: Int64? = nil) async -> Int32 {
        let ep = NWEndpoint.hostPort(host: "127.0.0.1", port: NWEndpoint.Port(rawValue: port)!)
        let hello: [String: Any] = ["t": "hello", "id": "client-id", "name": "Mac", "platform": "mac", "addrs": [], "port": 0]
        guard let c = await PeerLink.connect(candidates: [ep], mode: "session", key: Data(hex: keyHex), myId: "client-id", hello: hello)
        else { print("FAIL: connect"); return 1 }
        do {
            let items = try Transfer.prepare(urls: [URL(fileURLWithPath: file)], texts: [text])
            let control = TransferControl(note: "Stopped on Mac.")
            control.attach(c.channel, receiving: false)
            var start: Int64?
            try await Transfer.send(items, over: c.channel, id: id, control: control) { done, _, _ in
                if start == nil { start = done; if done > 0 { print("start=\(done)") } }
                if let n = cancelAfter, done >= n { control.cancel() }
                if let n = dropAfter, done >= n { c.channel.close() }
            }
            try await c.channel.send(json: ["t": "bye"])
            c.channel.close()
            print("SENT")
            return 0
        } catch {
            print("FAIL: \(error)")
            return 1
        }
    }

    static func vectors() {
        let master = Data((0..<32).map { UInt8($0) })
        let nc = Data((0..<32).map { UInt8(0x40 + $0) })
        let ns = Data((0..<32).map { UInt8(0x80 + $0) })
        let k = FrameCipher.derive(master: master, clientNonce: nc, serverNonce: ns)
        var c = FrameCipher(key: k.c2s)
        _ = try! c.seal(Data("first".utf8))
        let second = try! c.seal(Data("hello notchdrop".utf8))
        print("c2s=\(k.c2s.withUnsafeBytes { Transfer.hex(Array($0)) })")
        print("s2c=\(k.s2c.withUnsafeBytes { Transfer.hex(Array($0)) })")
        print("sealed#1=\(Transfer.hex(second))")
    }
}

extension Data {
    init(hex: String) {
        var bytes: [UInt8] = []
        var i = hex.startIndex
        while i < hex.endIndex, let j = hex.index(i, offsetBy: 2, limitedBy: hex.endIndex) {
            bytes.append(UInt8(hex[i..<j], radix: 16) ?? 0)
            i = j
        }
        self.init(bytes)
    }
}
