import AppKit
import SwiftUI

/// What a tile hands over when dragged out of the notch.
enum DragPayload: Equatable {
    case file(URL)
    case text(String)
}

/// Makes the view under it draggable into Finder or any app (a copy; the original stays put), and clickable.
/// AppKit rather than SwiftUI's `onDrag`, which can't start a drag from a panel that never becomes key.
struct DragOut: NSViewRepresentable {
    var payload: DragPayload
    var image: NSImage?
    var onClick: () -> Void
    /// When set, a single click waits out the double-click interval so a double-click doesn't also run [onClick].
    var onDoubleClick: (() -> Void)? = nil
    var onHover: (Bool) -> Void = { _ in }
    /// True while the drag is under way, so the notch stays put until it ends.
    var onDragging: (Bool) -> Void = { _ in }

    func makeNSView(context: Context) -> DragOutView { DragOutView() }

    func updateNSView(_ v: DragOutView, context: Context) {
        v.payload = payload
        v.image = image
        v.onClick = onClick
        v.onDoubleClick = onDoubleClick
        v.onHover = onHover
        v.onDragging = onDragging
    }
}

final class DragOutView: NSView, NSDraggingSource {
    var payload: DragPayload = .text("")
    var image: NSImage?
    var onClick: () -> Void = {}
    var onDoubleClick: (() -> Void)?
    var onHover: (Bool) -> Void = { _ in }
    var onDragging: (Bool) -> Void = { _ in }
    private var mouseDown: NSEvent?
    private var pendingClick: DispatchWorkItem?

    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }

    override func updateTrackingAreas() {
        super.updateTrackingAreas()
        trackingAreas.forEach(removeTrackingArea)
        addTrackingArea(NSTrackingArea(rect: .zero, options: [.mouseEnteredAndExited, .activeAlways, .inVisibleRect], owner: self))
    }

    override func mouseEntered(with event: NSEvent) { onHover(true) }
    override func mouseExited(with event: NSEvent) { onHover(false) }

    override func mouseDown(with event: NSEvent) { mouseDown = event }

    override func mouseDragged(with event: NSEvent) {
        guard let down = mouseDown else { return }
        let a = down.locationInWindow, b = event.locationInWindow
        guard hypot(b.x - a.x, b.y - a.y) > 4 else { return }
        mouseDown = nil
        let writer: NSPasteboardWriting
        let icon: NSImage
        switch payload {
        case .file(let url):
            writer = url as NSURL
            icon = image ?? NSWorkspace.shared.icon(forFile: url.path)
        case .text(let s):
            writer = s as NSString
            icon = image ?? NSImage(systemSymbolName: "text.alignleft", accessibilityDescription: nil) ?? NSImage()
        }
        let item = NSDraggingItem(pasteboardWriter: writer)
        item.setDraggingFrame(bounds, contents: icon)
        onDragging(true)
        beginDraggingSession(with: [item], event: down, source: self)
    }

    override func mouseUp(with event: NSEvent) {
        defer { mouseDown = nil }
        guard mouseDown != nil else { return }
        guard let onDoubleClick else { onClick(); return }
        pendingClick?.cancel()
        if event.clickCount >= 2 {
            pendingClick = nil
            onDoubleClick()
        } else {
            let click = DispatchWorkItem { [weak self] in self?.onClick() }
            pendingClick = click
            DispatchQueue.main.asyncAfter(deadline: .now() + NSEvent.doubleClickInterval, execute: click)
        }
    }

    func draggingSession(_ session: NSDraggingSession, sourceOperationMaskFor context: NSDraggingContext) -> NSDragOperation {
        // Never back into the notch, which would send it straight back to the phone.
        context == .outsideApplication ? .copy : []
    }

    func draggingSession(_ session: NSDraggingSession, endedAt screenPoint: NSPoint, operation: NSDragOperation) {
        onDragging(false)
    }
}
