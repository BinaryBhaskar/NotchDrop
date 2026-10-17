import AppKit
import SwiftUI

@MainActor
final class HistoryWindowController: NSObject {
    private var window: NSWindow?
    private let app: AppModel

    init(app: AppModel) { self.app = app }

    func show() {
        if window == nil {
            let w = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 560, height: 520),
                             styleMask: [.titled, .closable, .miniaturizable, .resizable, .fullSizeContentView],
                             backing: .buffered, defer: false)
            w.title = "NotchDrop History"
            w.toolbarStyle = .unified
            w.isReleasedWhenClosed = false
            w.minSize = NSSize(width: 420, height: 320)
            w.contentView = NSHostingView(rootView: HistoryView(app: app, history: app.history))
            w.setFrameAutosaveName("HistoryWindow")
            if !w.setFrameUsingName("HistoryWindow") { w.center() }
            window = w
        }
        NSApp.activate(ignoringOtherApps: true)
        window?.makeKeyAndOrderFront(nil)
    }
}

/// Every item sent and received. Double-click to open; right-click for more.
struct HistoryView: View {
    @ObservedObject var app: AppModel
    @ObservedObject var history: History
    @StateObject private var query = Box("")
    @StateObject private var selection = Box(Set<UUID>())
    @StateObject private var confirmClear = Box(false)

    private var shown: [HistoryEntry] {
        let q = query.value.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { return history.entries }
        return history.entries.filter { $0.name.localizedCaseInsensitiveContains(q) || $0.text?.localizedCaseInsensitiveContains(q) == true }
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 10) {
                HStack(spacing: 6) {
                    Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                    TextField("Search", text: $query.value).textFieldStyle(.plain)
                }
                .padding(.horizontal, 8).padding(.vertical, 5)
                .background(RoundedRectangle(cornerRadius: 7).fill(Color.primary.opacity(0.06)))
                Button("Clear All…") { confirmClear.value = true }
                    .disabled(history.entries.isEmpty)
            }
            .padding(.horizontal, 14).padding(.top, 34).padding(.bottom, 10)
            Divider()
            if shown.isEmpty {
                VStack(spacing: 8) {
                    Spacer()
                    Image(systemName: history.entries.isEmpty ? "clock.arrow.circlepath" : "magnifyingglass")
                        .font(.system(size: 28)).foregroundStyle(.tertiary)
                    Text(history.entries.isEmpty ? "Nothing sent or received yet" : "No matches")
                        .foregroundStyle(.secondary)
                    Spacer()
                }
                .frame(maxWidth: .infinity)
            } else {
                List(shown, selection: $selection.value) { e in
                    HistoryRow(entry: e, peer: app.peerName).tag(e.id)
                }
                .listStyle(.inset)
                .contextMenu(forSelectionType: UUID.self) { ids in
                    menu(for: history.entries.filter { ids.contains($0.id) })
                } primaryAction: { ids in
                    history.entries.filter { ids.contains($0.id) }.forEach(open)
                }
                .onDeleteCommand { history.remove(selection.value) }
            }
        }
        .frame(minWidth: 420, minHeight: 320)
        .ignoresSafeArea(edges: .top)
        .alert("Clear all history?", isPresented: $confirmClear.value) {
            Button("Clear", role: .destructive) { history.clear() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This only clears the list. Received files stay where they were saved.")
        }
    }

    @ViewBuilder
    private func menu(for entries: [HistoryEntry]) -> some View {
        let files = entries.compactMap { $0.fileExists ? $0.url : nil }
        if !files.isEmpty {
            Button("Open") { files.forEach { NSWorkspace.shared.open($0) } }
            Button("Show in Finder") { NSWorkspace.shared.activateFileViewerSelecting(files) }
        }
        if entries.count == 1, let e = entries.first {
            if let link = e.link { Button("Open Link") { NSWorkspace.shared.open(link) } }
            if let t = e.text { Button("Copy") { copy(t) } }
            if e.fileExists || e.text != nil {
                Button("Send to \(app.peerName) Again") { app.resend(e) }.disabled(app.peer == nil)
            }
        }
        Divider()
        Button("Remove from History") { history.remove(Set(entries.map(\.id))) }
    }

    private func open(_ e: HistoryEntry) {
        if let url = e.url, e.fileExists { NSWorkspace.shared.open(url) }
        else if let link = e.link { NSWorkspace.shared.open(link) }
        else if let t = e.text { copy(t) }
        else { NSSound.beep() }
    }

    private func copy(_ text: String) {
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(text, forType: .string)
    }
}

private struct HistoryRow: View {
    var entry: HistoryEntry
    var peer: String

    private static let time: RelativeDateTimeFormatter = {
        let f = RelativeDateTimeFormatter()
        f.unitsStyle = .short
        return f
    }()

    var body: some View {
        HStack(spacing: 10) {
            icon.frame(width: 28, height: 28)
            VStack(alignment: .leading, spacing: 2) {
                Text(entry.name).lineLimit(1).truncationMode(.middle)
                    .foregroundStyle(entry.path != nil && !entry.fileExists ? .secondary : .primary)
                HStack(spacing: 4) {
                    Image(systemName: entry.incoming ? "arrow.down" : "arrow.up").font(.system(size: 9, weight: .bold))
                    Text(subtitle)
                }
                .font(.caption).foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 2)
    }

    private var subtitle: String {
        var parts = [entry.incoming ? "From \(peer)" : "To \(peer)", Self.time.localizedString(for: entry.date, relativeTo: Date())]
        if let size = entry.size { parts.append(ByteCountFormatter.string(fromByteCount: size, countStyle: .file)) }
        if entry.path != nil && !entry.fileExists { parts.append("Moved or deleted") }
        return parts.joined(separator: " · ")
    }

    @ViewBuilder private var icon: some View {
        if let url = entry.url, entry.fileExists {
            Image(nsImage: NSWorkspace.shared.icon(forFile: url.path)).resizable()
        } else {
            Image(systemName: entry.link != nil ? "link" : entry.text != nil ? "text.alignleft" : "doc")
                .font(.system(size: 14, weight: .medium)).foregroundStyle(.secondary)
                .frame(width: 28, height: 28)
                .background(RoundedRectangle(cornerRadius: 6).fill(Color.primary.opacity(0.06)))
        }
    }
}
