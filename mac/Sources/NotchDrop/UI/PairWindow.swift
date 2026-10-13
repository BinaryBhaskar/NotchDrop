import AppKit
import CoreImage.CIFilterBuiltins
import SwiftUI

@MainActor
final class PairWindowController: NSObject, NSWindowDelegate {
    private var window: NSWindow?
    private let app: AppModel

    init(app: AppModel) { self.app = app }

    func show() {
        app.beginPairing()
        if window == nil {
            let w = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 380, height: 520),
                             styleMask: [.titled, .closable, .fullSizeContentView], backing: .buffered, defer: false)
            w.titlebarAppearsTransparent = true
            w.titleVisibility = .hidden
            w.isMovableByWindowBackground = true
            w.isReleasedWhenClosed = false
            w.delegate = self
            w.contentView = NSHostingView(rootView: PairView(app: app) { [weak self] in self?.window?.close() })
            w.center()
            window = w
        }
        NSApp.activate(ignoringOtherApps: true)
        window?.makeKeyAndOrderFront(nil)
    }

    func windowWillClose(_ notification: Notification) {
        app.endPairing()
    }
}

struct PairView: View {
    @ObservedObject var app: AppModel
    var close: () -> Void

    var body: some View {
        VStack(spacing: 18) {
            HStack(spacing: 10) {
                Image(systemName: "laptopcomputer").font(.system(size: 22, weight: .medium))
                Image(systemName: "arrow.left.arrow.right").font(.system(size: 13, weight: .bold)).foregroundStyle(Palette.accent)
                PhoneGlyph(color: .primary, size: 26)
            }
            .padding(.top, 34)

            if app.justPaired, let peer = app.peer {
                Spacer()
                Image(systemName: "checkmark.circle.fill")
                    .font(.system(size: 64))
                    .foregroundStyle(Palette.ok)
                    .symbolEffect(.bounce, value: app.justPaired)
                Text("Paired with \(peer.displayName)").font(.title3.weight(.semibold))
                Text("Drag anything to the notch to send it.\nShare to “NotchDrop” on your phone to send here.")
                    .multilineTextAlignment(.center)
                    .foregroundStyle(.secondary)
                Spacer()
                Button("Done", action: close).keyboardShortcut(.defaultAction).controlSize(.large)
                    .padding(.bottom, 26)
            } else {
                Text("Pair your phone").font(.title2.weight(.bold))
                Text("Open NotchDrop on your phone and tap **Scan to pair**.")
                    .multilineTextAlignment(.center)
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 30)

                if let uri = app.pairing?.uri, let img = QR.image(uri) {
                    Image(nsImage: img)
                        .interpolation(.none)
                        .resizable()
                        .frame(width: 230, height: 230)
                        .padding(14)
                        .background(RoundedRectangle(cornerRadius: 22, style: .continuous).fill(.white))
                        .shadow(color: .black.opacity(0.15), radius: 12, y: 4)
                }

                HStack(spacing: 8) {
                    ProgressView().controlSize(.small)
                    Text("Waiting for phone…").foregroundStyle(.secondary)
                }
                Text("Both devices must be on the same Wi-Fi, or the Mac on the phone's hotspot. The code is single-use; keep it private.")
                    .font(.caption)
                    .multilineTextAlignment(.center)
                    .foregroundStyle(.tertiary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal, 30)
                    .padding(.bottom, 22)
            }
        }
        .frame(width: 380, height: 520)
    }
}

enum QR {
    static func image(_ text: String) -> NSImage? {
        let f = CIFilter.qrCodeGenerator()
        f.message = Data(text.utf8)
        f.correctionLevel = "M"
        guard let out = f.outputImage else { return nil }
        let scaled = out.transformed(by: CGAffineTransform(scaleX: 10, y: 10))
        let rep = NSCIImageRep(ciImage: scaled)
        let img = NSImage(size: rep.size)
        img.addRepresentation(rep)
        return img
    }
}
