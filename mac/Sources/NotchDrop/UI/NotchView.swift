import AppKit
import QuickLookThumbnailing
import SwiftUI

enum Palette {
    static let accent = Color(red: 0.36, green: 0.58, blue: 1.0)
    static let accent2 = Color(red: 0.62, green: 0.45, blue: 1.0)
    static let ok = Color(red: 0.30, green: 0.85, blue: 0.48)
    static let warn = Color(red: 1.0, green: 0.62, blue: 0.25)
    static let bad = Color(red: 1.0, green: 0.38, blue: 0.38)
    static let dim = Color.white.opacity(0.55)
}

/// Notch silhouette: flat top that flares into the menu bar, rounded bottom corners.
struct NotchShape: Shape {
    var bottomRadius: CGFloat
    var flare: CGFloat

    var animatableData: AnimatablePair<CGFloat, CGFloat> {
        get { AnimatablePair(bottomRadius, flare) }
        set { bottomRadius = newValue.first; flare = newValue.second }
    }

    func path(in r: CGRect) -> Path {
        let f = flare
        let br = min(bottomRadius, r.height / 2, (r.width - 2 * f) / 2)
        var p = Path()
        p.move(to: CGPoint(x: r.minX, y: r.minY))
        p.addQuadCurve(to: CGPoint(x: r.minX + f, y: r.minY + f), control: CGPoint(x: r.minX + f, y: r.minY))
        p.addLine(to: CGPoint(x: r.minX + f, y: r.maxY - br))
        p.addQuadCurve(to: CGPoint(x: r.minX + f + br, y: r.maxY), control: CGPoint(x: r.minX + f, y: r.maxY))
        p.addLine(to: CGPoint(x: r.maxX - f - br, y: r.maxY))
        p.addQuadCurve(to: CGPoint(x: r.maxX - f, y: r.maxY - br), control: CGPoint(x: r.maxX - f, y: r.maxY))
        p.addLine(to: CGPoint(x: r.maxX - f, y: r.minY + f))
        p.addQuadCurve(to: CGPoint(x: r.maxX, y: r.minY), control: CGPoint(x: r.maxX - f, y: r.minY))
        p.closeSubpath()
        return p
    }
}

struct NotchRootView: View {
    @ObservedObject var ui: NotchUIModel
    @ObservedObject var app: AppModel

    private var phase: NotchPhase { ui.phase(for: app.activity) }
    private let flare: CGFloat = 8
    /// Expanded drop box size below the notch; `NotchController` uses it for hit testing.
    static let openSize = CGSize(width: 460, height: 196)
    /// The rounded drop target's inset within the open area below the notch, and its corner radius.
    /// `NotchController` uses both so the drop box is exactly the area that takes drops.
    static let dropBoxInsets = EdgeInsets(top: 56, leading: 18, bottom: 18, trailing: 18)
    static let dropBoxRadius: CGFloat = 20
    /// Extra width on each side of the notch for the transfer sliver's icon and percentage.
    static let miniEar: CGFloat = 46

    private var bodySize: CGSize {
        let n = ui.notchSize
        switch phase {
        case .collapsed: return n
        case .peek: return CGSize(width: max(n.width + 90, 250), height: n.height + 30)
        case .open: return CGSize(width: Self.openSize.width, height: n.height + Self.openSize.height)
        case .mini: return CGSize(width: n.width + Self.miniEar * 2, height: n.height + 9)
        case .activity:
            switch app.activity {
            case .received(let files, _) where !files.isEmpty: return CGSize(width: 420, height: n.height + 150)
            case .incoming: return CGSize(width: 420, height: n.height + 84)
            default: return CGSize(width: 400, height: n.height + 78)
            }
        }
    }

    private var radius: CGFloat {
        switch phase {
        case .collapsed: return 10
        case .peek: return 16
        case .open: return 30
        case .activity: return 24
        case .mini: return 12
        }
    }

    var body: some View {
        let size = bodySize
        ZStack(alignment: .top) {
            NotchShape(bottomRadius: radius, flare: flare)
                .fill(Color.black)
                .shadow(color: .black.opacity(phase == .collapsed ? 0 : 0.45), radius: 18, y: 8)

            content
                .padding(.top, phase == .mini ? 0 : ui.notchSize.height)
                .padding(.horizontal, flare)
                .frame(width: size.width + flare * 2, height: size.height, alignment: .top)
                .clipped()
        }
        .frame(width: size.width + flare * 2, height: size.height)
        .opacity(phase == .collapsed && !ui.hasNotch ? 0 : 1)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .animation(.spring(response: 0.42, dampingFraction: 0.78), value: phase)
        .animation(.spring(response: 0.42, dampingFraction: 0.78), value: app.activity)
        .preferredColorScheme(.dark)
    }

    @ViewBuilder private var content: some View {
        switch phase {
        case .collapsed:
            EmptyView()
        case .peek:
            PeekView(name: app.peerName, paired: app.peer != nil)
                .transition(.opacity.combined(with: .scale(scale: 0.9, anchor: .top)))
        case .open:
            OpenView(app: app, targeted: ui.dropHover, onClick: ui.onClickDropBox)
                .transition(.asymmetric(insertion: .opacity.animation(.easeOut(duration: 0.2).delay(0.08)),
                                        removal: .opacity.animation(.easeOut(duration: 0.12))))
        case .activity:
            ActivityView(app: app)
                .transition(.opacity.combined(with: .scale(scale: 0.94, anchor: .top)))
        case .mini:
            MiniTransferView(activity: app.activity, notch: ui.notchSize, ear: Self.miniEar)
                .transition(.opacity.animation(.easeOut(duration: 0.2).delay(0.12)))
        }
    }
}

// MARK: - Pieces

/// Generic phone: slim rounded slab with a centered punch-hole camera.
struct PhoneGlyph: View {
    var color: Color = .white
    var size: CGFloat = 26

    var body: some View {
        ZStack(alignment: .top) {
            RoundedRectangle(cornerRadius: size * 0.2, style: .continuous)
                .strokeBorder(color, lineWidth: max(1.4, size * 0.065))
            Circle()
                .fill(color)
                .frame(width: size * 0.13, height: size * 0.13)
                .padding(.top, size * 0.1)
        }
        .frame(width: size * 0.5, height: size)
    }
}

struct StatusLine: View {
    @ObservedObject var app: AppModel

    var body: some View {
        HStack(spacing: 5) {
            Circle().fill(color).frame(width: 6, height: 6)
                .shadow(color: color.opacity(0.8), radius: 3)
            Text(text).font(.system(size: 11, weight: .medium)).foregroundStyle(Palette.dim)
        }
    }

    private var color: Color {
        if app.peer == nil { return .gray }
        return app.reachable ? Palette.ok : Palette.warn
    }

    private var text: String {
        if app.peer == nil { return "Not paired" }
        if app.reachable { return "Connected · \(app.route?.rawValue ?? "Wi-Fi")" }
        return "Looking for phone…"
    }
}

struct PeekView: View {
    var name: String
    var paired: Bool

    var body: some View {
        HStack(spacing: 8) {
            TimelineView(.animation(minimumInterval: 1 / 30)) { tl in
                Image(systemName: "chevron.compact.up")
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(Palette.accent)
                    .offset(y: CGFloat(sin(tl.date.timeIntervalSinceReferenceDate * 5)) * 2)
            }
            Text(paired ? "Drop here for \(name)" : "Pair a phone to share")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(.white.opacity(0.9))
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 6)
    }
}

struct OpenView: View {
    @ObservedObject var app: AppModel
    var targeted: Bool
    var onClick: (() -> Void)?

    var body: some View {
        ZStack(alignment: .top) {
            HStack(spacing: 10) {
                PhoneGlyph(color: .white.opacity(0.9), size: 24)
                VStack(alignment: .leading, spacing: 2) {
                    Text(app.peer?.displayName ?? "No phone paired")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(.white)
                    StatusLine(app: app)
                }
                Spacer()
            }
            .frame(height: 34)
            .padding(.horizontal, 22)
            .padding(.top, 10)

            DropBox(title: app.peer == nil ? "Pair a phone from the menu bar first" : "Drop to share to \(app.peerName)",
                    targeted: targeted && app.peer != nil)
                .contentShape(RoundedRectangle(cornerRadius: NotchRootView.dropBoxRadius, style: .continuous))
                .onTapGesture { onClick?() }
                .padding(NotchRootView.dropBoxInsets)
        }
        .frame(width: NotchRootView.openSize.width, height: NotchRootView.openSize.height, alignment: .top)
    }
}

/// The drop target. Hovering only shifts its colours: a calm tint, nothing moves.
struct DropBox: View {
    var title: String
    var targeted: Bool

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: NotchRootView.dropBoxRadius, style: .continuous)
        ZStack {
            shape.fill(targeted ? Palette.accent.opacity(0.14) : Color.white.opacity(0.04))
            shape.strokeBorder(targeted ? Palette.accent.opacity(0.9) : Color.white.opacity(0.22),
                               style: StrokeStyle(lineWidth: 1.5, dash: [6, 5]))
            VStack(spacing: 8) {
                Image(systemName: "arrow.up")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(targeted ? Color.white : Color.white.opacity(0.8))
                    .frame(width: 36, height: 36)
                    .background(Circle().fill(targeted ? Palette.accent : Color.white.opacity(0.08)))
                Text(title)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(.white)
                Text("Files · Photos · Folders · Links · Text")
                    .font(.system(size: 10.5, weight: .medium))
                    .foregroundStyle(targeted ? Palette.accent.opacity(0.9) : Color.white.opacity(0.42))
            }
        }
        .animation(.easeOut(duration: 0.18), value: targeted)
    }
}

struct ActivityView: View {
    @ObservedObject var app: AppModel

    var body: some View {
        VStack(spacing: 10) {
            row
            if case .received(let files, _) = app.activity, !files.isEmpty {
                ThumbnailStrip(files: files) { app.dismissActivity() }
                    .transition(.opacity.combined(with: .move(edge: .top)))
            }
        }
        .padding(.horizontal, 20)
        .padding(.top, 10)
        .padding(.bottom, 14)
    }

    private var row: some View {
        HStack(spacing: 14) {
            icon.frame(width: 34, height: 34)
            VStack(alignment: .leading, spacing: 5) {
                Text(title)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(.white)
                    .lineLimit(1)
                if let fraction {
                    ProgressBar(fraction: fraction)
                }
                if let subtitle {
                    Text(subtitle)
                        .font(.system(size: 11, weight: .medium))
                        .foregroundStyle(Palette.dim)
                        .lineLimit(2)
                }
            }
            Spacer(minLength: 0)
            actions
        }
    }

    @ViewBuilder private var icon: some View {
        switch app.activity {
        case .connecting:
            ProgressView().controlSize(.small).tint(.white)
        case .sending:
            ZStack {
                PhoneGlyph(color: Palette.accent, size: 26)
                Image(systemName: "arrow.up").font(.system(size: 9, weight: .heavy)).foregroundStyle(Palette.accent).offset(y: 3)
            }
        case .receiving:
            Image(systemName: "arrow.down.circle.fill").font(.system(size: 26)).foregroundStyle(Palette.accent)
        case .sent:
            Image(systemName: "checkmark.circle.fill").font(.system(size: 28)).foregroundStyle(Palette.ok)
                .symbolEffect(.bounce, value: app.activity)
        case .received:
            Image(systemName: "tray.and.arrow.down.fill").font(.system(size: 24)).foregroundStyle(Palette.ok)
                .symbolEffect(.bounce, value: app.activity)
        case .failed:
            Image(systemName: "exclamationmark.triangle.fill").font(.system(size: 24)).foregroundStyle(Palette.bad)
        case .incoming:
            ZStack {
                PhoneGlyph(color: Palette.accent, size: 26)
                Image(systemName: "arrow.down").font(.system(size: 9, weight: .heavy)).foregroundStyle(Palette.accent).offset(y: 3)
            }
            .symbolEffect(.pulse)
        case .idle:
            EmptyView()
        }
    }

    private var title: String {
        let name = app.peerName
        switch app.activity {
        case .connecting(let n): return "Connecting to \(name)… (\(n) item\(n == 1 ? "" : "s"))"
        case .sending(_, let f, let n): return "Sending \(n) item\(n == 1 ? "" : "s") · \(Int(f * 100))%"
        case .sent(let n): return "Sent \(n) item\(n == 1 ? "" : "s") to \(name)"
        case .receiving(_, let f): return "Receiving from \(name) · \(Int(f * 100))%"
        case .received(let files, let texts):
            if files.isEmpty { return texts.count == 1 ? "Copied text from \(name)" : "Received \(texts.count) texts" }
            return "Received \(files.count) file\(files.count == 1 ? "" : "s") from \(name)"
        case .failed: return "Couldn't send"
        case .incoming: return "\(name) wants to send"
        case .idle: return ""
        }
    }

    private var subtitle: String? {
        switch app.activity {
        case .sending(let n, _, _), .receiving(let n, _): return n.isEmpty ? nil : n
        case .received(let files, let texts):
            if files.isEmpty { return texts.last }
            return files.count == 1 ? files[0].lastPathComponent : "Saved to \(files[0].deletingLastPathComponent().lastPathComponent)"
        case .failed(let m): return m
        case .incoming(let summary): return summary
        default: return nil
        }
    }

    private var fraction: Double? {
        switch app.activity {
        case .sending(_, let f, _), .receiving(_, let f): return f
        default: return nil
        }
    }

    @ViewBuilder private var actions: some View {
        if case .received(let files, let texts) = app.activity {
            HStack(spacing: 6) {
                if let url = texts.last.flatMap({ URL(string: $0.trimmingCharacters(in: .whitespacesAndNewlines)) }),
                   url.scheme?.hasPrefix("http") == true {
                    PillButton(title: "Open Link") { NSWorkspace.shared.open(url); app.dismissActivity() }
                } else if files.count > 1 {
                    PillButton(title: "Show All") {
                        NSWorkspace.shared.activateFileViewerSelecting(files)
                        app.dismissActivity()
                    }
                }
            }
        } else if case .incoming = app.activity {
            HStack(spacing: 6) {
                PillButton(title: "Decline") { app.answerIncoming(false) }
                PillButton(title: "Accept", tint: Palette.accent) { app.answerIncoming(true) }
            }
        } else if case .failed = app.activity {
            PillButton(title: "OK") { app.dismissActivity() }
        }
    }
}

/// A long transfer, shrunk around the notch: direction on the left, percentage on the right, a hairline bar below.
struct MiniTransferView: View {
    var activity: Activity
    var notch: CGSize
    var ear: CGFloat

    private var fraction: Double {
        switch activity {
        case .sending(_, let f, _), .receiving(_, let f): return f
        default: return 0
        }
    }

    private var incoming: Bool {
        if case .receiving = activity { return true }
        return false
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 0) {
                Image(systemName: incoming ? "arrow.down" : "arrow.up")
                    .font(.system(size: 11, weight: .bold))
                    .foregroundStyle(Palette.accent)
                    .symbolEffect(.pulse)
                    .frame(width: ear)
                Spacer(minLength: notch.width)
                Text("\(Int(fraction * 100))%")
                    .font(.system(size: 10.5, weight: .semibold).monospacedDigit())
                    .foregroundStyle(.white.opacity(0.85))
                    .contentTransition(.numericText())
                    .frame(width: ear)
            }
            .frame(height: notch.height)
            ProgressBar(fraction: fraction, height: 3)
                .padding(.horizontal, 14)
                .padding(.top, 2)
        }
    }
}

/// Received files as thumbnails; clicking one reveals it in Finder.
struct ThumbnailStrip: View {
    var files: [URL]
    var onReveal: () -> Void

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(files.prefix(12), id: \.self) { url in
                    Thumbnail(url: url)
                        .onTapGesture {
                            NSWorkspace.shared.activateFileViewerSelecting([url])
                            onReveal()
                        }
                        .help(url.lastPathComponent)
                }
                if files.count > 12 {
                    Text("+\(files.count - 12)")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(Palette.dim)
                        .frame(width: 52, height: 52)
                }
            }
        }
        .frame(height: 60)
    }
}

struct Thumbnail: View {
    var url: URL
    @StateObject private var loader = ThumbnailLoader()
    @StateObject private var hover = HoverState()

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 10, style: .continuous).fill(Color.white.opacity(0.08))
            if let img = loader.image {
                Image(nsImage: img).resizable().aspectRatio(contentMode: .fill)
            } else {
                Image(nsImage: NSWorkspace.shared.icon(forFile: url.path)).resizable().padding(8)
            }
        }
        .frame(width: 52, height: 52)
        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 10, style: .continuous)
            .strokeBorder(Color.white.opacity(hover.on ? 0.6 : 0.12), lineWidth: 1))
        .scaleEffect(hover.on ? 1.06 : 1)
        .animation(.easeOut(duration: 0.12), value: hover.on)
        .onHover { hover.on = $0 }
        .onAppear { loader.load(url) }
    }
}

@MainActor
final class ThumbnailLoader: ObservableObject {
    @Published var image: NSImage?
    private var started = false

    func load(_ url: URL) {
        guard !started else { return }
        started = true
        let scale = NSScreen.main?.backingScaleFactor ?? 2
        let req = QLThumbnailGenerator.Request(fileAt: url, size: CGSize(width: 104, height: 104), scale: scale, representationTypes: .thumbnail)
        QLThumbnailGenerator.shared.generateBestRepresentation(for: req) { rep, _ in
            guard let cg = rep?.cgImage else { return }
            let img = NSImage(cgImage: cg, size: .zero)
            DispatchQueue.main.async { self.image = img }
        }
    }
}

struct ProgressBar: View {
    var fraction: Double
    var height: CGFloat = 5

    var body: some View {
        GeometryReader { g in
            ZStack(alignment: .leading) {
                Capsule().fill(Color.white.opacity(0.12))
                Capsule()
                    .fill(LinearGradient(colors: [Palette.accent, Palette.accent2], startPoint: .leading, endPoint: .trailing))
                    .frame(width: max(height + 1, g.size.width * fraction))
                    .animation(.linear(duration: 0.1), value: fraction)
            }
        }
        .frame(height: height)
    }
}

struct PillButton: View {
    var title: String
    var tint: Color? = nil
    var action: () -> Void
    @StateObject private var hover = HoverState()

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 11.5, weight: .semibold))
                .foregroundStyle(.white)
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(Capsule().fill(tint.map { AnyShapeStyle($0.opacity(hover.on ? 1 : 0.85)) }
                    ?? AnyShapeStyle(Color.white.opacity(hover.on ? 0.22 : 0.13))))
        }
        .buttonStyle(.plain)
        .onHover { hover.on = $0 }
    }
}

final class HoverState: ObservableObject {
    @Published var on = false
}
