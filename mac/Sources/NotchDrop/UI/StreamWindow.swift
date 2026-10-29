import AppKit
import AVFoundation
import SwiftUI

/// One live stream from the phone: Camera Relay or the phone's screen, shown in its own window.
@MainActor
final class StreamSession: ObservableObject {
    enum Kind: String { case camera, screen }

    let kind: Kind
    let peerName: String
    fileprivate let channel: SecureChannel
    fileprivate let pipeline: StreamPipeline

    /// Encoded frame size, before rotation.
    @Published var videoSize = CGSize(width: 1920, height: 1080)
    /// Degrees clockwise the picture is turned to stand upright.
    @Published var rotation = 0
    @Published var mirror = false
    @Published var lens = "back"
    @Published var lenses: [String] = ["back"]
    @Published var zoom = 1.0
    @Published var zoomRange: ClosedRange<Double> = 1...1
    @Published var torch = false
    @Published var hasTorch = false
    @Published var quality = "1080p"
    @Published var fps = 30
    @Published var recording: URL?
    @Published var obsViewers = 0
    @Published var hover = false
    @Published var floating = false
    /// A short message over the video ("Photo sent", "Turn on phone control…").
    @Published var note: String?
    /// Whether the phone can take taps and keys (its Accessibility service is on).
    @Published var canControl = false
    @Published var fpsShown = 0

    var onEnd: (() -> Void)?
    private var noteToken = 0
    private var ended = false

    init(kind: Kind, info: [String: Any], channel: SecureChannel, peerName: String) {
        self.kind = kind
        self.channel = channel
        self.peerName = peerName
        pipeline = StreamPipeline()
        pipeline.onNeedKeyframe = { [weak channel] in channel?.post(json: ["t": "keyframe"]) }
        pipeline.onFps = { [weak self] n in Task { @MainActor in self?.fpsShown = n } }
        apply(info)
        if kind == .camera {
            let feed = FeedServer()
            feed.onViewerJoined = { [weak channel] in channel?.post(json: ["t": "keyframe"]) }
            feed.onViewersChanged = { [weak self] n in self?.obsViewers = n }
            feed.start()
            pipeline.feed = feed
            mirror = lens == "front"
        }
    }

    var title: String { kind == .camera ? "\(peerName) Camera" : "\(peerName) Screen" }

    /// The picture as shown, after rotation.
    var shownSize: CGSize {
        rotation % 180 == 0 ? videoSize : CGSize(width: videoSize.height, height: videoSize.width)
    }

    /// Phone messages: "format", "cam", "note", "control".
    fileprivate func apply(_ m: [String: Any]) {
        if let w = m["w"] as? Int, let h = m["h"] as? Int, w > 0, h > 0 { videoSize = CGSize(width: w, height: h) }
        if let r = m["rotation"] as? Int { rotation = (r % 360 + 360) % 360 }
        if let l = m["lens"] as? String {
            if l != lens, kind == .camera { mirror = l == "front" }
            lens = l
        }
        if let ls = m["lenses"] as? [String], !ls.isEmpty { lenses = ls }
        if let z = (m["zoom"] as? NSNumber)?.doubleValue { zoom = z }
        if let lo = (m["zoomMin"] as? NSNumber)?.doubleValue, let hi = (m["zoomMax"] as? NSNumber)?.doubleValue, hi >= lo {
            zoomRange = lo...hi
        }
        if let t = m["torch"] as? Bool { torch = t }
        if let t = m["hasTorch"] as? Bool { hasTorch = t }
        if let q = m["quality"] as? String { quality = q }
        if let f = m["fps"] as? Int { fps = f }
        if let c = m["control"] as? Bool { canControl = c }
        if let n = m["message"] as? String { show(note: n) }
    }

    func show(note text: String, for seconds: Double = 3) {
        note = text
        noteToken += 1
        let token = noteToken
        Task {
            try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
            if token == noteToken { note = nil }
        }
    }

    func send(_ msg: [String: Any]) { channel.post(json: msg) }

    func setLens(_ l: String) { send(["t": "cam", "lens": l]) }
    func setZoom(_ z: Double) { zoom = z; send(["t": "cam", "zoom": z]) }
    func toggleTorch() { torch.toggle(); send(["t": "cam", "torch": torch]) }
    func setQuality(_ q: String, fps f: Int) {
        quality = q
        fps = f
        send(["t": "quality", "quality": q, "fps": f])
    }
    func shutter() {
        send(["t": "shutter"])
        show(note: "Taking a photo…")
    }

    func toggleRecording() {
        if let url = recording {
            recording = nil
            pipeline.stopRecording { ok in
                Task { @MainActor in
                    if ok { NSWorkspace.shared.activateFileViewerSelecting([url]) } else { self.show(note: "Recording failed") }
                }
            }
            return
        }
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd 'at' HH.mm.ss"
        let dir = Store.shared.receiveDirectory
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent("\(title) \(f.string(from: Date())).mov")
        do {
            try pipeline.startRecording(to: url, rotation: rotation)
            recording = url
            send(["t": "keyframe"])
        } catch {
            show(note: "Can't record: \(error.localizedDescription)")
        }
    }

    /// Reads from the phone until it stops; runs on the server's task.
    nonisolated func run() async {
        let ch = channel
        let pipe = pipeline
        while true {
            do {
                // Frames keep coming even on a still screen (the phone repeats the last one).
                let m = try await withTimeout(15) { try await ch.receive() }
                switch m {
                case .chunk(let d):
                    if let f = StreamFrame.parse(d) { pipe.handle(f) }
                case .json(let obj):
                    if obj["t"] as? String == "bye" { throw NDError.closed }
                    await MainActor.run { self.apply(obj) }
                }
            } catch {
                break
            }
        }
        await MainActor.run { self.finish(note: nil) }
    }

    /// Ends the stream from this side (window closed).
    func stop() {
        guard !ended else { return }
        send(["t": "bye"])
        let ch = channel
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { ch.close() }
        finish(note: nil)
    }

    private func finish(note: String?) {
        guard !ended else { return }
        ended = true
        if recording != nil { toggleRecording() }
        pipeline.feed?.stop()
        channel.close()
        onEnd?()
    }
}

/// Decodes on the receiving task: H.264 to the display layer, the OBS feed and the recorder.
final class StreamPipeline: @unchecked Sendable {
    let layer = AVSampleBufferDisplayLayer()
    var feed: FeedServer?
    var onNeedKeyframe: (() -> Void)?
    var onFps: ((Int) -> Void)?

    private let lock = NSLock()
    private var sps: Data?
    private var pps: Data?
    private var format: CMVideoFormatDescription?
    private var config: Data?
    private var waitingForKey = true
    private var lastKeyRequest = Date.distantPast
    private var writer: AVAssetWriter?
    private var writerInput: AVAssetWriterInput?
    private var writerStarted = false
    private var frames = 0
    private var fpsWindow = Date()

    init() {
        layer.videoGravity = .resizeAspect
        layer.backgroundColor = NSColor.black.cgColor
    }

    func handle(_ f: StreamFrame.Parsed) {
        var slices: [Data] = []
        var newParams = false
        for n in H264.nalUnits(f.data) {
            switch H264.type(n) {
            case 7: if n != sps { sps = n; newParams = true }
            case 8: if n != pps { pps = n; newParams = true }
            case 9: break
            default: slices.append(n)
            }
        }
        if newParams || format == nil, let sps, let pps {
            if let fd = H264.formatDescription(sps: sps, pps: pps) {
                format = fd
                let sc = Data([0, 0, 0, 1])
                config = sc + sps + sc + pps
            }
        }
        guard !slices.isEmpty else { return }
        let isKey = f.isKey || slices.contains { H264.type($0) == 5 }
        guard let format else { requestKey(); return }
        if waitingForKey {
            guard isKey else { requestKey(); return }
            waitingForKey = false
        }
        let pts = CMTime(value: Int64(f.ptsMicros), timescale: 1_000_000)
        guard let sample = H264.sampleBuffer(nals: slices, format: format, pts: pts, isKey: isKey) else { return }
        let r = layer.sampleBufferRenderer
        if r.status == .failed || r.requiresFlushToResumeDecoding {
            r.flush()
            waitingForKey = true
            requestKey()
            return
        }
        r.enqueue(sample)
        feed?.push(f, config: config)
        lock.lock()
        if let input = writerInput, let writer {
            if !writerStarted, isKey {
                writer.startSession(atSourceTime: pts)
                writerStarted = true
            }
            if writerStarted, input.isReadyForMoreMediaData { input.append(sample) }
        }
        lock.unlock()
        frames += 1
        let now = Date()
        if now.timeIntervalSince(fpsWindow) >= 1 {
            onFps?(frames)
            frames = 0
            fpsWindow = now
        }
    }

    private func requestKey() {
        guard Date().timeIntervalSince(lastKeyRequest) > 0.5 else { return }
        lastKeyRequest = Date()
        onNeedKeyframe?()
    }

    func startRecording(to url: URL, rotation: Int) throws {
        lock.lock()
        defer { lock.unlock() }
        let w = try AVAssetWriter(outputURL: url, fileType: .mov)
        let input = AVAssetWriterInput(mediaType: .video, outputSettings: nil, sourceFormatHint: format)
        input.expectsMediaDataInRealTime = true
        input.transform = CGAffineTransform(rotationAngle: CGFloat(rotation) * .pi / 180)
        guard w.canAdd(input) else { throw NDError.badFrame("can't add video track") }
        w.add(input)
        guard w.startWriting() else { throw w.error ?? NDError.badFrame("can't start writing") }
        writer = w
        writerInput = input
        writerStarted = false
    }

    func stopRecording(_ done: @escaping @Sendable (Bool) -> Void) {
        lock.lock()
        let w = writer
        let started = writerStarted
        writerInput?.markAsFinished()
        writer = nil
        writerInput = nil
        writerStarted = false
        lock.unlock()
        guard let w else { done(false); return }
        guard started else { w.cancelWriting(); done(false); return }
        w.finishWriting { done(w.status == .completed) }
    }
}

// MARK: - Window

@MainActor
final class StreamWindowController: NSObject, NSWindowDelegate {
    let session: StreamSession
    private let window: NSWindow
    private var bag: [Any] = []

    init(session: StreamSession) {
        self.session = session
        let video = VideoView(session: session)
        let host = NSHostingView(rootView: StreamOverlay(session: session))
        host.translatesAutoresizingMaskIntoConstraints = false
        video.addSubview(host)
        NSLayoutConstraint.activate([
            host.leadingAnchor.constraint(equalTo: video.leadingAnchor),
            host.trailingAnchor.constraint(equalTo: video.trailingAnchor),
            host.topAnchor.constraint(equalTo: video.topAnchor),
            host.bottomAnchor.constraint(equalTo: video.bottomAnchor),
        ])
        let size = Self.initialSize(for: session.shownSize, kind: session.kind)
        window = NSWindow(contentRect: NSRect(origin: .zero, size: size),
                          styleMask: [.titled, .closable, .miniaturizable, .resizable, .fullSizeContentView],
                          backing: .buffered, defer: false)
        super.init()
        window.title = session.title
        window.titlebarAppearsTransparent = true
        window.titleVisibility = .hidden
        window.isMovableByWindowBackground = session.kind == .camera
        window.backgroundColor = .black
        window.isReleasedWhenClosed = false
        window.contentView = video
        window.contentAspectRatio = session.shownSize
        window.delegate = self
        window.collectionBehavior = [.fullScreenPrimary]
        window.center()
        let sizeSink = session.$videoSize.combineLatest(session.$rotation).sink { [weak self] _, _ in
            DispatchQueue.main.async { self?.fitAspect() }
        }
        let floatSink = session.$floating.sink { [weak self] f in self?.window.level = f ? .floating : .normal }
        bag = [sizeSink, floatSink]
    }

    private static func initialSize(for shown: CGSize, kind: StreamSession.Kind) -> CGSize {
        let screen = NSScreen.main?.visibleFrame.size ?? CGSize(width: 1440, height: 900)
        let maxW = screen.width * (kind == .camera ? 0.5 : 0.6), maxH = screen.height * 0.8
        let s = min(maxW / max(shown.width, 1), maxH / max(shown.height, 1))
        return CGSize(width: (shown.width * s).rounded(), height: (shown.height * s).rounded())
    }

    func show() {
        NSApp.activate(ignoringOtherApps: true)
        window.makeKeyAndOrderFront(nil)
        if session.kind == .screen { window.makeFirstResponder(window.contentView) }
    }

    func close() { window.close() }

    /// Keeps the window's shape in step with the picture (phone rotated, quality changed).
    private func fitAspect() {
        let shown = session.shownSize
        guard shown.width > 0, shown.height > 0 else { return }
        window.contentAspectRatio = shown
        var frame = window.frame
        let content = window.contentRect(forFrameRect: frame)
        let area = content.width * content.height
        let w = (area * shown.width / shown.height).squareRoot()
        let h = w * shown.height / shown.width
        let newContent = NSRect(x: content.midX - w / 2, y: content.midY - h / 2, width: w, height: h)
        frame = window.frameRect(forContentRect: newContent)
        if let vis = window.screen?.visibleFrame {
            frame.size.width = min(frame.width, vis.width)
            frame.size.height = min(frame.height, vis.height)
            frame.origin.x = min(max(frame.minX, vis.minX), vis.maxX - frame.width)
            frame.origin.y = min(max(frame.minY, vis.minY), vis.maxY - frame.height)
        }
        window.setFrame(frame, display: true, animate: true)
        window.contentView?.needsLayout = true
    }

    func windowWillClose(_ notification: Notification) {
        session.stop()
    }
}

/// Shows the decoded picture, turned and mirrored; on a screen mirror, forwards mouse and keys to the phone.
final class VideoView: NSView {
    private let session: StreamSession
    private let videoLayer: AVSampleBufferDisplayLayer
    private var tracking: NSTrackingArea?
    private var lastMove = Date.distantPast
    private var observers: [Any] = []

    @MainActor init(session: StreamSession) {
        self.session = session
        videoLayer = session.pipeline.layer
        super.init(frame: .zero)
        wantsLayer = true
        layer?.backgroundColor = NSColor.black.cgColor
        layer?.addSublayer(videoLayer)
        let s1 = session.$rotation.sink { [weak self] _ in DispatchQueue.main.async { self?.needsLayout = true } }
        let s2 = session.$mirror.sink { [weak self] _ in DispatchQueue.main.async { self?.needsLayout = true } }
        let s3 = session.$videoSize.sink { [weak self] _ in DispatchQueue.main.async { self?.needsLayout = true } }
        observers = [s1, s2, s3]
    }

    required init?(coder: NSCoder) { fatalError() }

    override var acceptsFirstResponder: Bool { true }
    override var mouseDownCanMoveWindow: Bool { session.kind == .camera }

    /// Where the picture sits in the view.
    @MainActor private var pictureRect: CGRect {
        let shown = session.shownSize
        let s = min(bounds.width / max(shown.width, 1), bounds.height / max(shown.height, 1))
        let w = shown.width * s, h = shown.height * s
        return CGRect(x: bounds.midX - w / 2, y: bounds.midY - h / 2, width: w, height: h)
    }

    override func layout() {
        super.layout()
        MainActor.assumeIsolated {
            CATransaction.begin()
            CATransaction.setDisableActions(true)
            let r = pictureRect
            let turned = session.rotation % 180 != 0
            videoLayer.bounds = CGRect(x: 0, y: 0, width: turned ? r.height : r.width, height: turned ? r.width : r.height)
            videoLayer.position = CGPoint(x: r.midX, y: r.midY)
            let angle = -CGFloat(session.rotation) * .pi / 180
            let t = CGAffineTransform(rotationAngle: angle).concatenating(CGAffineTransform(scaleX: session.mirror ? -1 : 1, y: 1))
            videoLayer.setAffineTransform(t)
            CATransaction.commit()
        }
    }

    override func updateTrackingAreas() {
        super.updateTrackingAreas()
        if let tracking { removeTrackingArea(tracking) }
        let t = NSTrackingArea(rect: bounds, options: [.mouseEnteredAndExited, .activeAlways, .inVisibleRect, .mouseMoved], owner: self)
        addTrackingArea(t)
        tracking = t
    }

    override func mouseEntered(with event: NSEvent) { MainActor.assumeIsolated { session.hover = true } }
    override func mouseExited(with event: NSEvent) { MainActor.assumeIsolated { session.hover = false } }

    // MARK: Input (screen mirror only)

    /// Phone-screen coordinates, 0…1 from the top left.
    @MainActor private func phonePoint(_ event: NSEvent) -> (Double, Double)? {
        let p = convert(event.locationInWindow, from: nil)
        let r = pictureRect
        guard r.width > 0, r.height > 0 else { return nil }
        let x = (p.x - r.minX) / r.width, y = 1 - (p.y - r.minY) / r.height
        return (min(max(x, 0), 1), min(max(y, 0), 1))
    }

    @MainActor private func sendPointer(_ action: String, _ event: NSEvent) {
        guard session.kind == .screen, let (x, y) = phonePoint(event) else { return }
        if !session.canControl {
            if action == "down" { session.show(note: "Turn on Phone control in NotchDrop's settings on \(session.peerName)") }
            return
        }
        session.send(["t": "input", "a": action, "x": x, "y": y])
    }

    override func mouseDown(with event: NSEvent) {
        MainActor.assumeIsolated {
            guard session.kind == .screen else { super.mouseDown(with: event); return }
            window?.makeFirstResponder(self)
            sendPointer("down", event)
        }
    }

    override func mouseDragged(with event: NSEvent) {
        MainActor.assumeIsolated {
            guard session.kind == .screen else { super.mouseDragged(with: event); return }
            // About 60 updates a second is plenty for a gesture path.
            guard Date().timeIntervalSince(lastMove) > 0.016 else { return }
            lastMove = Date()
            sendPointer("move", event)
        }
    }

    override func mouseUp(with event: NSEvent) {
        MainActor.assumeIsolated {
            guard session.kind == .screen else { super.mouseUp(with: event); return }
            sendPointer("up", event)
        }
    }

    /// Right-click is Back, like a mouse on Android.
    override func rightMouseDown(with event: NSEvent) {
        MainActor.assumeIsolated {
            guard session.kind == .screen, session.canControl else { return }
            session.send(["t": "input", "a": "key", "code": "back"])
        }
    }

    override func scrollWheel(with event: NSEvent) {
        MainActor.assumeIsolated {
            guard session.kind == .screen, session.canControl, let (x, y) = phonePoint(event) else { return }
            let scale = event.hasPreciseScrollingDeltas ? 1.0 : 12.0
            let dx = Double(event.scrollingDeltaX) * scale, dy = Double(event.scrollingDeltaY) * scale
            guard dx != 0 || dy != 0 else { return }
            session.send(["t": "input", "a": "scroll", "x": x, "y": y, "dx": dx, "dy": dy])
        }
    }

    override func keyDown(with event: NSEvent) {
        MainActor.assumeIsolated {
            guard session.kind == .screen, session.canControl else { super.keyDown(with: event); return }
            if let msg = KeyMap.message(for: event) {
                session.send(msg)
            } else {
                super.keyDown(with: event)
            }
        }
    }

    override func performKeyEquivalent(with event: NSEvent) -> Bool {
        MainActor.assumeIsolated {
            guard session.kind == .screen, session.canControl, window?.firstResponder === self,
                  let msg = KeyMap.message(for: event) else { return super.performKeyEquivalent(with: event) }
            session.send(msg)
            return true
        }
    }
}

/// Mac keys to phone input messages. Shared with Universal Control.
enum KeyMap {
    static func message(for event: NSEvent) -> [String: Any]? {
        let cmd = event.modifierFlags.contains(.command)
        if cmd {
            switch event.charactersIgnoringModifiers?.lowercased() {
            case "v":
                guard let text = NSPasteboard.general.string(forType: .string) else { return nil }
                return ["t": "input", "a": "key", "text": text]
            case "h": return ["t": "input", "a": "key", "code": "home"]
            case "a": return ["t": "input", "a": "key", "code": "selectAll"]
            case "c": return ["t": "input", "a": "key", "code": "copy"]
            case "x": return ["t": "input", "a": "key", "code": "cut"]
            case "z": return ["t": "input", "a": "key", "code": "undo"]
            default: return nil
            }
        }
        let code: String? = switch event.keyCode {
        case 53: "back" // esc
        case 36, 76: "enter"
        case 51: "backspace"
        case 117: "delete"
        case 123: "left"
        case 124: "right"
        case 125: "down"
        case 126: "up"
        case 48: "tab"
        default: nil
        }
        if let code { return ["t": "input", "a": "key", "code": code] }
        guard let text = event.characters, !text.isEmpty,
              text.unicodeScalars.allSatisfy({ !CharacterSet.controlCharacters.contains($0) && $0.value < 0xF700 })
        else { return nil }
        return ["t": "input", "a": "key", "text": text]
    }
}

// MARK: - Controls

private struct StreamOverlay: View {
    @ObservedObject var session: StreamSession
    @StateObject private var showObs = Box(false)

    var body: some View {
        ZStack {
            if let note = session.note {
                VStack {
                    Text(note)
                        .font(.system(size: 12, weight: .medium))
                        .padding(.horizontal, 12).padding(.vertical, 6)
                        .background(Capsule().fill(.black.opacity(0.65)))
                        .foregroundStyle(.white)
                        .padding(.top, 34)
                    Spacer()
                }
                .transition(.opacity)
            }
            VStack {
                Spacer()
                bar
                    .padding(.horizontal, 10).padding(.vertical, 7)
                    .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(.ultraThinMaterial))
                    .environment(\.colorScheme, .dark)
                    .padding(.bottom, 12)
            }
            // Hidden unless the pointer is over the window, so a window capture in OBS shows only the picture.
            .opacity(session.hover ? 1 : 0)
            .animation(.easeOut(duration: 0.2), value: session.hover)
            if session.recording != nil {
                VStack {
                    HStack {
                        Spacer()
                        Circle().fill(.red).frame(width: 10, height: 10).padding(12).padding(.top, 22)
                    }
                    Spacer()
                }
                .allowsHitTesting(false)
            }
        }
        .animation(.easeOut(duration: 0.2), value: session.note)
    }

    @ViewBuilder private var bar: some View {
        HStack(spacing: 6) {
            if session.kind == .camera { cameraControls } else { screenControls }
            Divider().frame(height: 18)
            qualityMenu
            iconButton(session.recording != nil ? "stop.circle.fill" : "record.circle",
                       session.recording != nil ? "Stop recording" : "Record to the received folder",
                       tint: session.recording != nil ? .red : nil) { session.toggleRecording() }
            iconButton(session.floating ? "pin.fill" : "pin", session.floating ? "Stop keeping on top" : "Keep on top") {
                session.floating.toggle()
            }
            if session.fpsShown > 0 {
                Text("\(session.fpsShown) fps").font(.system(size: 10, weight: .medium).monospacedDigit())
                    .foregroundStyle(.secondary).frame(minWidth: 36)
            }
        }
        .foregroundStyle(.white)
    }

    @ViewBuilder private var cameraControls: some View {
        if session.lenses.count > 1 {
            iconButton("arrow.triangle.2.circlepath.camera", "Switch camera") {
                let i = session.lenses.firstIndex(of: session.lens) ?? 0
                session.setLens(session.lenses[(i + 1) % session.lenses.count])
            }
        }
        if session.zoomRange.upperBound > session.zoomRange.lowerBound {
            HStack(spacing: 4) {
                Text(String(format: "%.1f×", session.zoom)).font(.system(size: 10, weight: .semibold).monospacedDigit())
                    .frame(width: 32)
                Slider(value: Binding(get: { session.zoom }, set: { session.setZoom($0) }), in: session.zoomRange)
                    .frame(width: 90).controlSize(.mini)
            }
        }
        if session.hasTorch {
            iconButton(session.torch ? "flashlight.on.fill" : "flashlight.off.fill", "Torch",
                       tint: session.torch ? .yellow : nil) { session.toggleTorch() }
        }
        iconButton("arrow.left.and.right.righttriangle.left.righttriangle.right", "Mirror the preview (not the recording or OBS)",
                   tint: session.mirror ? .accentColor : nil) { session.mirror.toggle() }
        iconButton("camera.shutter.button", "Take a full-quality photo and send it here") { session.shutter() }
        Button {
            showObs.value.toggle()
        } label: {
            HStack(spacing: 3) {
                Image(systemName: "dot.radiowaves.left.and.right")
                Text("OBS").font(.system(size: 10, weight: .semibold))
                if session.obsViewers > 0 { Circle().fill(.green).frame(width: 6, height: 6) }
            }
            .frame(height: 26).padding(.horizontal, 6)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .help("Use the camera in Zoom, FaceTime or Chrome through OBS")
        .popover(isPresented: $showObs.value, arrowEdge: .top) { ObsHelp(session: session) }
    }

    @ViewBuilder private var screenControls: some View {
        iconButton("chevron.backward", "Back (Esc or right-click)") { session.send(["t": "input", "a": "key", "code": "back"]) }
            .disabled(!session.canControl)
        iconButton("circle", "Home (⌘H)") { session.send(["t": "input", "a": "key", "code": "home"]) }
            .disabled(!session.canControl)
        iconButton("square.on.square", "Recent apps") { session.send(["t": "input", "a": "key", "code": "recents"]) }
            .disabled(!session.canControl)
        iconButton("bell", "Notifications") { session.send(["t": "input", "a": "key", "code": "notifications"]) }
            .disabled(!session.canControl)
        if !session.canControl {
            Text("View only").font(.system(size: 10, weight: .medium)).foregroundStyle(.secondary)
                .help("Turn on Phone control in NotchDrop's settings on the phone to click and type")
        }
    }

    private var qualityMenu: some View {
        Menu {
            ForEach(session.kind == .camera ? ["720p", "1080p", "4K"] : ["720p", "1080p", "Native"], id: \.self) { q in
                Section(q) {
                    ForEach([30, 60], id: \.self) { f in
                        Button {
                            session.setQuality(q, fps: f)
                        } label: {
                            if session.quality == q && session.fps == f { Label("\(q) · \(f) fps", systemImage: "checkmark") }
                            else { Text("\(q) · \(f) fps") }
                        }
                    }
                }
            }
        } label: {
            Text("\(session.quality) · \(session.fps)").font(.system(size: 10, weight: .semibold))
        }
        .menuStyle(.borderlessButton)
        .menuIndicator(.hidden)
        .fixedSize()
        .help("Quality")
    }

    private func iconButton(_ symbol: String, _ help: String, tint: Color? = nil, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(tint ?? .white)
                .frame(width: 28, height: 26)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .help(help)
    }
}

private struct ObsHelp: View {
    @ObservedObject var session: StreamSession
    static let address = "tcp://127.0.0.1:\(FeedServer.port)"

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Use as a webcam").font(.headline)
            Text("OBS turns this camera into one that Zoom, FaceTime, Meet and Chrome can pick.")
                .font(.callout).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            VStack(alignment: .leading, spacing: 6) {
                step(1, "In OBS, add a **Media Source**, untick **Local File**, and paste this address as the **Input**:")
                HStack {
                    Text(Self.address).font(.system(.callout, design: .monospaced)).textSelection(.enabled)
                    Spacer()
                    Button("Copy") {
                        NSPasteboard.general.clearContents()
                        NSPasteboard.general.setString(Self.address, forType: .string)
                    }
                }
                .padding(8).background(RoundedRectangle(cornerRadius: 6).fill(Color.primary.opacity(0.06)))
                step(2, "Set **Network Buffering** to 0 and tick **Restart playback when source becomes active**.")
                step(3, "Click **Start Virtual Camera**, then pick **OBS Virtual Camera** in your call app.")
            }
            Text("The picture comes as the camera shoots it; if it's on its side, right-click the source › Transform › Rotate. Or add this window with **macOS Screen Capture** instead.")
                .font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            HStack(spacing: 5) {
                Circle().fill(session.obsViewers > 0 ? .green : .secondary.opacity(0.5)).frame(width: 7, height: 7)
                Text(session.obsViewers > 0 ? "OBS is connected" : "Waiting for OBS").font(.caption)
            }
        }
        .padding(16)
        .frame(width: 340)
    }

    private func step(_ n: Int, _ text: LocalizedStringKey) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 6) {
            Text("\(n).").font(.callout.monospacedDigit()).foregroundStyle(.secondary)
            Text(text).font(.callout).fixedSize(horizontal: false, vertical: true)
        }
    }
}
