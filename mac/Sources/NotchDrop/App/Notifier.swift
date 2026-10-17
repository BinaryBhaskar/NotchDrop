import AppKit
import UserNotifications

/// System notifications for received items, with Show in Finder / Open (files) or Copy / Open Link (text).
@MainActor
final class Notifier: NSObject, UNUserNotificationCenterDelegate {
    static let shared = Notifier()

    private enum Action {
        static let reveal = "reveal", open = "open", copy = "copy", openLink = "openLink"
    }
    private enum Category {
        static let files = "files", text = "text", link = "link"
    }

    /// Notifications need an app bundle; the command-line self-test has none.
    private var center: UNUserNotificationCenter? {
        Bundle.main.bundleURL.pathExtension == "app" ? .current() : nil
    }
    private var asked = false

    func start() {
        guard let center else { return }
        center.delegate = self
        let reveal = UNNotificationAction(identifier: Action.reveal, title: "Show in Finder")
        let open = UNNotificationAction(identifier: Action.open, title: "Open", options: .foreground)
        let copy = UNNotificationAction(identifier: Action.copy, title: "Copy")
        let openLink = UNNotificationAction(identifier: Action.openLink, title: "Open Link", options: .foreground)
        center.setNotificationCategories([
            UNNotificationCategory(identifier: Category.files, actions: [reveal, open], intentIdentifiers: []),
            UNNotificationCategory(identifier: Category.text, actions: [copy], intentIdentifiers: []),
            UNNotificationCategory(identifier: Category.link, actions: [openLink, copy], intentIdentifiers: []),
        ])
    }

    func received(_ batch: ReceivedBatch, from phone: String) {
        guard let center else { return }
        let content = UNMutableNotificationContent()
        content.title = "From \(phone)"
        if let first = batch.files.first {
            content.body = batch.files.count == 1 ? first.lastPathComponent
                : "\(first.lastPathComponent) and \(batch.files.count - 1) more"
            content.categoryIdentifier = Category.files
            // No image attachment: the system would move the file into its own store.
            content.userInfo = ["paths": batch.files.map(\.path)]
        } else if let text = batch.texts.last {
            content.body = text.count > 240 ? String(text.prefix(240)) + "…" : text
            let isLink = URL(string: text.trimmingCharacters(in: .whitespacesAndNewlines))?.scheme?.hasPrefix("http") == true
            content.categoryIdentifier = isLink ? Category.link : Category.text
            content.userInfo = ["text": text]
        } else { return }
        let post = { center.add(UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)) }
        if asked { post(); return }
        center.requestAuthorization(options: [.alert, .sound]) { granted, _ in
            Task { @MainActor in
                self.asked = true
                if granted { post() }
            }
        }
    }

    // MARK: UNUserNotificationCenterDelegate

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                            withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .list])
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                            withCompletionHandler completionHandler: @escaping () -> Void) {
        let info = response.notification.request.content.userInfo
        let paths = info["paths"] as? [String] ?? []
        let text = info["text"] as? String
        let action = response.actionIdentifier
        Task { @MainActor in
            let urls = paths.map { URL(fileURLWithPath: $0) }.filter { FileManager.default.fileExists(atPath: $0.path) }
            switch action {
            case Action.open:
                urls.forEach { NSWorkspace.shared.open($0) }
            case Action.copy:
                if let text {
                    NSPasteboard.general.clearContents()
                    NSPasteboard.general.setString(text, forType: .string)
                }
            case Action.openLink:
                if let t = text?.trimmingCharacters(in: .whitespacesAndNewlines), let u = URL(string: t), u.scheme?.hasPrefix("http") == true {
                    NSWorkspace.shared.open(u)
                } else if let text {
                    NSPasteboard.general.clearContents()
                    NSPasteboard.general.setString(text, forType: .string)
                }
            default: // Show in Finder, or a click on the notification itself
                if !urls.isEmpty { NSWorkspace.shared.activateFileViewerSelecting(urls) }
                else if let t = text?.trimmingCharacters(in: .whitespacesAndNewlines), let u = URL(string: t), u.scheme?.hasPrefix("http") == true {
                    NSWorkspace.shared.open(u)
                }
            }
            completionHandler()
        }
    }
}
