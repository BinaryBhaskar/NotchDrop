import Foundation

struct PairedPeer: Codable, Equatable {
    var id: String
    var name: String
    var key: Data
    var addrs: [String]
    var port: UInt16
    /// Nickname chosen on this Mac; survives the phone renaming itself.
    var alias: String?

    var displayName: String { alias ?? name }
}

/// Thread-safe persistent state. The pairing key lives in a 0600 file in Application Support.
final class Store: @unchecked Sendable {
    static let shared = Store()

    private let lock = NSLock()
    private let dir: URL
    private var _peer: PairedPeer?
    private var _pendingPairKey: Data?

    let deviceId: String

    /// The name the phone sees: a custom one if set, otherwise the computer name from System Settings.
    var deviceName: String { customName ?? systemName }
    var systemName: String { Host.current().localizedName ?? "Mac" }
    var customName: String? {
        get { UserDefaults.standard.string(forKey: "deviceName") }
        set { UserDefaults.standard.set(newValue?.trimmed, forKey: "deviceName") }
    }

    init(directory: URL? = nil, deviceId: String? = nil) {
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        dir = directory ?? support.appendingPathComponent("NotchDrop", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)

        if let id = deviceId {
            self.deviceId = id
        } else if let id = UserDefaults.standard.string(forKey: "deviceId") {
            self.deviceId = id
        } else {
            let id = UUID().uuidString.lowercased()
            UserDefaults.standard.set(id, forKey: "deviceId")
            self.deviceId = id
        }
        if let data = try? Data(contentsOf: peerFile) {
            _peer = try? JSONDecoder().decode(PairedPeer.self, from: data)
        }
    }

    private var peerFile: URL { dir.appendingPathComponent("peer.json") }
    /// Where an interrupted incoming batch is remembered so it can be resumed after a relaunch.
    var resumeFile: URL { dir.appendingPathComponent("resume.json") }
    var recentsFile: URL { dir.appendingPathComponent("recent.json") }
    var historyFile: URL { dir.appendingPathComponent("history.json") }

    var peer: PairedPeer? {
        get { lock.lock(); defer { lock.unlock() }; return _peer }
        set {
            lock.lock()
            _peer = newValue
            lock.unlock()
            if let newValue, let data = try? JSONEncoder().encode(newValue) {
                try? data.write(to: peerFile, options: .atomic)
                try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: peerFile.path)
            } else {
                try? FileManager.default.removeItem(at: peerFile)
            }
        }
    }

    var pendingPairKey: Data? {
        get { lock.lock(); defer { lock.unlock() }; return _pendingPairKey }
        set { lock.lock(); _pendingPairKey = newValue; lock.unlock() }
    }

    func updatePeerAddresses(_ addrs: [String], port: UInt16?, name: String?) {
        guard var p = peer else { return }
        let new = addrs.isEmpty ? p.addrs : addrs
        let newPort = port ?? p.port
        let newName = name ?? p.name
        guard new != p.addrs || newPort != p.port || newName != p.name else { return }
        p.addrs = new
        p.port = newPort
        p.name = newName
        peer = p
    }

    /// Key lookup for incoming handshakes.
    func key(mode: String, from: String) -> Data? {
        switch mode {
        case "pair": return pendingPairKey
        case "session": return peer?.id == from ? peer?.key : nil
        default: return nil
        }
    }

    var receiveDirectory: URL {
        if let custom = UserDefaults.standard.string(forKey: Prefs.Key.saveFolder) {
            return URL(fileURLWithPath: custom, isDirectory: true)
        }
        let downloads = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask)[0]
        return downloads.appendingPathComponent("From \(peer?.displayName ?? "Phone")", isDirectory: true)
    }
}

extension String {
    /// Trimmed, or nil when nothing is left.
    var trimmed: String? {
        let t = trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty ? nil : t
    }
}
