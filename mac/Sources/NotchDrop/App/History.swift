import Foundation

/// One item sent or received, kept in the History window.
struct HistoryEntry: Codable, Identifiable, Equatable {
    var id = UUID()
    var date = Date()
    var incoming: Bool
    var name: String
    var path: String?
    var text: String?
    var size: Int64?

    var url: URL? { path.map { URL(fileURLWithPath: $0) } }
    var fileExists: Bool { path.map { FileManager.default.fileExists(atPath: $0) } ?? false }
    var link: URL? {
        guard let t = text?.trimmingCharacters(in: .whitespacesAndNewlines), let u = URL(string: t), u.scheme?.hasPrefix("http") == true
        else { return nil }
        return u
    }
}

/// Everything sent and received, newest first, capped at [limit] entries.
@MainActor
final class History: ObservableObject {
    static let limit = 500

    @Published private(set) var entries: [HistoryEntry] = []
    private let file: URL

    init(file: URL) {
        self.file = file
        if let data = try? Data(contentsOf: file), let saved = try? JSONDecoder().decode([HistoryEntry].self, from: data) {
            entries = saved
        }
    }

    func log(incoming: Bool, files: [(url: URL?, name: String, size: Int64?)], texts: [String]) {
        let new = files.map { HistoryEntry(incoming: incoming, name: $0.name, path: $0.url?.path, size: $0.size) }
            + texts.map { HistoryEntry(incoming: incoming, name: Self.title(of: $0), text: $0) }
        guard !new.isEmpty else { return }
        entries = Array((new.reversed() + entries).prefix(Self.limit))
        save()
    }

    func remove(_ ids: Set<UUID>) {
        entries.removeAll { ids.contains($0.id) }
        save()
    }

    func clear() {
        entries = []
        save()
    }

    private static func title(of text: String) -> String {
        let line = text.split(whereSeparator: \.isNewline).first.map(String.init) ?? text
        return line.trimmingCharacters(in: .whitespaces).prefix(120).description
    }

    private func save() {
        if let data = try? JSONEncoder().encode(entries) { try? data.write(to: file, options: .atomic) }
    }
}
