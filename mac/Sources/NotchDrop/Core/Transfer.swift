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

    /// `id` names the batch so an interrupted one can be resumed: sending the same items with the same id
    /// lets the receiver answer with how much of each file it already has.
    static func send(_ items: [OutgoingItem], over ch: SecureChannel, id: String? = nil, control: TransferControl? = nil,
                     progress: ProgressHandler) async throws
    {
        let manifest: [[String: Any]] = items.map {
            switch $0 {
            case .file(_, let name, let size, let mime):
                return ["kind": "file", "name": name, "size": size, "mime": mime]
            case .text(let s):
                return ["kind": "text", "text": s]
            }
        }
        var offer: [String: Any] = ["t": "offer", "items": manifest]
        if let id { offer["id"] = id }
        try await ch.send(json: offer)
        let accept = try await ch.expect("accept")
        let have = (accept["resume"] as? [NSNumber])?.map(\.int64Value) ?? []

        // The receiver may cancel mid-stream, so its reply is read while the data is still going out.
        let remote = ErrorBox()
        let reply = Task {
            do { return try await ch.expect("received") } catch { remote.set(error); throw error }
        }
        do {
            let total = items.reduce(Int64(0)) { $0 + $1.size }
            let skip: [Int64] = items.indices.map { i in
                guard i < have.count else { return 0 }
                return have[i] == ResumeStore.complete ? items[i].size : min(max(have[i], 0), items[i].size)
            }
            var done = skip.reduce(0, +)
            progress(done, total, "")
            for (i, item) in items.enumerated() {
                guard case .file(let url, let name, _, _) = item else { continue }
                if i < have.count, have[i] == ResumeStore.complete { continue } // received before the interruption
                let fh = try FileHandle(forReadingFrom: url)
                defer { try? fh.close() }
                var hasher = SHA256()
                // The receiver already has the start; hash it locally so the checksum still covers the whole file.
                var prefix = skip[i]
                while prefix > 0 {
                    let chunk = try fh.read(upToCount: Int(min(Int64(Wire.chunkSize), prefix))) ?? Data()
                    if chunk.isEmpty { throw NDError.badFrame("\(name) changed since the last attempt") }
                    hasher.update(data: chunk)
                    prefix -= Int64(chunk.count)
                }
                while true {
                    if let control, control.isCancelled { throw NDError.cancelled(TransferControl.youCancelled) }
                    if let e = remote.error { throw e }
                    let chunk = try fh.read(upToCount: Wire.chunkSize) ?? Data()
                    if chunk.isEmpty { break }
                    hasher.update(data: chunk)
                    try await stall { try await ch.send(chunk: chunk) }
                    done += Int64(chunk.count)
                    progress(done, total, name)
                }
                try await stall { try await ch.send(json: ["t": "file_end", "sha256": hex(hasher.finalize())]) }
            }
            try await stall { try await ch.send(json: ["t": "done"]) }
            _ = try await stall { try await reply.value }
        } catch {
            defer { reply.cancel() }
            if case NDError.cancelled = error, let control, control.isCancelled {
                try? await withTimeout(1) { try await ch.send(json: Wire.cancelFrame(control.note)) }
                throw error
            }
            // A failed write usually means the receiver hung up; prefer its reason (e.g. it cancelled).
            if let e = await remote.wait(upTo: 1) { throw e }
            throw error
        }
    }

    /// Runs one network step, treating a stall as a lost connection.
    private static func stall<T>(_ op: @escaping () async throws -> T) async throws -> T {
        do {
            return try await withTimeout(Wire.stallTimeout, op)
        } catch NDError.timeout {
            throw NDError.connectionLost
        }
    }

    // MARK: Receiving

    /// With a `resume` store, an interrupted batch keeps its partial files so the sender can pick up where it stopped.
    static func receive(offer: [String: Any], over ch: SecureChannel, saveDir: URL, control: TransferControl? = nil,
                        resume: ResumeStore? = nil, progress: ProgressHandler) async throws -> ReceivedBatch
    {
        let partial = PartialBatch(id: offer["id"] as? String, signature: ResumeStore.signature(offer))
        do {
            let batch = try await receiveItems(offer: offer, over: ch, saveDir: saveDir, resume: resume, partial: partial,
                                               progress: progress)
            return batch
        } catch {
            // Cancelling closes the connection under the read; report that as the cancel it was.
            let cancelled = control?.isCancelled == true
            var stopped = cancelled
            if case NDError.cancelled = error { stopped = true }
            if let resume, !stopped, partial.id != nil { resume.keep(partial) } else { partial.discard() }
            if cancelled { throw NDError.cancelled(TransferControl.youCancelled) }
            throw error
        }
    }

    private static func receiveItems(offer: [String: Any], over ch: SecureChannel, saveDir: URL, resume: ResumeStore?,
                                     partial: PartialBatch, progress: ProgressHandler) async throws -> ReceivedBatch
    {
        guard let items = offer["items"] as? [[String: Any]] else { throw NDError.badFrame("bad offer") }
        try FileManager.default.createDirectory(at: saveDir, withIntermediateDirectories: true)
        let prior = resume?.take(id: partial.id, signature: partial.signature)
        if let prior { partial.parts = prior.parts; partial.done = prior.done } // still ours if this attempt fails too
        var accept: [String: Any] = ["t": "accept"]
        if let prior { accept["resume"] = items.indices.map { prior.offset(for: $0) } }
        try await ch.send(json: accept)

        let total = items.reduce(Int64(0)) { $0 + ((($1["size"] as? NSNumber)?.int64Value) ?? 0) }
        var done: Int64 = 0
        var batch = ReceivedBatch()
        if let prior {
            for (i, item) in items.enumerated() {
                let size = (item["size"] as? NSNumber)?.int64Value ?? 0
                let o = prior.offset(for: i)
                done += o == ResumeStore.complete ? size : o
            }
        }
        progress(done, total, "")

        for (i, item) in items.enumerated() {
            switch item["kind"] as? String {
            case "text":
                if let t = item["text"] as? String { batch.texts.append(t) }
            case "file":
                let name = sanitize(item["name"] as? String ?? "file")
                let size = (item["size"] as? NSNumber)?.int64Value ?? -1
                guard size >= 0 else { throw NDError.badFrame("bad size") }
                if let url = prior?.done[i], prior?.offset(for: i) == ResumeStore.complete {
                    partial.done[i] = url
                    batch.files.append(url)
                    continue
                }
                var hasher = SHA256()
                var got: Int64 = 0
                let part: URL
                if let p = prior?.parts[i], prior?.offset(for: i) == p.length {
                    // Resuming: re-hash what is already on disk, then append.
                    part = p.url
                    let rh = try FileHandle(forReadingFrom: part)
                    while let c = try rh.read(upToCount: Wire.chunkSize), !c.isEmpty { hasher.update(data: c) }
                    try rh.close()
                    got = p.length
                } else {
                    if let stale = partial.parts[i] { try? FileManager.default.removeItem(at: stale.url) }
                    part = saveDir.appendingPathComponent(".\(UUID().uuidString)\(ResumeStore.partSuffix)")
                    FileManager.default.createFile(atPath: part.path, contents: nil)
                    resume?.track(part) // so a crash mid-file doesn't leave it behind forever
                }
                partial.parts[i] = (part, got)
                let fh = try FileHandle(forWritingTo: part)
                try fh.truncate(atOffset: UInt64(got))
                try fh.seekToEnd()
                do {
                    while got < size {
                        guard case .chunk(let data) = try await stall({ try await ch.receive() }) else { throw NDError.badFrame("expected data") }
                        guard got + Int64(data.count) <= size else { throw NDError.badFrame("too much data") }
                        try fh.write(contentsOf: data)
                        hasher.update(data: data)
                        got += Int64(data.count)
                        done += Int64(data.count)
                        progress(done, total, name)
                    }
                    try fh.close()
                    let end = try await stall { try await ch.expect("file_end") }
                    guard (end["sha256"] as? String)?.lowercased() == hex(hasher.finalize()) else {
                        throw NDError.badFrame("checksum mismatch for \(name)")
                    }
                    let dest = uniqueURL(in: saveDir, name: name)
                    try FileManager.default.moveItem(at: part, to: dest)
                    resume?.untrack(part)
                    partial.parts[i] = nil
                    partial.done[i] = dest
                    batch.files.append(dest)
                } catch {
                    try? fh.close()
                    if case NDError.badFrame = error {
                        try? FileManager.default.removeItem(at: part) // corrupt: start this file over next time
                        partial.parts[i] = nil
                    } else {
                        partial.parts[i] = (part, got)
                    }
                    throw error
                }
            default:
                break
            }
        }
        _ = try await stall { try await ch.expect("done") }
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
    /// Scratch space for zipped folders and handed-over items; emptied at launch.
    static var tempDirectory: URL { FileManager.default.temporaryDirectory.appendingPathComponent("NotchDrop", isDirectory: true) }

    /// Deletes the zips and copies `prepare` (or a drop or share) made once they've been sent.
    static func removeTemporaries(_ items: [OutgoingItem]) {
        let tmp = tempDirectory.standardizedFileURL.path + "/"
        for case .file(let url, _, _, _) in items where url.standardizedFileURL.path.hasPrefix(tmp) {
            try? FileManager.default.removeItem(at: url)
        }
    }

    static func prepare(urls: [URL], texts: [String]) throws -> [OutgoingItem] {
        var items: [OutgoingItem] = []
        let tmp = tempDirectory
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

/// Lets the UI stop a running transfer. Either side may cancel; the other side is told with a
/// cancel frame so it can say who stopped it instead of reporting a broken connection.
final class TransferControl: @unchecked Sendable {
    static let youCancelled = "You stopped the transfer."

    /// What the other side shows, e.g. "Stopped on MacBook Pro."
    let note: String
    private let lock = NSLock()
    private var cancelled = false
    private var channel: SecureChannel?
    private var receiving = false

    init(note: String) { self.note = note }

    var isCancelled: Bool { lock.withLock { cancelled } }

    func attach(_ ch: SecureChannel, receiving: Bool) {
        let late = lock.withLock { () -> Bool in
            channel = ch
            self.receiving = receiving
            return cancelled
        }
        if late { cancel() }
    }

    func cancel() {
        let (ch, receiving) = lock.withLock { () -> (SecureChannel?, Bool) in
            cancelled = true
            return (channel, self.receiving)
        }
        guard let ch else { return } // still connecting: the sender checks the flag once connected
        if receiving {
            // The receive loop is blocked on a read, so tell the sender from here and hang up.
            let note = note
            Task {
                try? await withTimeout(1) { try await ch.send(json: Wire.cancelFrame(note)) }
                ch.close()
            }
        } else {
            // The send loop notices between chunks and tells the receiver; hang up anyway if it is stuck.
            DispatchQueue.global().asyncAfter(deadline: .now() + 2) { ch.close() }
        }
    }
}

/// The first error seen by a background reader, readable without waiting on it.
final class ErrorBox: @unchecked Sendable {
    private let lock = NSLock()
    private var value: Error?

    var error: Error? { lock.withLock { value } }

    func set(_ e: Error) { lock.withLock { if value == nil { value = e } } }

    func wait(upTo seconds: TimeInterval) async -> Error? {
        let end = Date().addingTimeInterval(seconds)
        while Date() < end {
            if let e = error { return e }
            try? await Task.sleep(nanoseconds: 50_000_000)
        }
        return error
    }
}

/// What an interrupted incoming batch left behind: finished files, and partial files with how much they hold.
final class PartialBatch: @unchecked Sendable {
    let id: String?
    let signature: [String]
    var parts: [Int: (url: URL, length: Int64)] = [:]
    var done: [Int: URL] = [:]

    init(id: String?, signature: [String]) {
        self.id = id
        self.signature = signature
    }

    /// Bytes of item `i` already here, or `ResumeStore.complete`.
    func offset(for i: Int) -> Int64 {
        if let url = done[i], FileManager.default.fileExists(atPath: url.path) { return ResumeStore.complete }
        if let p = parts[i], FileManager.default.fileExists(atPath: p.url.path) { return p.length }
        return 0
    }

    func discard() {
        for p in parts.values { try? FileManager.default.removeItem(at: p.url) }
        parts = [:]
    }
}

/// Holds the last interrupted incoming batch until the sender resumes it or sends something else.
/// With a file, the record survives quitting, and partial files nothing refers to any more are deleted at startup.
final class ResumeStore: @unchecked Sendable {
    /// In an `accept`'s `resume` list: this item arrived in full before the interruption; don't send it.
    static let complete: Int64 = -1
    /// Partial files are hidden in the save folder as `.<uuid>.ndpart`.
    static let partSuffix = ".ndpart"

    private struct Record: Codable {
        struct Part: Codable { var path: String; var length: Int64 }
        var id: String?
        var signature: [String]
        var parts: [String: Part]
        var done: [String: String]
        /// Every partial file that may exist, including ones being written when the app quit.
        var tracked: [String]
    }

    private let lock = NSLock()
    private let file: URL?
    private var pending: PartialBatch?
    private var tracked: Set<String> = []

    init(file: URL? = nil) {
        self.file = file
        guard let file, let data = try? Data(contentsOf: file), let r = try? JSONDecoder().decode(Record.self, from: data) else { return }
        tracked = Set(r.tracked)
        guard r.id != nil else { return }
        let b = PartialBatch(id: r.id, signature: r.signature)
        for (k, p) in r.parts { if let i = Int(k) { b.parts[i] = (URL(fileURLWithPath: p.path), p.length) } }
        for (k, path) in r.done { if let i = Int(k) { b.done[i] = URL(fileURLWithPath: path) } }
        pending = b
    }

    /// One entry per offered item; a resume only matches the exact same list.
    static func signature(_ offer: [String: Any]) -> [String] {
        (offer["items"] as? [[String: Any]] ?? []).map {
            $0["kind"] as? String == "file"
                ? "file:\($0["name"] as? String ?? ""):\(($0["size"] as? NSNumber)?.int64Value ?? -1)"
                : "text"
        }
    }

    func keep(_ batch: PartialBatch) {
        let old = lock.withLock { () -> PartialBatch? in
            defer { pending = batch; save() }
            return pending === batch ? nil : pending
        }
        old?.discard()
    }

    /// The stored batch if this offer resumes it. Any other offer drops it, deleting its partial files.
    func take(id: String?, signature: [String]) -> PartialBatch? {
        let p = lock.withLock { () -> PartialBatch? in
            defer { pending = nil; save() }
            return pending
        }
        guard let p else { return nil }
        if let id, p.id == id, p.signature == signature { return p }
        p.discard()
        return nil
    }

    func track(_ part: URL) { lock.withLock { tracked.insert(part.path); save() } }
    func untrack(_ part: URL) { lock.withLock { tracked.remove(part.path); save() } }

    /// Deletes partial files left by earlier runs that the kept batch doesn't need: anything tracked, plus any
    /// `.ndpart` directly in `folders`. Call once at startup, before receiving.
    func cleanUp(folders: [URL]) {
        let fm = FileManager.default
        lock.withLock {
            let keep = Set(pending?.parts.values.map(\.url.path) ?? [])
            var stray = tracked
            for dir in folders {
                let names = (try? fm.contentsOfDirectory(atPath: dir.path)) ?? []
                for n in names where n.hasPrefix(".") && n.hasSuffix(Self.partSuffix) { stray.insert(dir.appendingPathComponent(n).path) }
            }
            for path in stray.subtracting(keep) { try? fm.removeItem(atPath: path) }
            tracked = keep
            // A kept batch whose files have all gone (deleted by hand) is useless.
            if let p = pending, p.parts.values.allSatisfy({ !fm.fileExists(atPath: $0.url.path) }),
               p.done.values.allSatisfy({ !fm.fileExists(atPath: $0.path) }) { pending = nil }
            save()
        }
    }

    /// Call with `lock` held.
    private func save() {
        guard let file else { return }
        let fm = FileManager.default
        tracked = tracked.filter { fm.fileExists(atPath: $0) }
        var r = Record(id: nil, signature: [], parts: [:], done: [:], tracked: Array(tracked).sorted())
        if let p = pending {
            r.id = p.id
            r.signature = p.signature
            for (i, part) in p.parts { r.parts[String(i)] = .init(path: part.url.path, length: part.length) }
            for (i, url) in p.done { r.done[String(i)] = url.path }
            r.tracked = Array(tracked.union(p.parts.values.map(\.url.path))).sorted()
        }
        if r.id == nil, r.tracked.isEmpty {
            try? fm.removeItem(at: file)
        } else if let data = try? JSONEncoder().encode(r) {
            try? data.write(to: file, options: .atomic)
        }
    }
}
