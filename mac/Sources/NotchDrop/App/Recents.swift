import Foundation

/// Something received lately, shown on the shelf in the open notch.
struct RecentItem: Codable, Identifiable, Equatable {
    var id = UUID()
    var path: String?
    var text: String?
    var date = Date()

    var url: URL? { path.map { URL(fileURLWithPath: $0) } }
    /// A received text that is a web link.
    var link: URL? {
        guard let t = text?.trimmingCharacters(in: .whitespacesAndNewlines), let u = URL(string: t), u.scheme?.hasPrefix("http") == true
        else { return nil }
        return u
    }
}

/// The last few received files and texts, newest first. Files moved or deleted since drop off.
@MainActor
final class Recents: ObservableObject {
    static let limit = 5

    @Published private(set) var items: [RecentItem] = []
    private let file: URL

    init(file: URL) {
        self.file = file
        if let data = try? Data(contentsOf: file), let saved = try? JSONDecoder().decode([RecentItem].self, from: data) {
            items = saved
        }
        prune()
    }

    func add(files: [URL], texts: [String]) {
        let new = files.map { RecentItem(path: $0.path) } + texts.map { RecentItem(text: $0) }
        guard !new.isEmpty else { return }
        let paths = Set(files.map(\.path))
        items = Array((new.reversed() + items.filter { $0.path == nil || !paths.contains($0.path!) }).prefix(Self.limit))
        save()
    }

    func remove(_ item: RecentItem) {
        items.removeAll { $0.id == item.id }
        save()
    }

    func clear() {
        items = []
        save()
    }

    /// Drops files that are no longer where they were saved.
    func prune() {
        let kept = items.filter { $0.path.map { FileManager.default.fileExists(atPath: $0) } ?? true }
        if kept != items { items = kept; save() }
    }

    private func save() {
        if let data = try? JSONEncoder().encode(items) { try? data.write(to: file, options: .atomic) }
    }
}
