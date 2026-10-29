import CoreMedia
import Foundation
import Network

/// Live video from the phone (Camera Relay, screen mirror). See PROTOCOL.md, "Streams".
///
/// Each chunk is one H.264 access unit: flags (1 byte), presentation time in µs (8 bytes, big-endian), Annex-B data.
enum StreamFrame {
    static let keyFlag: UInt8 = 1
    static let configFlag: UInt8 = 2

    struct Parsed {
        var flags: UInt8
        var ptsMicros: UInt64
        var data: Data
        var isKey: Bool { flags & keyFlag != 0 }
        var isConfig: Bool { flags & configFlag != 0 }
    }

    static func parse(_ chunk: Data) -> Parsed? {
        guard chunk.count > 9 else { return nil }
        let b = [UInt8](chunk.prefix(9))
        var pts: UInt64 = 0
        for i in 1...8 { pts = pts << 8 | UInt64(b[i]) }
        return Parsed(flags: b[0], ptsMicros: pts, data: chunk.dropFirst(9))
    }
}

enum H264 {
    /// NAL units of an Annex-B buffer, without start codes.
    static func nalUnits(_ data: Data) -> [Data] {
        let b = [UInt8](data)
        var starts: [(code: Int, payload: Int)] = []
        var i = 0
        while i + 2 < b.count {
            if b[i] == 0, b[i + 1] == 0, b[i + 2] == 1 {
                let code = i > 0 && b[i - 1] == 0 ? i - 1 : i
                starts.append((code, i + 3))
                i += 3
            } else {
                i += 1
            }
        }
        guard !starts.isEmpty else { return b.isEmpty ? [] : [data] }
        var out: [Data] = []
        for (n, s) in starts.enumerated() {
            let end = n + 1 < starts.count ? starts[n + 1].code : b.count
            if end > s.payload { out.append(Data(b[s.payload..<end])) }
        }
        return out
    }

    static func type(_ nal: Data) -> UInt8 { (nal.first ?? 0) & 0x1F }

    static func formatDescription(sps: Data, pps: Data) -> CMVideoFormatDescription? {
        var desc: CMFormatDescription?
        let status = sps.withUnsafeBytes { s in
            pps.withUnsafeBytes { p in
                let ptrs = [s.bindMemory(to: UInt8.self).baseAddress!, p.bindMemory(to: UInt8.self).baseAddress!]
                let sizes = [sps.count, pps.count]
                return CMVideoFormatDescriptionCreateFromH264ParameterSets(
                    allocator: kCFAllocatorDefault, parameterSetCount: 2, parameterSetPointers: ptrs,
                    parameterSetSizes: sizes, nalUnitHeaderLength: 4, formatDescriptionOut: &desc)
            }
        }
        return status == noErr ? desc : nil
    }

    /// One AVCC sample from the frame's slice NAL units.
    static func sampleBuffer(nals: [Data], format: CMVideoFormatDescription, pts: CMTime, isKey: Bool) -> CMSampleBuffer? {
        var avcc = Data()
        for n in nals {
            var len = UInt32(n.count).bigEndian
            withUnsafeBytes(of: &len) { avcc.append(contentsOf: $0) }
            avcc.append(n)
        }
        guard !avcc.isEmpty else { return nil }
        var block: CMBlockBuffer?
        guard CMBlockBufferCreateWithMemoryBlock(allocator: kCFAllocatorDefault, memoryBlock: nil, blockLength: avcc.count,
                                                 blockAllocator: kCFAllocatorDefault, customBlockSource: nil, offsetToData: 0,
                                                 dataLength: avcc.count, flags: 0, blockBufferOut: &block) == noErr,
            let block else { return nil }
        let copied = avcc.withUnsafeBytes { CMBlockBufferReplaceDataBytes(with: $0.baseAddress!, blockBuffer: block, offsetIntoDestination: 0, dataLength: avcc.count) }
        guard copied == noErr else { return nil }
        var sample: CMSampleBuffer?
        var timing = CMSampleTimingInfo(duration: .invalid, presentationTimeStamp: pts, decodeTimeStamp: .invalid)
        var size = avcc.count
        guard CMSampleBufferCreateReady(allocator: kCFAllocatorDefault, dataBuffer: block, formatDescription: format,
                                        sampleCount: 1, sampleTimingEntryCount: 1, sampleTimingArray: &timing,
                                        sampleSizeEntryCount: 1, sampleSizeArray: &size, sampleBufferOut: &sample) == noErr,
            let sample else { return nil }
        if let atts = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: true), CFArrayGetCount(atts) > 0 {
            let dict = unsafeBitCast(CFArrayGetValueAtIndex(atts, 0), to: CFMutableDictionary.self)
            CFDictionarySetValue(dict, Unmanaged.passUnretained(kCMSampleAttachmentKey_DisplayImmediately).toOpaque(),
                                 Unmanaged.passUnretained(kCFBooleanTrue).toOpaque())
            if !isKey {
                CFDictionarySetValue(dict, Unmanaged.passUnretained(kCMSampleAttachmentKey_NotSync).toOpaque(),
                                     Unmanaged.passUnretained(kCFBooleanTrue).toOpaque())
            }
        }
        return sample
    }
}

/// Wraps H.264 access units in MPEG-TS, which OBS's Media Source plays with correct timing.
struct TSMuxer {
    private static let pmtPID: UInt16 = 0x1000
    private static let videoPID: UInt16 = 0x100
    private var counters: [UInt16: UInt8] = [:]
    private var base: UInt64?

    /// `config` is SPS+PPS in Annex-B; it is repeated before every keyframe so a player can join at any keyframe.
    mutating func mux(_ annexB: Data, ptsMicros: UInt64, key: Bool, config: Data?) -> Data {
        let start = base ?? ptsMicros
        base = start
        // 90 kHz, offset by a second so the PCR (100 ms earlier) never goes negative.
        let pts = (ptsMicros &- start) * 9 / 100 + 90_000
        var out = Data()
        var au = Data([0, 0, 0, 1, 0x09, 0xF0]) // access unit delimiter
        if key {
            out += psi(pid: 0, section: Self.pat)
            out += psi(pid: Self.pmtPID, section: Self.pmt)
            if let config { au += config }
        }
        au += annexB
        var pes = Data([0, 0, 1, 0xE0, 0, 0, 0x80, 0x80, 5])
        pes += [UInt8(0x21 | (pts >> 29) & 0x0E), UInt8(pts >> 22 & 0xFF), UInt8((pts >> 14) & 0xFE | 1),
                UInt8(pts >> 7 & 0xFF), UInt8((pts << 1) & 0xFE | 1)]
        pes += au
        out += packets(pid: Self.videoPID, payload: pes, pcr: pts - 9000, randomAccess: key)
        return out
    }

    private mutating func counter(_ pid: UInt16) -> UInt8 {
        let c = counters[pid, default: 0]
        counters[pid] = (c + 1) & 0x0F
        return c
    }

    private mutating func psi(pid: UInt16, section: [UInt8]) -> Data {
        var p = [UInt8](repeating: 0xFF, count: 188)
        p[0] = 0x47
        p[1] = 0x40 | UInt8(pid >> 8 & 0x1F)
        p[2] = UInt8(pid & 0xFF)
        p[3] = 0x10 | counter(pid)
        p[4] = 0 // pointer field
        let s = section + Self.crc32(section)
        p.replaceSubrange(5..<(5 + s.count), with: s)
        return Data(p)
    }

    private mutating func packets(pid: UInt16, payload: Data, pcr: UInt64, randomAccess: Bool) -> Data {
        let bytes = [UInt8](payload)
        var out = Data(capacity: (bytes.count / 184 + 2) * 188)
        var offset = 0
        var first = true
        while offset < bytes.count {
            var af: [UInt8] = []
            if first {
                let base = pcr
                af = [(randomAccess ? 0x40 : 0) | 0x10,
                      UInt8(base >> 25 & 0xFF), UInt8(base >> 17 & 0xFF), UInt8(base >> 9 & 0xFF), UInt8(base >> 1 & 0xFF),
                      UInt8((base & 1) << 7 | 0x7E), 0]
            }
            var room = 184 - (af.isEmpty ? 0 : 1 + af.count)
            let remaining = bytes.count - offset
            var hasAF = !af.isEmpty
            if remaining < room {
                // Stuff the adaptation field so the payload ends the packet exactly.
                let stuff = room - remaining
                if af.isEmpty {
                    hasAF = true
                    af = stuff == 1 ? [] : [0x00] + [UInt8](repeating: 0xFF, count: stuff - 2)
                } else {
                    af += [UInt8](repeating: 0xFF, count: stuff)
                }
                room = remaining
            }
            var p: [UInt8] = [0x47, (first ? 0x40 : 0) | UInt8(pid >> 8 & 0x1F), UInt8(pid & 0xFF),
                              (hasAF ? 0x30 : 0x10) | counter(pid)]
            if hasAF { p.append(UInt8(af.count)); p += af }
            p += bytes[offset..<(offset + room)]
            offset += room
            out.append(contentsOf: p)
            first = false
        }
        return out
    }

    private static let pat: [UInt8] = [0x00, 0xB0, 0x0D, 0x00, 0x01, 0xC1, 0x00, 0x00,
                                       0x00, 0x01, 0xE0 | UInt8(pmtPID >> 8), UInt8(pmtPID & 0xFF)]
    private static let pmt: [UInt8] = [0x02, 0xB0, 0x12, 0x00, 0x01, 0xC1, 0x00, 0x00,
                                       0xE0 | UInt8(videoPID >> 8), UInt8(videoPID & 0xFF), 0xF0, 0x00,
                                       0x1B, 0xE0 | UInt8(videoPID >> 8), UInt8(videoPID & 0xFF), 0xF0, 0x00]

    /// CRC-32/MPEG-2.
    static func crc32(_ bytes: [UInt8]) -> [UInt8] {
        var crc: UInt32 = 0xFFFF_FFFF
        for b in bytes {
            crc ^= UInt32(b) << 24
            for _ in 0..<8 { crc = crc & 0x8000_0000 != 0 ? (crc << 1) ^ 0x04C1_1DB7 : crc << 1 }
        }
        return [UInt8(crc >> 24), UInt8(crc >> 16 & 0xFF), UInt8(crc >> 8 & 0xFF), UInt8(crc & 0xFF)]
    }
}

/// Serves the camera as MPEG-TS on localhost for OBS (Media Source → tcp://127.0.0.1:47830).
final class FeedServer: @unchecked Sendable {
    static let port: UInt16 = 47830

    private final class Client {
        let conn: NWConnection
        var started = false
        var pending = 0
        init(_ conn: NWConnection) { self.conn = conn }
    }

    private let queue = DispatchQueue(label: "notchdrop.feed")
    private var listener: NWListener?
    private var clients: [ObjectIdentifier: Client] = [:]
    private var muxer = TSMuxer()
    /// Asked for a keyframe so a new viewer can start right away.
    var onViewerJoined: (() -> Void)?
    /// Number of connected players, reported on the main queue.
    var onViewersChanged: ((Int) -> Void)?

    func start() {
        queue.async { [self] in
            guard listener == nil else { return }
            let params = NWParameters.tcp
            params.allowLocalEndpointReuse = true
            params.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: NWEndpoint.Port(rawValue: Self.port)!)
            guard let l = try? NWListener(using: params) else { return }
            l.newConnectionHandler = { [weak self] conn in self?.accept(conn) }
            l.start(queue: queue)
            listener = l
        }
    }

    func stop() {
        queue.async { [self] in
            listener?.cancel()
            listener = nil
            clients.values.forEach { $0.conn.cancel() }
            clients = [:]
            muxer = TSMuxer()
            report()
        }
    }

    private func accept(_ conn: NWConnection) {
        let c = Client(conn)
        let id = ObjectIdentifier(c)
        conn.stateUpdateHandler = { [weak self] state in
            switch state {
            case .failed, .cancelled:
                self?.queue.async { self?.clients[id] = nil; self?.report() }
            default: break
            }
        }
        conn.start(queue: queue)
        // Players send nothing; reading just notices when they hang up.
        func drain() {
            conn.receive(minimumIncompleteLength: 1, maximumLength: 4096) { _, _, done, err in
                if done || err != nil { conn.cancel() } else { drain() }
            }
        }
        drain()
        clients[id] = c
        report()
        onViewerJoined?()
    }

    private func report() {
        let n = clients.count
        DispatchQueue.main.async { self.onViewersChanged?(n) }
    }

    func push(_ frame: StreamFrame.Parsed, config: Data?) {
        queue.async { [self] in
            guard !clients.isEmpty else { return }
            let ts = muxer.mux(frame.data, ptsMicros: frame.ptsMicros, key: frame.isKey, config: config)
            for c in clients.values {
                if !c.started { guard frame.isKey else { continue }; c.started = true }
                // A slow player skips frames until the next keyframe instead of building up delay.
                if c.pending > 30 { c.started = false; continue }
                c.pending += 1
                c.conn.send(content: ts, completion: .contentProcessed { [weak self] _ in
                    self?.queue.async { c.pending -= 1 }
                })
            }
        }
    }
}
