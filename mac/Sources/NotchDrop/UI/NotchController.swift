import AppKit
import Combine
import SwiftUI

enum NotchPhase: Equatable {
    case collapsed, peek, open, activity
}

@MainActor
final class NotchUIModel: ObservableObject {
    @Published var notchSize = CGSize(width: 185, height: 32)
    @Published var hasNotch = true
    @Published var dragActive = false
    @Published var nearNotch = false
    @Published var dropHover = false

    func phase(for activity: Activity) -> NotchPhase {
        if dragActive && (nearNotch || dropHover) { return .open }
        if activity != .idle { return .activity }
        if dragActive { return .peek }
        return .collapsed
    }
}

/// Owns the transparent panel that sits over the notch and grows into the drop zone.
@MainActor
final class NotchController {
    static let panelSize = CGSize(width: 640, height: 280)

    let ui = NotchUIModel()
    let app: AppModel
    private var panel: NSPanel!
    private var dropView: DropView!
    private let drag = DragMonitor()
    private var bag = Set<AnyCancellable>()

    init(app: AppModel) {
        self.app = app
        buildPanel()
        layout()

        NotificationCenter.default.addObserver(forName: NSApplication.didChangeScreenParametersNotification,
                                               object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.layout() }
        }

        drag.onDragChanged = { [weak self] active in
            guard let self else { return }
            self.ui.dragActive = active
            if !active { self.ui.nearNotch = false; self.ui.dropHover = false }
            self.updateMouseHandling()
        }
        drag.onMove = { [weak self] p in self?.updateNear(p) }
        drag.start()

        // ND_DEMO=peek|open|hover forces a notch state, for screenshots while designing.
        if let demo = ProcessInfo.processInfo.environment["ND_DEMO"] {
            ui.dragActive = true
            ui.nearNotch = demo != "peek"
            ui.dropHover = demo == "hover"
        }

        app.$activity.sink { [weak self] _ in
            DispatchQueue.main.async { self?.updateMouseHandling() }
        }.store(in: &bag)
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
            self?.ui.dropHover = hovering
        }
        dv.canAccept = { [weak self] in
            guard let self else { return false }
            return self.app.peer != nil && !self.app.isBusy
        }
        dv.onDrop = { [weak self] urls, texts in
            guard let self else { return }
            self.ui.dropHover = false
            self.ui.dragActive = false
            self.app.send(urls: urls, texts: texts)
        }
        dropView = dv
        p.contentView = dv
        p.orderFrontRegardless()
        panel = p
    }

    /// Positions the panel at the top-center of the notched (built-in) screen, or the main screen.
    func layout() {
        guard let screen = NSScreen.screens.first(where: { $0.safeAreaInsets.top > 0 }) ?? NSScreen.main else { return }
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

    private func updateNear(_ p: NSPoint) {
        let f = panel.frame
        let zone = NSRect(x: f.midX - 280, y: f.maxY - 210, width: 560, height: 210)
        let near = zone.contains(p)
        if near != ui.nearNotch { ui.nearNotch = near }
    }

    private func updateMouseHandling() {
        let interactive: Bool
        switch ui.phase(for: app.activity) {
        case .open, .peek: interactive = true
        case .activity:
            if case .received = app.activity { interactive = true } else if case .failed = app.activity { interactive = true } else { interactive = false }
        case .collapsed: interactive = false
        }
        panel.ignoresMouseEvents = !(interactive || ui.dragActive)
    }
}

// MARK: - Drag detection

/// Detects system-wide drags by watching the drag pasteboard while the mouse button is held.
final class DragMonitor {
    var onDragChanged: ((Bool) -> Void)?
    var onMove: ((NSPoint) -> Void)?

    private var monitors: [Any] = []
    private var baseline = NSPasteboard(name: .drag).changeCount
    private var dragging = false
    private var poll: Timer?

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
        case .leftMouseDragged:
            if !dragging && NSPasteboard(name: .drag).changeCount != baseline {
                setDragging(true)
            }
            if dragging { onMove?(NSEvent.mouseLocation) }
        case .leftMouseUp:
            if dragging { setDragging(false) }
        default:
            break
        }
    }

    private func setDragging(_ d: Bool) {
        dragging = d
        onDragChanged?(d)
        poll?.invalidate()
        guard d else { return }
        // Mouse-up can be swallowed when the drop lands on our own panel; poll the button state as a fallback.
        poll = Timer.scheduledTimer(withTimeInterval: 0.08, repeats: true) { [weak self] _ in
            guard let self else { return }
            if NSEvent.pressedMouseButtons & 1 == 0 {
                self.baseline = NSPasteboard(name: .drag).changeCount
                self.setDragging(false)
            } else {
                self.onMove?(NSEvent.mouseLocation)
            }
        }
    }
}

// MARK: - Drop destination

final class DropView: NSView {
    var onHover: ((Bool) -> Void)?
    var onDrop: (([URL], [String]) -> Void)?
    var canAccept: (() -> Bool)?

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
        onHover?(true)
        return canAccept?() == true ? .copy : []
    }

    override func draggingUpdated(_ sender: NSDraggingInfo) -> NSDragOperation {
        canAccept?() == true ? .copy : []
    }

    override func draggingExited(_ sender: NSDraggingInfo?) {
        onHover?(false)
    }

    override func performDragOperation(_ sender: NSDraggingInfo) -> Bool {
        guard canAccept?() == true else { return false }
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
    }
}
