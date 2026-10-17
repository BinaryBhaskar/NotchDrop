import AppKit
import UniformTypeIdentifiers

/// The "NotchDrop" entry in the Share menu. Extensions are sandboxed and can't reach the phone, so this
/// only collects the shared items, leaves them on a private pasteboard and wakes the app with
/// `notchdrop://share`; the app sends them. Keep the names in sync with `ShareHandoff` in AppModel.swift.
@objc(ShareViewController)
final class ShareViewController: NSViewController {
    private static let pasteboard = NSPasteboard.Name("com.amurot.notchdrop.share")
    private static let dataType = NSPasteboard.PasteboardType("com.amurot.notchdrop.data")
    private static let nameType = NSPasteboard.PasteboardType("com.amurot.notchdrop.name")

    override func loadView() {
        let spinner = NSProgressIndicator()
        spinner.style = .spinning
        spinner.controlSize = .small
        spinner.startAnimation(nil)
        let label = NSTextField(labelWithString: "Sending with NotchDrop…")
        let stack = NSStackView(views: [spinner, label])
        stack.edgeInsets = NSEdgeInsets(top: 20, left: 24, bottom: 20, right: 24)
        view = stack
    }

    override func viewDidAppear() {
        super.viewDidAppear()
        let providers = (extensionContext?.inputItems as? [NSExtensionItem] ?? []).flatMap { $0.attachments ?? [] }
        Task { @MainActor in
            var items: [NSPasteboardItem] = []
            for p in providers {
                if let item = await Self.load(p) { items.append(item) }
            }
            hand(items)
        }
    }

    private func hand(_ items: [NSPasteboardItem]) {
        guard !items.isEmpty else {
            extensionContext?.cancelRequest(withError: NSError(domain: NSCocoaErrorDomain, code: NSUserCancelledError))
            return
        }
        let pb = NSPasteboard(name: Self.pasteboard)
        pb.clearContents()
        pb.writeObjects(items)
        NSWorkspace.shared.open(URL(string: "notchdrop://share")!)
        extensionContext?.completeRequest(returningItems: nil)
    }

    private static func load(_ p: NSItemProvider) async -> NSPasteboardItem? {
        let item = NSPasteboardItem()
        if p.hasItemConformingToTypeIdentifier(UTType.fileURL.identifier),
           let url = await loadURL(p, UTType.fileURL), url.isFileURL {
            item.setString(url.absoluteString, forType: .fileURL)
            return item
        }
        if p.hasItemConformingToTypeIdentifier(UTType.url.identifier),
           let url = await loadURL(p, UTType.url), !url.isFileURL {
            item.setString(url.absoluteString, forType: .string)
            return item
        }
        if p.hasItemConformingToTypeIdentifier(UTType.plainText.identifier),
           let text = await loadText(p), !text.isEmpty {
            item.setString(text, forType: .string)
            return item
        }
        // Images and other data with no file behind them (e.g. from Photos): pass the bytes.
        guard let type = p.registeredTypeIdentifiers.first,
              let (data, name) = await loadData(p, type) else { return nil }
        item.setData(data, forType: dataType)
        item.setString(name, forType: nameType)
        return item
    }

    private static func loadURL(_ p: NSItemProvider, _ type: UTType) async -> URL? {
        await withCheckedContinuation { cont in
            p.loadItem(forTypeIdentifier: type.identifier) { value, _ in
                switch value {
                case let url as URL: cont.resume(returning: url)
                case let data as Data: cont.resume(returning: URL(dataRepresentation: data, relativeTo: nil))
                case let s as String: cont.resume(returning: URL(string: s))
                default: cont.resume(returning: nil)
                }
            }
        }
    }

    private static func loadText(_ p: NSItemProvider) async -> String? {
        await withCheckedContinuation { cont in
            p.loadItem(forTypeIdentifier: UTType.plainText.identifier) { value, _ in
                switch value {
                case let s as String: cont.resume(returning: s)
                case let s as NSAttributedString: cont.resume(returning: s.string)
                case let d as Data: cont.resume(returning: String(data: d, encoding: .utf8))
                default: cont.resume(returning: nil)
                }
            }
        }
    }

    private static func loadData(_ p: NSItemProvider, _ type: String) async -> (Data, String)? {
        let suggested = p.suggestedName
        return await withCheckedContinuation { cont in
            // The file only exists inside the callback, so read it there.
            _ = p.loadFileRepresentation(forTypeIdentifier: type) { url, _ in
                guard let url, let data = try? Data(contentsOf: url) else { cont.resume(returning: nil); return }
                var name = suggested ?? url.deletingPathExtension().lastPathComponent
                if (name as NSString).pathExtension.isEmpty, !url.pathExtension.isEmpty {
                    name += "." + url.pathExtension
                }
                cont.resume(returning: (data, name))
            }
        }
    }
}
