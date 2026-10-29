import AppKit
import Combine
import SwiftUI

/// Buttons in the open notch's header.
enum NotchAction { case sendClipboard, openFolder, settings }

enum NotchPhase: Equatable {
    /// `mini`: a long transfer shrunk to a sliver under the notch with just its progress.
    case collapsed, peek, open, activity, mini
}

@MainActor
final class NotchUIModel: ObservableObject {
    @Published var notchSize = CGSize(width: 185, height: 32)
    @Published var hasNotch = true
    /// "Hide notch": nothing is drawn while it rests, only when it opens.
    @Published var hidden = false
    @Published var dragActive = false
    @Published var nearNotch = false
    @Published var dropHover = false
    /// Opened by the pointer: resting on the notch with "open on hover" on, or a swipe down on it.
    @Published var hoverOpen = false
    /// Set a moment into a transfer that is still running, so big files don't hold the notch open.
    @Published var transferCompact = false
    /// Pointer over the shrunk transfer: show the full details (and Cancel) until it moves away.
    @Published var transferPeek = false
    /// A recent or received item is being dragged out; the notch holds still until it's dropped.
    @Published var draggingOut = false
    /// Any recent items to show under the drop box.
    @Published var hasRecents = false
    /// The shelf shows when opened by hover or swipe, not while something is being dragged in.
    var showShelf: Bool { hasRecents && !dragActive }
    var onClickDropBox: (() -> Void)?
    var onAction: ((NotchAction) -> Void)?

    func phase(for activity: Activity) -> NotchPhase {
        if dragActive && nearNotch { return .open }
        if hoverOpen && activity == .idle { return .open }
        if transferCompact && activity.isTransferring && !transferPeek { return .mini }
        if activity != .idle { return .activity }
        if dragActive { return .peek }
        return .collapsed
    }
}

/// Owns the transparent panel that sits over the notch and grows into the drop zone.
@MainActor
final class NotchController {
    static let panelSize = CGSize(width: 640, height: 340)

    let ui = NotchUIModel()
    let app: AppModel
    private let prefs = Prefs.shared
    private var panel: NSPanel!
    private var zonePanel: NSPanel?
    private var pointerMonitors: [Any] = []
    /// Fingers' travel down (positive) or up over the notch in the current scroll gesture.
    private var swipe: CGFloat = 0
    /// After a swipe up closes it, hovering doesn't reopen it until the pointer has left the notch.
    private var hoverSuppressed = false
    private static let swipeThreshold: CGFloat = 25
    private var dropView: DropView!
    private let drag = DragMonitor()
    private var bag = Set<AnyCancellable>()
    private var compactTimer: DispatchWorkItem?
    /// Mouse-up came while a drop over the box was being accepted: stay open until AppKit delivers it.
    private var dropPending = false
    private var dropTimeout: DispatchWorkItem?
    /// Opened by the shortcut: stays open until the pointer has been in it and left, a click lands
    /// elsewhere, or the shortcut is pressed again.
    private var pinned = false
    private var pinnedEntered = false
    /// The display the panel is on, when it isn't the home one (the notched or main display).
    private var awayScreen: NSScreen?
    /// How long a transfer shows full details before it shrinks to the progress sliver.
    private static let fullDetailsDuration: TimeInterval = 2.5

    init(app: AppModel) {
        self.app = app
        buildPanel()
        layout()

        NotificationCenter.default.addObserver(forName: NSApplication.didChangeScreenParametersNotification,
                                               object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.layout() }
        }

        drag.onDragChanged = { [weak self] active in
            // Our own drags out of the notch aren't drops coming in.
            guard let self, self.prefs.notchEnabled, !self.ui.draggingOut else { return }
            if active {
                if self.prefs.followPointer { self.moveToPointerScreen() }
                self.dropTimeout?.cancel()
                self.dropPending = false
                self.ui.dragActive = true
                self.ui.hoverOpen = false
            } else if self.dropView.holdsDrop {
                // Released over the box. Collapsing now could pull the panel out from under the drop.
                self.dropPending = true
                let work = DispatchWorkItem { [weak self] in self?.endDrag() }
                self.dropTimeout = work
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.5, execute: work)
                return
            } else {
                self.endDrag()
                return
            }
            self.updateMouseHandling()
        }
        drag.onMove = { [weak self] p in
            self?.updateNear(p)
            self?.updateMouseHandling()
        }
        drag.start()

        app.$activity.sink { [weak self] a in
            guard let self else { return }
            self.updateCompact(transferring: a.isTransferring)
            DispatchQueue.main.async {
                self.updateMouseHandling()
                self.returnHomeWhenIdle()
            }
        }.store(in: &bag)

        prefs.$notchEnabled.sink { [weak self] on in
            guard let self else { return }
            if on { self.panel.orderFrontRegardless() } else { self.panel.orderOut(nil) }
            self.ui.dragActive = false
            self.ui.hoverOpen = false
        }.store(in: &bag)
        prefs.$hideNotch.sink { [weak self] hide in
            self?.ui.hidden = hide
            // Leaves the notch out of screenshots, screen recordings and screen sharing.
            self?.panel.sharingType = hide ? .none : .readOnly
        }.store(in: &bag)
        prefs.$openOnHover.sink { [weak self] _ in
            DispatchQueue.main.async { self?.updatePointer() }
        }.store(in: &bag)
        app.recents.$items.sink { [weak self] items in
            self?.ui.hasRecents = !items.isEmpty
        }.store(in: &bag)
        ui.$draggingOut.dropFirst().sink { [weak self] _ in
            DispatchQueue.main.async { self?.updatePointer() }
        }.store(in: &bag)
        startPointerMonitors()
        prefs.$previewZone.combineLatest(prefs.$zoneWidth, prefs.$zoneHeight).sink { [weak self] show, w, h in
            self?.showZone(show ? CGSize(width: w, height: h) : nil)
        }.store(in: &bag)

        // ND_DEMO=peek|open|hover forces a notch state, for screenshots while designing.
        if ProcessInfo.processInfo.environment["ND_DEMO"] == "transfer" {
            DispatchQueue.main.asyncAfter(deadline: .now() + 1) { app.demoTransfer() }
        } else if let demo = ProcessInfo.processInfo.environment["ND_DEMO"] {
            ui.dragActive = true
            ui.nearNotch = demo != "peek"
            ui.dropHover = demo == "hover"
        }
    }

    private func endDrag() {
        dropTimeout?.cancel()
        dropTimeout = nil
        dropPending = false
        ui.dragActive = false
        ui.nearNotch = false
        ui.dropHover = false
        updateMouseHandling()
        returnHomeWhenIdle()
    }

    // MARK: Other displays

    private var homeScreen: NSScreen? {
        NSScreen.screens.first(where: { $0.safeAreaInsets.top > 0 }) ?? NSScreen.main
    }

    private var pointerScreen: NSScreen? {
        let p = NSEvent.mouseLocation
        return NSScreen.screens.first { NSMouseInRect(p, $0.frame, false) }
    }

    /// The open-notch shortcut: opens on the display the pointer is on, or closes it if it's open.
    func toggleOnPointerScreen() {
        guard prefs.notchEnabled, !ui.dragActive, !ui.draggingOut else { return }
        if ui.hoverOpen {
            hoverSuppressed = openBox.contains(NSEvent.mouseLocation)
            setPointerOpen(false)
            return
        }
        guard app.activity == .idle else { return }
        moveToPointerScreen()
        pinned = true
        pinnedEntered = openBox.contains(NSEvent.mouseLocation)
        setPointerOpen(true)
    }

    private func moveToPointerScreen() {
        guard let screen = pointerScreen, screen != (awayScreen ?? homeScreen) else { return }
        awayScreen = screen == homeScreen ? nil : screen
        layout()
    }

    /// Once the notch has closed on another display, it goes back to the built-in one.
    private func returnHomeWhenIdle() {
        guard awayScreen != nil else { return }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) { [weak self] in
            guard let self, self.awayScreen != nil, !self.dropPending, !self.ui.draggingOut,
                  self.ui.phase(for: self.app.activity) == .collapsed else { return }
            self.awayScreen = nil
            self.layout()
        }
    }

    // MARK: Transfer sliver

    /// Shows a transfer in full first, then shrinks it if it is still running; completion expands it again.
    private func updateCompact(transferring: Bool) {
        if !transferring {
            ui.transferPeek = false
            compactTimer?.cancel()
            compactTimer = nil
            if ui.transferCompact { ui.transferCompact = false }
            return
        }
        guard compactTimer == nil, !ui.transferCompact else { return }
        let work = DispatchWorkItem { [weak self] in
            guard let self, self.app.activity.isTransferring else { return }
            self.ui.transferCompact = true
        }
        compactTimer = work
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.fullDetailsDuration, execute: work)
    }

    /// Hovering the sliver brings back the full view.
    private func updateTransferPeek() {
        guard ui.transferCompact, app.activity.isTransferring else { return }
        let p = NSEvent.mouseLocation
        let f = panel.frame
        let n = ui.notchSize
        // Opens over the sliver; closes once the pointer leaves the (larger) full view, so it doesn't flicker.
        let w = ui.transferPeek ? 400 + 32 : n.width + NotchRootView.miniEar * 2 + 16
        let h = ui.transferPeek ? n.height + 78 + 12 : n.height + 9 + 8
        let peek = NSRect(x: f.midX - w / 2, y: f.maxY - h, width: w, height: h).contains(p)
        if peek != ui.transferPeek {
            ui.transferPeek = peek
            updateMouseHandling()
        }
    }

    // MARK: Activation zone

    /// The area around the notch where a drag makes the drop box expand.
    private var zone: NSRect {
        let f = panel.frame
        let w = prefs.zoneWidth, h = prefs.zoneHeight
        return NSRect(x: f.midX - w / 2, y: f.maxY - h, width: w, height: h)
    }

    /// The black shape on screen in a given phase, flares included, from the same sizes `NotchRootView` draws.
    private func shapeRect(_ phase: NotchPhase) -> NSRect {
        let f = panel.frame
        let size = NotchRootView.bodySize(phase: phase, activity: app.activity, notch: ui.notchSize, showShelf: ui.showShelf)
        let w = size.width + NotchRootView.flare * 2
        return NSRect(x: f.midX - w / 2, y: f.maxY - size.height, width: w, height: size.height)
    }

    /// The expanded notch on screen.
    private var openBox: NSRect { shapeRect(.open) }

    /// The rounded drop target inside the open notch: the only place a drop is accepted.
    private var dropTarget: NSBezierPath {
        let f = panel.frame
        let i = NotchRootView.dropBoxInsets
        let w = NotchRootView.openSize.width - i.leading - i.trailing
        let h = NotchRootView.openSize.height - i.top - i.bottom
        let r = NSRect(x: f.midX - NotchRootView.openSize.width / 2 + i.leading,
                       y: f.maxY - ui.notchSize.height - i.top - h, width: w, height: h)
        return NSBezierPath(roundedRect: r, xRadius: NotchRootView.dropBoxRadius, yRadius: NotchRootView.dropBoxRadius)
    }

    private func inDropTarget(_ p: NSPoint) -> Bool {
        ui.phase(for: app.activity) == .open && dropTarget.contains(p)
    }

    /// Draws the activation zone over the screen while it's being adjusted in Settings.
    private func showZone(_ size: CGSize?) {
        guard let size else { zonePanel?.orderOut(nil); return }
        let f = panel.frame
        let maxW = Prefs.zoneWidthRange.upperBound, maxH = Prefs.zoneHeightRange.upperBound
        let frame = NSRect(x: f.midX - maxW / 2, y: f.maxY - maxH, width: maxW, height: maxH)
        if zonePanel == nil {
            let p = NSPanel(contentRect: frame, styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: false)
            p.isOpaque = false
            p.backgroundColor = .clear
            p.hasShadow = false
            p.level = .statusBar
            p.ignoresMouseEvents = true
            p.collectionBehavior = [.canJoinAllSpaces, .stationary, .fullScreenAuxiliary, .ignoresCycle]
            zonePanel = p
        }
        zonePanel?.setFrame(frame, display: false)
        zonePanel?.contentView = NSHostingView(rootView: ZoneOverlay(size: size, notch: ui.notchSize))
        zonePanel?.orderFrontRegardless()
    }

    // MARK: Open by hover or swipe

    /// The pointer and scroll gestures are watched all the time: they open the notch (hover or swipe down),
    /// peek at a shrunk transfer, and hold a finished transfer on screen while the pointer is over it.
    private func startPointerMonitors() {
        let moved: NSEvent.EventTypeMask = [.mouseMoved, .leftMouseDragged]
        if let m = NSEvent.addGlobalMonitorForEvents(matching: moved, handler: { [weak self] _ in
            MainActor.assumeIsolated { self?.updatePointer() }
        }) { pointerMonitors.append(m) }
        if let m = NSEvent.addLocalMonitorForEvents(matching: moved, handler: { [weak self] e in
            MainActor.assumeIsolated { self?.updatePointer() }
            return e
        }) { pointerMonitors.append(m) }
        // A click anywhere else closes a notch the shortcut opened.
        if let m = NSEvent.addGlobalMonitorForEvents(matching: [.leftMouseDown, .rightMouseDown], handler: { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self, self.pinned, self.ui.hoverOpen, !self.openBox.contains(NSEvent.mouseLocation) else { return }
                self.setPointerOpen(false)
            }
        }) { pointerMonitors.append(m) }
        if let m = NSEvent.addGlobalMonitorForEvents(matching: .scrollWheel, handler: { [weak self] e in
            MainActor.assumeIsolated { self?.handleScroll(e) }
        }) { pointerMonitors.append(m) }
        if let m = NSEvent.addLocalMonitorForEvents(matching: .scrollWheel, handler: { [weak self] e in
            MainActor.assumeIsolated { self?.handleScroll(e) }
            return e
        }) { pointerMonitors.append(m) }
    }

    private var hotspot: NSRect {
        let f = panel.frame
        let n = ui.notchSize
        return NSRect(x: f.midX - n.width / 2, y: f.maxY - n.height, width: n.width, height: n.height)
    }

    /// The finished-transfer box on screen.
    private var activityBox: NSRect { shapeRect(.activity).insetBy(dx: -8, dy: -8) }

    private func updatePointer() {
        defer { updateMouseHandling() }
        updateTransferPeek()
        let p = NSEvent.mouseLocation
        // A finished transfer stays up while the pointer is on it (or one of its files is being dragged out).
        let finished = app.activity != .idle && !app.activity.isTransferring
        app.holdActivity = ui.draggingOut || (finished && activityBox.contains(p))

        guard prefs.notchEnabled, !ui.dragActive, !ui.draggingOut else { return }
        if hoverSuppressed, !openBox.contains(p) { hoverSuppressed = false }
        let inBox = openBox.insetBy(dx: -8, dy: -8).contains(p)
        if pinned && ui.hoverOpen {
            // Opened by the shortcut away from the notch: wait for the pointer to come in before leaving closes it.
            if inBox { pinnedEntered = true }
            if !pinnedEntered { return }
        }
        // Opens on the notch itself; stays open while the pointer is over the expanded box.
        let open = ui.hoverOpen
            ? inBox
            : prefs.openOnHover && !hoverSuppressed && app.activity == .idle && hotspot.contains(p)
        setPointerOpen(open)
    }

    private func setPointerOpen(_ open: Bool) {
        guard open != ui.hoverOpen else { return }
        ui.hoverOpen = open
        if open {
            prefs.haptic()
            app.recents.prune()
        } else {
            pinned = false
            returnHomeWhenIdle()
        }
        updateMouseHandling()
    }

    /// A two-finger swipe down over the notch opens it; a swipe up closes it.
    private func handleScroll(_ e: NSEvent) {
        guard prefs.notchEnabled, !ui.dragActive, !ui.draggingOut, e.momentumPhase.isEmpty else { return }
        if e.phase == .began { swipe = 0 }
        let p = NSEvent.mouseLocation
        let area = hotspot.insetBy(dx: -80, dy: 0)
        guard ui.hoverOpen || prefs.swipeToOpen else { return }
        guard ui.hoverOpen ? openBox.contains(p) : area.contains(p) else { swipe = 0; return }
        // Content follows the fingers with natural scrolling, so undo that to get the fingers' direction.
        let raw = e.hasPreciseScrollingDeltas ? e.scrollingDeltaY : e.scrollingDeltaY * 10
        swipe += e.isDirectionInvertedFromDevice ? raw : -raw
        if !ui.hoverOpen, swipe > Self.swipeThreshold, app.activity == .idle {
            swipe = 0
            setPointerOpen(true)
        } else if ui.hoverOpen, swipe < -Self.swipeThreshold {
            swipe = 0
            hoverSuppressed = true
            setPointerOpen(false)
        }
    }

    private func buildPanel() {
        let p = NSPanel(contentRect: NSRect(origin: .zero, size: Self.panelSize),
                        styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: false)
        p.isOpaque = false
        p.backgroundColor = .clear
        p.hasShadow = false
        p.level = .statusBar
        p.collectionBehavior = [.canJoinAllSpaces, .stationary, .fullScreenAuxiliary, .ignoresCycle]
        p.isMovable = false
        p.acceptsMouseMovedEvents = true
        p.ignoresMouseEvents = true
        p.hidesOnDeactivate = false

        let root = NotchRootView(ui: ui, app: app)
        let host = NSHostingView(rootView: root)
        host.frame = NSRect(origin: .zero, size: Self.panelSize)
        host.autoresizingMask = [.width, .height]

        let dv = DropView(frame: NSRect(origin: .zero, size: Self.panelSize))
        dv.autoresizingMask = [.width, .height]
        dv.addSubview(host)
        dv.onHover = { [weak self] hovering in
            if !hovering { self?.ui.dropHover = false }
        }
        dv.onMove = { [weak self] p in self?.updateNear(p) }
        dv.canAccept = { [weak self] p in
            guard let self else { return false }
            // While busy the drop is queued, so it's still taken.
            return self.inDropTarget(p) && self.app.peer != nil
        }
        dv.onDrop = { [weak self] urls, texts in
            guard let self else { return }
            self.prefs.haptic(.levelChange)
            self.endDrag()
            self.app.send(urls: urls, texts: texts)
        }
        dv.onSessionEnd = { [weak self] in
            guard let self, self.dropPending else { return }
            self.endDrag()
        }
        ui.onClickDropBox = { [weak self] in
            self?.ui.hoverOpen = false
            AppDelegate.shared?.sendFiles()
        }
        ui.onAction = { [weak self] action in
            guard let self else { return }
            self.ui.hoverOpen = false
            self.hoverSuppressed = true
            self.updateMouseHandling()
            switch action {
            case .sendClipboard: self.app.sendClipboard()
            case .openFolder: AppDelegate.shared?.openReceivedFolder()
            case .settings: AppDelegate.shared?.showSettings()
            }
        }
        dropView = dv
        p.contentView = dv
        p.orderFrontRegardless()
        panel = p
    }

    /// Positions the panel at the top-center of the notched (built-in) screen, or the main screen,
    /// or of another display while it's open there.
    func layout() {
        if let away = awayScreen, !NSScreen.screens.contains(away) { awayScreen = nil }
        guard let screen = awayScreen ?? homeScreen else { return }
        let f = screen.frame
        if screen.safeAreaInsets.top > 0,
           let left = screen.auxiliaryTopLeftArea, let right = screen.auxiliaryTopRightArea
        {
            ui.hasNotch = true
            ui.notchSize = CGSize(width: f.width - left.width - right.width, height: screen.safeAreaInsets.top)
        } else {
            ui.hasNotch = false
            ui.notchSize = CGSize(width: 200, height: NSStatusBar.system.thickness)
        }
        let size = Self.panelSize
        panel.setFrame(NSRect(x: f.midX - size.width / 2, y: f.maxY - size.height, width: size.width, height: size.height),
                       display: true)
    }

    /// The activation zone only decides when the notch expands; it stays open while the pointer is
    /// over the expanded notch. Dropping is a separate question: only the rounded drop box takes drops.
    private func updateNear(_ p: NSPoint) {
        let near = zone.contains(p) || (ui.nearNotch && openBox.contains(p))
        if near != ui.nearNotch {
            ui.nearNotch = near
            if near { prefs.haptic() }
        }
        let over = near && dropTarget.contains(p)
        if over != ui.dropHover { ui.dropHover = over }
    }

    /// The panel is much bigger than what's drawn in it, so it only takes the mouse while the pointer is
    /// over the visible notch. Otherwise its transparent area would swallow clicks, and drags meant for
    /// windows underneath (Finder to Finder, say).
    private func updateMouseHandling() {
        let p = NSEvent.mouseLocation
        let phase = ui.phase(for: app.activity)
        let interactive: Bool
        if dropPending {
            interactive = true
        } else if ui.dragActive {
            // Only the open drop box takes part in a drag; the zone itself is tracked from the pointer.
            interactive = phase == .open && openBox.contains(p)
        } else {
            switch phase {
            case .open, .activity: interactive = shapeRect(phase).insetBy(dx: -4, dy: -4).contains(p)
            case .peek, .collapsed, .mini: interactive = false
            }
        }
        if panel.ignoresMouseEvents == interactive { panel.ignoresMouseEvents = !interactive }
    }
}

/// Dashed outline of the drag activation zone, shown while adjusting it in Settings.
struct ZoneOverlay: View {
    var size: CGSize
    var notch: CGSize

    var body: some View {
        ZStack(alignment: .top) {
            Rectangle()
                .fill(Palette.accent.opacity(0.12))
                .overlay(Rectangle().strokeBorder(Palette.accent, style: StrokeStyle(lineWidth: 2, dash: [8, 6])))
                .frame(width: size.width, height: size.height)
            Text("Drag activation zone · \(Int(size.width)) × \(Int(size.height))")
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(.white)
                .padding(.horizontal, 10).padding(.vertical, 4)
                .background(Capsule().fill(Palette.accent))
                .padding(.top, min(size.height - 26, notch.height + 8))
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .animation(.easeOut(duration: 0.1), value: size)
    }
}

// MARK: - Drag detection

/// Detects system-wide drags by watching the drag pasteboard while the left button is held.
///
/// It polls rather than relying on mouse-dragged events alone: once another app's drag session starts,
/// those events don't always reach a global monitor, which used to leave a drag unnoticed.
final class DragMonitor {
    var onDragChanged: ((Bool) -> Void)?
    var onMove: ((NSPoint) -> Void)?

    private var monitors: [Any] = []
    private var baseline = NSPasteboard(name: .drag).changeCount
    private var dragging = false
    private var poll: Timer?
    /// Polls in a row that saw the button up. One isn't enough: a trackpad's three-finger drag can blip.
    private var upTicks = 0

    func start() {
        let m = NSEvent.addGlobalMonitorForEvents(matching: [.leftMouseDown, .leftMouseDragged, .leftMouseUp]) { [weak self] e in
            self?.handle(e)
        }
        if let m { monitors.append(m) }
    }

    private func handle(_ e: NSEvent) {
        switch e.type {
        case .leftMouseDown:
            baseline = NSPasteboard(name: .drag).changeCount
            startPolling()
        case .leftMouseDragged:
            if poll == nil { startPolling() }
            check()
        case .leftMouseUp:
            finish()
        default:
            break
        }
    }

    private static var buttonDown: Bool {
        NSEvent.pressedMouseButtons & 1 != 0 || CGEventSource.buttonState(.combinedSessionState, button: .left)
    }

    private func startPolling() {
        poll?.invalidate()
        upTicks = 0
        let t = Timer(timeInterval: 1.0 / 60, repeats: true) { [weak self] _ in self?.check() }
        // .common, so it keeps firing while menus or our own windows track the mouse.
        RunLoop.main.add(t, forMode: .common)
        poll = t
    }

    private func check() {
        guard Self.buttonDown else {
            upTicks += 1
            if upTicks >= 2 { finish() }
            return
        }
        upTicks = 0
        if !dragging, NSPasteboard(name: .drag).changeCount != baseline { setDragging(true) }
        if dragging { onMove?(NSEvent.mouseLocation) }
    }

    private func finish() {
        poll?.invalidate()
        poll = nil
        upTicks = 0
        baseline = NSPasteboard(name: .drag).changeCount
        if dragging { setDragging(false) }
    }

    private func setDragging(_ d: Bool) {
        dragging = d
        onDragChanged?(d)
    }
}

// MARK: - Drop destination

final class DropView: NSView {
    var onHover: ((Bool) -> Void)?
    var onDrop: (([URL], [String]) -> Void)?
    /// Whether a drop at this screen point would be taken.
    var canAccept: ((NSPoint) -> Bool)?
    /// Pointer position in screen coordinates while a drag is over the panel.
    var onMove: ((NSPoint) -> Void)?
    /// The drag that was over the view has gone: dropped, cancelled or moved away.
    var onSessionEnd: (() -> Void)?
    /// A drag is over the view and would be taken if released now.
    var holdsDrop: Bool { inside && lastAccepted }
    private var inside = false

    private let promiseQueue: OperationQueue = {
        let q = OperationQueue()
        q.qualityOfService = .userInitiated
        return q
    }()

    override init(frame: NSRect) {
        super.init(frame: frame)
        registerForDraggedTypes([.fileURL, .URL, .string] + NSFilePromiseReceiver.readableDraggedTypes.map { NSPasteboard.PasteboardType($0) })
    }

    required init?(coder: NSCoder) { fatalError() }

    override func draggingEntered(_ sender: NSDraggingInfo) -> NSDragOperation {
        inside = true
        onHover?(true)
        report(sender)
        return accepts(sender) ? .copy : []
    }

    override func draggingUpdated(_ sender: NSDraggingInfo) -> NSDragOperation {
        report(sender)
        return accepts(sender) ? .copy : []
    }

    /// The answer given while hovering. The drop itself relies on it: by the time `performDragOperation`
    /// runs, mouse-up has already ended the drag and collapsed the notch, so a fresh check would refuse.
    private var lastAccepted = false

    private func accepts(_ sender: NSDraggingInfo) -> Bool {
        // A source in this app means one of our own tiles, which would only send the file straight back.
        guard let window, sender.draggingSource == nil else { return false }
        lastAccepted = canAccept?(window.convertPoint(toScreen: sender.draggingLocation)) == true
        return lastAccepted
    }

    private func report(_ sender: NSDraggingInfo) {
        guard let window else { return }
        onMove?(window.convertPoint(toScreen: sender.draggingLocation))
    }

    override func draggingExited(_ sender: NSDraggingInfo?) {
        onHover?(false)
        inside = false
        lastAccepted = false
        onSessionEnd?()
    }

    override func draggingEnded(_ sender: NSDraggingInfo) {
        inside = false
        lastAccepted = false
        onSessionEnd?()
    }

    override func performDragOperation(_ sender: NSDraggingInfo) -> Bool {
        guard lastAccepted else { return false }
        lastAccepted = false
        let pb = sender.draggingPasteboard
        if let urls = pb.readObjects(forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true]) as? [URL], !urls.isEmpty {
            onDrop?(urls, [])
            return true
        }
        if let promises = pb.readObjects(forClasses: [NSFilePromiseReceiver.self]) as? [NSFilePromiseReceiver], !promises.isEmpty {
            let dest = FileManager.default.temporaryDirectory.appendingPathComponent("NotchDrop/promised-\(UUID().uuidString)")
            try? FileManager.default.createDirectory(at: dest, withIntermediateDirectories: true)
            let group = DispatchGroup()
            var urls: [URL] = []
            let lock = NSLock()
            for p in promises {
                group.enter()
                p.receivePromisedFiles(atDestination: dest, options: [:], operationQueue: promiseQueue) { url, err in
                    if err == nil { lock.lock(); urls.append(url); lock.unlock() }
                    group.leave()
                }
            }
            group.notify(queue: .main) { [weak self] in self?.onDrop?(urls, []) }
            return true
        }
        if let urls = pb.readObjects(forClasses: [NSURL.self]) as? [URL], !urls.isEmpty {
            onDrop?([], urls.map(\.absoluteString))
            return true
        }
        if let s = pb.string(forType: .string), !s.isEmpty {
            onDrop?([], [s])
            return true
        }
        return false
    }

    override func concludeDragOperation(_ sender: NSDraggingInfo?) {
        onHover?(false)
        inside = false
        onSessionEnd?()
    }
}
