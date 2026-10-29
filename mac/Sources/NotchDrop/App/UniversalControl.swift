import AppKit
import Combine
import CoreGraphics

/// Where the phone sits next to the Mac's displays, arranged like System Settings › Displays.
/// All rectangles are in global display coordinates (top-left origin, y down), as CGDisplayBounds gives them.
enum PhonePlacement {
    struct Screen {
        let id: CGDirectDisplayID
        let uuid: String
        let frame: CGRect
        let name: String
    }

    struct Resolved {
        let screen: Screen
        let edge: UniversalControl.Edge
        /// The part of the edge the phone covers, as fractions of the edge's length.
        let start: CGFloat
        let span: CGFloat
    }

    static func screens() -> [Screen] {
        var count: UInt32 = 0
        CGGetActiveDisplayList(0, nil, &count)
        var ids = [CGDirectDisplayID](repeating: 0, count: Int(count))
        CGGetActiveDisplayList(count, &ids, &count)
        return ids.prefix(Int(count)).map { id in
            let uuid = CGDisplayCreateUUIDFromDisplayID(id).map { CFUUIDCreateString(nil, $0.takeRetainedValue()) as String } ?? "\(id)"
            let name = NSScreen.screens.first {
                ($0.deviceDescription[NSDeviceDescriptionKey("NSScreenNumber")] as? NSNumber)?.uint32Value == id
            }?.localizedName ?? "Display"
            return Screen(id: id, uuid: uuid, frame: CGDisplayBounds(id), name: name)
        }
    }

    /// A phone drawn at roughly its real size next to a laptop screen.
    static func phoneSize(_ screens: [Screen]) -> CGSize {
        let main = screens.first { CGDisplayIsMain($0.id) != 0 }?.frame ?? screens.first?.frame ?? CGRect(x: 0, y: 0, width: 1512, height: 982)
        let h = max(300, main.height * 0.55)
        return CGSize(width: h * 0.46, height: h)
    }

    static func edgeLength(_ frame: CGRect, _ edge: UniversalControl.Edge) -> CGFloat {
        edge == .left || edge == .right ? frame.height : frame.width
    }

    static func span(_ screen: Screen, _ edge: UniversalControl.Edge, _ phone: CGSize) -> CGFloat {
        let along = edge == .left || edge == .right ? phone.height : phone.width
        return min(1, along / edgeLength(screen.frame, edge))
    }

    static func resolve(display uuid: String, edge: UniversalControl.Edge, offset: Double) -> Resolved? {
        let all = screens()
        guard let s = all.first(where: { $0.uuid == uuid }) ?? all.first(where: { CGDisplayIsMain($0.id) != 0 }) ?? all.first
        else { return nil }
        let span = span(s, edge, phoneSize(all))
        let start = min(max(CGFloat(offset) - span / 2, 0), 1 - span)
        return Resolved(screen: s, edge: edge, start: start, span: span)
    }

    /// The phone's rectangle next to `screen`, centred at `offset` along `edge`.
    static func phoneRect(_ screen: Screen, _ edge: UniversalControl.Edge, offset: Double, phone: CGSize) -> CGRect {
        let f = screen.frame
        let sp = span(screen, edge, phone)
        let centre = min(max(CGFloat(offset), sp / 2), 1 - sp / 2)
        switch edge {
        case .left: return CGRect(x: f.minX - phone.width, y: f.minY + f.height * centre - phone.height / 2, width: phone.width, height: phone.height)
        case .right: return CGRect(x: f.maxX, y: f.minY + f.height * centre - phone.height / 2, width: phone.width, height: phone.height)
        case .top: return CGRect(x: f.minX + f.width * centre - phone.width / 2, y: f.minY - phone.height, width: phone.width, height: phone.height)
        case .bottom: return CGRect(x: f.minX + f.width * centre - phone.width / 2, y: f.maxY, width: phone.width, height: phone.height)
        }
    }

    /// Snaps a dragged phone rectangle to the nearest free outer edge of any display.
    static func snap(_ rect: CGRect, screens: [Screen], phone: CGSize) -> (uuid: String, edge: UniversalControl.Edge, offset: Double)? {
        let c = CGPoint(x: rect.midX, y: rect.midY)
        var best: (uuid: String, edge: UniversalControl.Edge, offset: Double, d: CGFloat)?
        for s in screens {
            let f = s.frame
            for edge in UniversalControl.Edge.allCases {
                let offset: CGFloat = edge == .left || edge == .right
                    ? (c.y - f.minY) / f.height
                    : (c.x - f.minX) / f.width
                let r = phoneRect(s, edge, offset: Double(min(max(offset, 0), 1)), phone: phone)
                // Skip spots that overlap another display.
                if screens.contains(where: { $0.frame.insetBy(dx: 2, dy: 2).intersects(r) }) { continue }
                let d = hypot(r.midX - c.x, r.midY - c.y)
                if best == nil || d < best!.d { best = (s.uuid, edge, Double(min(max(offset, 0), 1)), d) }
            }
        }
        return best.map { ($0.uuid, $0.edge, $0.offset) }
    }
}

/// Push the pointer past the edge where the phone sits and the mouse and keyboard drive the phone
/// (through its Phone control accessibility service) until the pointer comes back the same way.
/// Dragging files out over that edge sends them instead.
@MainActor
final class UniversalControl {
    static let shared = UniversalControl()

    /// Which side of the display the phone sits on.
    enum Edge: String, CaseIterable {
        case left, right, top, bottom

        var title: String { rawValue.capitalized }
        /// The phone's edge the pointer comes in through.
        var opposite: String {
            switch self {
            case .left: "right"
            case .right: "left"
            case .top: "bottom"
            case .bottom: "top"
            }
        }
    }

    private enum State { case idle, connecting, controlling }

    /// Without a reply for this long the phone is gone (Wi-Fi dropped, app killed): take the pointer back.
    private static let lostAfter: TimeInterval = 3.5

    private let app = AppModel.shared
    private let prefs = Prefs.shared
    private var tap: CFMachPort?
    private var source: CFRunLoopSource?
    private var state = State.idle
    private var push: CGFloat = 0
    private var channel: SecureChannel?
    private var reader: Task<Void, Never>?
    private var heartbeat: Task<Void, Never>?
    private var heardAt = Date()
    /// Where the pointer crossed, to come back to.
    private var crossing: PhonePlacement.Resolved?
    private var lastPos = 0.5
    private var dragCount = 0
    private var bag = Set<AnyCancellable>()

    private init() {}

    func start() {
        prefs.$controlEnabled.combineLatest(app.$peer.map { $0 != nil })
            .sink { [weak self] on, paired in
                DispatchQueue.main.async { self?.update(on && paired) }
            }
            .store(in: &bag)
        // Accessibility may be granted after launch.
        Timer.scheduledTimer(withTimeInterval: 3, repeats: true) { _ in
            MainActor.assumeIsolated { [weak self] in
                guard let self, self.tap == nil, self.prefs.controlEnabled, self.app.peer != nil else { return }
                self.update(true)
            }
        }
        // Never leave the pointer captured across sleep, the lock screen or a user switch.
        let ws = NSWorkspace.shared.notificationCenter
        for name in [NSWorkspace.willSleepNotification, NSWorkspace.screensDidSleepNotification, NSWorkspace.sessionDidResignActiveNotification] {
            ws.addObserver(forName: name, object: nil, queue: .main) { _ in
                MainActor.assumeIsolated { UniversalControl.shared.release(at: nil) }
            }
        }
    }

    /// Called when the app quits.
    func stop() {
        release(at: nil)
        uninstall()
    }

    private func update(_ on: Bool) {
        if on, tap == nil, AXIsProcessTrusted() { install() }
        if !on, tap != nil {
            release(at: nil)
            uninstall()
        }
    }

    private func install() {
        let types: [CGEventType] = [
            .mouseMoved, .leftMouseDown, .leftMouseUp, .leftMouseDragged, .rightMouseDown, .rightMouseUp,
            .rightMouseDragged, .otherMouseDown, .otherMouseUp, .otherMouseDragged, .scrollWheel, .keyDown, .keyUp, .flagsChanged,
        ]
        let mask = types.reduce(CGEventMask(0)) { $0 | (1 << $1.rawValue) }
        guard let t = CGEvent.tapCreate(
            tap: .cgSessionEventTap, place: .headInsertEventTap, options: .defaultTap, eventsOfInterest: mask,
            callback: { _, type, event, ctx in
                guard let ctx else { return Unmanaged.passUnretained(event) }
                let me = Unmanaged<UniversalControl>.fromOpaque(ctx).takeUnretainedValue()
                return MainActor.assumeIsolated { me.handle(type, event) }
            },
            userInfo: Unmanaged.passUnretained(self).toOpaque()
        ) else { return }
        tap = t
        source = CFMachPortCreateRunLoopSource(nil, t, 0)
        CFRunLoopAddSource(CFRunLoopGetMain(), source, .commonModes)
        CGEvent.tapEnable(tap: t, enable: true)
    }

    private func uninstall() {
        if let t = tap { CGEvent.tapEnable(tap: t, enable: false) }
        if let s = source { CFRunLoopRemoveSource(CFRunLoopGetMain(), s, .commonModes) }
        tap = nil
        source = nil
    }

    // MARK: Events

    private func handle(_ type: CGEventType, _ event: CGEvent) -> Unmanaged<CGEvent>? {
        if type == .tapDisabledByTimeout || type == .tapDisabledByUserInput {
            if let t = tap { CGEvent.tapEnable(tap: t, enable: true) }
            return Unmanaged.passUnretained(event)
        }
        switch state {
        case .idle:
            watchEdge(type, event)
            return Unmanaged.passUnretained(event)
        case .connecting:
            return Unmanaged.passUnretained(event)
        case .controlling:
            forward(type, event)
            return nil
        }
    }

    private func watchEdge(_ type: CGEventType, _ event: CGEvent) {
        if type == .leftMouseDown { dragCount = NSPasteboard(name: .drag).changeCount }
        guard type == .mouseMoved || type == .leftMouseDragged,
              let place = PhonePlacement.resolve(
                  display: prefs.controlDisplay, edge: Edge(rawValue: prefs.controlEdge) ?? .right, offset: prefs.controlOffset)
        else { return }
        let f = place.screen.frame
        let p = event.location
        let dx = CGFloat(event.getDoubleValueField(.mouseEventDeltaX))
        let dy = CGFloat(event.getDoubleValueField(.mouseEventDeltaY))
        guard f.insetBy(dx: -1, dy: -1).contains(p) else { push = 0; return }
        let (atEdge, along, outward): (Bool, CGFloat, CGFloat) = switch place.edge {
        case .left: (p.x <= f.minX + 1, (p.y - f.minY) / f.height, -dx)
        case .right: (p.x >= f.maxX - 1, (p.y - f.minY) / f.height, dx)
        case .top: (p.y <= f.minY + 1, (p.x - f.minX) / f.width, -dy)
        case .bottom: (p.y >= f.maxY - 1, (p.x - f.minX) / f.width, dy)
        }
        // Only the stretch of edge the phone is placed against.
        guard atEdge, along >= place.start, along <= place.start + place.span else {
            push = 0
            return
        }
        push += max(0, outward)
        guard push > 40 else { return }
        push = 0
        crossing = place
        let pos = Double(min(max((along - place.start) / place.span, 0), 1))
        if type == .leftMouseDragged {
            dropFiles()
        } else {
            enter(edge: place.edge, pos: pos)
        }
    }

    /// A file drag pushed past the edge: send what's being dragged.
    private func dropFiles() {
        let pb = NSPasteboard(name: .drag)
        guard pb.changeCount != dragCount,
              let urls = pb.readObjects(forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true]) as? [URL],
              !urls.isEmpty
        else { return }
        dragCount = pb.changeCount
        prefs.haptic(.levelChange)
        app.send(urls: urls, texts: [])
    }

    private func enter(edge: Edge, pos: Double) {
        state = .connecting
        lastPos = pos
        Task {
            guard let c = await app.openSession() else {
                state = .idle
                app.controlNote("\(app.peerName) isn't reachable")
                return
            }
            guard state == .connecting else { c.channel.close(); return }
            channel = c.channel
            state = .controlling
            heardAt = Date()
            CGAssociateMouseAndMouseCursorPosition(0)
            Self.hideCursor(true)
            prefs.haptic(.levelChange)
            let ch = c.channel
            ch.post(json: ["t": "input", "a": "enter", "edge": edge.opposite, "pos": pos])
            reader = Task {
                do {
                    while !Task.isCancelled {
                        guard case .json(let m) = try await ch.receive() else { continue }
                        guard self.channel === ch else { return }
                        self.heardAt = Date()
                        if m["t"] as? String == "input_exit" {
                            if let msg = m["message"] as? String { self.app.controlNote(msg) }
                            self.release(at: (m["pos"] as? NSNumber)?.doubleValue ?? pos, notify: false)
                            return
                        }
                    }
                } catch {
                    guard self.channel === ch else { return }
                    self.app.controlNote("Lost \(self.app.peerName). The pointer is back on the Mac.")
                    self.release(at: self.lastPos, notify: false)
                }
            }
            // The phone answers every `alive`. Silence means the link is gone even if the socket hasn't noticed yet.
            heartbeat = Task {
                while !Task.isCancelled, self.channel === ch {
                    ch.post(json: ["t": "input", "a": "alive"])
                    try? await Task.sleep(for: .seconds(1))
                    guard self.channel === ch else { return }
                    if Date().timeIntervalSince(self.heardAt) > Self.lostAfter {
                        self.app.controlNote("Lost \(self.app.peerName). The pointer is back on the Mac.")
                        self.release(at: self.lastPos, notify: false)
                        return
                    }
                }
            }
        }
    }

    /// Hands the pointer back to the Mac, at `pos` along the stretch of edge it crossed (or where it is).
    private func release(at pos: Double?, notify: Bool = true) {
        if state == .connecting { state = .idle }
        guard state == .controlling else { return }
        state = .idle
        let ch = channel
        channel = nil
        reader?.cancel()
        heartbeat?.cancel()
        if let ch {
            if notify { ch.post(json: ["t": "input", "a": "leave"]) }
            ch.post(json: ["t": "bye"])
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { ch.close() }
        }
        if let pos, let place = crossing {
            let f = place.screen.frame
            let a = place.start + place.span * CGFloat(pos)
            let p: CGPoint = switch place.edge {
            case .left: CGPoint(x: f.minX + 8, y: f.minY + f.height * a)
            case .right: CGPoint(x: f.maxX - 8, y: f.minY + f.height * a)
            case .top: CGPoint(x: f.minX + f.width * a, y: f.minY + 8)
            case .bottom: CGPoint(x: f.minX + f.width * a, y: f.maxY - 8)
            }
            CGWarpMouseCursorPosition(p)
        }
        CGAssociateMouseAndMouseCursorPosition(1)
        Self.hideCursor(false)
    }

    private func forward(_ type: CGEventType, _ event: CGEvent) {
        guard let ch = channel else { return }
        switch type {
        case .mouseMoved, .leftMouseDragged, .rightMouseDragged, .otherMouseDragged:
            let dx = event.getDoubleValueField(.mouseEventDeltaX), dy = event.getDoubleValueField(.mouseEventDeltaY)
            if dx != 0 || dy != 0 { ch.post(json: ["t": "input", "a": "rel", "dx": dx, "dy": dy]) }
        case .leftMouseDown: ch.post(json: ["t": "input", "a": "press"])
        case .leftMouseUp: ch.post(json: ["t": "input", "a": "release"])
        case .rightMouseDown: ch.post(json: ["t": "input", "a": "key", "code": "back"])
        case .otherMouseDown: ch.post(json: ["t": "input", "a": "key", "code": "home"])
        case .scrollWheel:
            guard let e = NSEvent(cgEvent: event) else { return }
            let k: Double = e.hasPreciseScrollingDeltas ? 1 : 12
            ch.post(json: ["t": "input", "a": "scroll", "dx": Double(e.scrollingDeltaX) * k, "dy": Double(e.scrollingDeltaY) * k])
        case .keyDown:
            guard let e = NSEvent(cgEvent: event) else { return }
            // ⌘⎋ always brings the pointer back.
            if e.keyCode == 53, e.modifierFlags.contains(.command) {
                release(at: lastPos)
                return
            }
            if let m = KeyMap.message(for: e) { ch.post(json: m) }
        default: break
        }
    }

    /// macOS only hides the pointer for the frontmost app, unless the connection opts in.
    private static func hideCursor(_ hide: Bool) {
        typealias DefaultConnection = @convention(c) () -> Int32
        typealias SetProperty = @convention(c) (Int32, Int32, CFString, CFTypeRef) -> Int32
        if hide, let h = dlopen(nil, RTLD_NOW),
           let a = dlsym(h, "_CGSDefaultConnection"), let b = dlsym(h, "CGSSetConnectionProperty") {
            let cid = unsafeBitCast(a, to: DefaultConnection.self)()
            _ = unsafeBitCast(b, to: SetProperty.self)(cid, cid, "SetsCursorInBackground" as CFString, kCFBooleanTrue)
        }
        if hide { CGDisplayHideCursor(CGMainDisplayID()) } else { CGDisplayShowCursor(CGMainDisplayID()) }
    }
}
