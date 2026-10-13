import CryptoKit
import Foundation
import UniformTypeIdentifiers

enum OutgoingItem {
    case file(url: URL, name: String, size: Int64, mime: String)
    case text(String)

    var size: Int64 {
        if case .file(_, _, let size, _) = self { return size }
        return 0
    }
}

struct ReceivedBatch {
    var files: [URL] = []
    var texts: [String] = []
}

/// Called with (bytesDone, bytesTotal, currentName). Throttled by the caller of `Transfer`.
typealias ProgressHandler = (Int64, Int64, String) -> Void

enum Transfer {
    // MARK: Sending

    static func send(_ items: [OutgoingItem], over ch: SecureChannel, progress: ProgressHandler) async throws {
        let manifest: [[String: Any]] = items.map {
            switch $0 {
            case .file(_, let name, let size, let mime):
                return ["kind": "file", "name": name, "size": size, "mime": mime]
            case .text(let s):
                return ["kind": "text", "text": s]
            }
        }
        try await ch.send(json: ["t": "offer", "items": manifest])
        _ = try await ch.expect("accept")

        let total = items.reduce(Int64(0)) { $0 + $1.size }
        var done: Int64 = 0
        progress(0, total, "")
        for item in items {
            guard case .file(let url, let name, _, _) = item else { continue }
            let fh = try FileHandle(forReadingFrom: url)
            defer { try? fh.close() }
            var hasher = SHA256()
            while true {
                let chunk = try fh.read(upToCount: Wire.chunkSize) ?? Data()
                if chunk.isEmpty { break }
                hasher.update(data: chunk)
                try await ch.send(chunk: chunk)
                done += Int64(chunk.count)
                progress(done, total, name)
            }
            try await ch.send(json: ["t": "file_end", "sha256": hex(hasher.finalize())])
        }
        try await ch.send(json: ["t": "done"])
        _ = try await ch.expect("received")
    }

    // MARK: Receiving

    static func receive(offer: [String: Any], over ch: SecureChannel, saveDir: URL, progress: ProgressHandler) async throws -> ReceivedBatch {
        guard let items = offer["items"] as? [[String: Any]] else { throw NDError.badFrame("bad offer") }
        try FileManager.default.createDirectory(at: saveDir, withIntermediateDirectories: true)
        try await ch.send(json: ["t": "accept"])

        let total = items.reduce(Int64(0)) { $0 + ((($1["size"] as? NSNumber)?.int64Value) ?? 0) }
        var done: Int64 = 0
        var batch = ReceivedBatch()
        progress(0, total, "")

        for item in items {
            switch item["kind"] as? String {
            case "text":
                if let t = item["text"] as? String { batch.texts.append(t) }
            case "file":
                let name = sanitize(item["name"] as? String ?? "file")
                let size = (item["size"] as? NSNumber)?.int64Value ?? -1
                guard size >= 0 else { throw NDError.badFrame("bad size") }
                let part = saveDir.appendingPathComponent(".\(UUID().uuidString).ndpart")
                FileManager.default.createFile(atPath: part.path, contents: nil)
                let fh = try FileHandle(forWritingTo: part)
                var hasher = SHA256()
                var got: Int64 = 0
                do {
                    while got < size {
                        guard case .chunk(let data) = try await ch.receive() else { throw NDError.badFrame("expected data") }
                        guard got + Int64(data.count) <= size else { throw NDError.badFrame("too much data") }
                        try fh.write(contentsOf: data)
                        hasher.update(data: data)
                        got += Int64(data.count)
                        done += Int64(data.count)
                        progress(done, total, name)
                    }
                    try fh.close()
                    let end = try await ch.expect("file_end")
                    guard (end["sha256"] as? String)?.lowercased() == hex(hasher.finalize()) else {
                        throw NDError.badFrame("checksum mismatch for \(name)")
                    }
                    let dest = uniqueURL(in: saveDir, name: name)
                    try FileManager.default.moveItem(at: part, to: dest)
                    batch.files.append(dest)
                } catch {
                    try? fh.close()
                    try? FileManager.default.removeItem(at: part)
                    throw error
                }
            default:
                break
            }
        }
        _ = try await ch.expect("done")
        try await ch.send(json: ["t": "received", "ok": true])
        return batch
    }

    // MARK: Helpers

    static func hex<D: Sequence>(_ digest: D) -> String where D.Element == UInt8 {
        digest.map { String(format: "%02x", $0) }.joined()
    }

    static func sanitize(_ raw: String) -> String {
        var s = (raw as NSString).lastPathComponent
            .replacingOccurrences(of: ":", with: "-")
            .replacingOccurrences(of: "/", with: "-")
        while s.hasPrefix(".") { s.removeFirst() }
        s = s.trimmingCharacters(in: .whitespacesAndNewlines)
        return s.isEmpty ? "file" : String(s.prefix(200))
    }

    static func uniqueURL(in dir: URL, name: String) -> URL {
        let fm = FileManager.default
        var url = dir.appendingPathComponent(name)
        let base = (name as NSString).deletingPathExtension
        let ext = (name as NSString).pathExtension
        var i = 1
        while fm.fileExists(atPath: url.path) {
            url = dir.appendingPathComponent(ext.isEmpty ? "\(base) (\(i))" : "\(base) (\(i)).\(ext)")
            i += 1
        }
        return url
    }

    static func mimeType(for url: URL) -> String {
        UTType(filenameExtension: url.pathExtension)?.preferredMIMEType ?? "application/octet-stream"
    }

    /// Turns dropped URLs into sendable items. Folders become .zip archives (via NSFileCoordinator).
    static func prepare(urls: [URL], texts: [String]) throws -> [OutgoingItem] {
        var items: [OutgoingItem] = []
        let tmp = FileManager.default.temporaryDirectory.appendingPathComponent("NotchDrop", isDirectory: true)
        try FileManager.default.createDirectory(at: tmp, withIntermediateDirectories: true)
        for url in urls {
            var isDir: ObjCBool = false
            guard FileManager.default.fileExists(atPath: url.path, isDirectory: &isDir) else { continue }
            if isDir.boolValue {
                var zipped: URL?
                var coordErr: NSError?
                var copyErr: Error?
                NSFileCoordinator().coordinate(readingItemAt: url, options: .forUploading, error: &coordErr) { zipURL in
                    let dest = tmp.appendingPathComponent(UUID().uuidString).appendingPathExtension("zip")
                    do { try FileManager.default.copyItem(at: zipURL, to: dest); zipped = dest } catch { copyErr = error }
                }
                if let e = coordErr ?? copyErr as NSError? { throw e }
                if let z = zipped {
                    items.append(.file(url: z, name: url.lastPathComponent + ".zip", size: fileSize(z), mime: "application/zip"))
                }
            } else {
                items.append(.file(url: url, name: url.lastPathComponent, size: fileSize(url), mime: mimeType(for: url)))
            }
        }
        items += texts.filter { !$0.isEmpty }.map { .text($0) }
        return items
    }

    static func fileSize(_ url: URL) -> Int64 {
        ((try? FileManager.default.attributesOfItem(atPath: url.path)[.size]) as? NSNumber)?.int64Value ?? 0
    }
}

/// Rate-limits progress callbacks to ~15/s plus the final one.
final class ProgressThrottle: @unchecked Sendable {
    private var last = Date.distantPast
    private let lock = NSLock()
    private let emit: (Int64, Int64, String) -> Void

    init(_ emit: @escaping (Int64, Int64, String) -> Void) { self.emit = emit }

    func callAsFunction(_ done: Int64, _ total: Int64, _ name: String) {
        lock.lock()
        let now = Date()
        let fire = done >= total || now.timeIntervalSince(last) > 0.066
        if fire { last = now }
        lock.unlock()
        if fire { emit(done, total, name) }
    }
}
