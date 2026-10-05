import UIKit
import UserNotifications
import UniformTypeIdentifiers
import LocalAuthentication
import Shared

final class NativePlatformServices: NSObject, IosPlatformServices, UIDocumentPickerDelegate {
    weak var presenter: UIViewController?
    var onReaderConfiguration: (() -> Void)?
    var onNotificationPermissionChanged: ((Bool) -> Void)?
    private(set) var allowLandscape = false
    private(set) var fullscreen = false
    private var notificationPermission = false {
        didSet { onNotificationPermissionChanged?(notificationPermission) }
    }
    private var fileResult: IosFileResult?
    private var exportResult: IosBooleanResult?
    private var authentication: LAContext?

    var documentsDirectory: String {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].path
    }
    var cacheDirectory: String {
        FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].path
    }

    func notificationsAllowed() -> Bool { notificationPermission }

    func refreshNotificationPermission() {
        UNUserNotificationCenter.current().getNotificationSettings { [weak self] settings in
            DispatchQueue.main.async {
                self?.notificationPermission = [.authorized, .provisional, .ephemeral].contains(settings.authorizationStatus)
            }
        }
    }

    func requestNotifications(result: IosBooleanResult) {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .badge, .sound]) { [weak self] granted, _ in
            DispatchQueue.main.async {
                self?.notificationPermission = granted
                result.complete(value: granted)
            }
        }
    }

    func openNotificationSettings() {
        let value: String
        if #available(iOS 16.0, *) { value = UIApplication.openNotificationSettingsURLString }
        else { value = UIApplication.openSettingsURLString }
        if let url = URL(string: value) { UIApplication.shared.open(url) }
    }

    func openUrl(url: String) -> Bool {
        guard let target = URL(string: url), ["https", "http"].contains(target.scheme?.lowercased() ?? ""), UIApplication.shared.canOpenURL(target) else { return false }
        UIApplication.shared.open(target)
        return true
    }

    func exportBackupFile(path: String, result: IosBooleanResult) {
        guard exportResult == nil, fileResult == nil else { result.complete(value: false); return }
        exportResult = result
        let picker = UIDocumentPickerViewController(forExporting: [URL(fileURLWithPath: path)], asCopy: true)
        picker.delegate = self
        if !present(picker) { exportResult = nil; result.complete(value: false) }
    }

    func pickBackupFile(result: IosFileResult) {
        guard fileResult == nil, exportResult == nil else { result.complete(path: nil); return }
        fileResult = result
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.json, .data], asCopy: true)
        picker.delegate = self
        if !present(picker) { fileResult = nil; result.complete(path: nil) }
    }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        if let result = exportResult {
            exportResult = nil
            result.complete(value: !urls.isEmpty)
            return
        }
        let result = fileResult
        fileResult = nil
        guard let source = urls.first else { result?.complete(path: nil); return }
        let scoped = source.startAccessingSecurityScopedResource()
        defer { if scoped { source.stopAccessingSecurityScopedResource() } }
        do {
            // Kotlin legge una copia privata: nessun accesso esterno dopo la fine dello scope.
            let destination = FileManager.default.temporaryDirectory.appendingPathComponent("mangapp-import-\(UUID().uuidString).json")
            try FileManager.default.copyItem(at: source, to: destination)
            result?.complete(path: destination.path)
        } catch {
            result?.complete(path: nil)
            showError("Impossibile importare il backup", message: error.localizedDescription)
        }
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
        let export = exportResult
        exportResult = nil
        export?.complete(value: false)
        let result = fileResult
        fileResult = nil
        result?.complete(path: nil)
    }

    func configureReader(allowLandscape: Bool, fullscreen: Bool, keepScreenOn: Bool) {
        let changed = self.allowLandscape != allowLandscape || self.fullscreen != fullscreen
        self.allowLandscape = allowLandscape
        self.fullscreen = fullscreen
        UIApplication.shared.isIdleTimerDisabled = keepScreenOn
        if changed { onReaderConfiguration?() }
    }

    func biometricAvailable() -> Bool {
        LAContext().canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: nil)
    }

    func authenticate(result: IosBiometricResult) {
        authentication?.invalidate()
        let context = LAContext()
        context.localizedFallbackTitle = "Usa PIN"
        guard context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: nil) else {
            result.complete(outcome: 0, message: nil)
            return
        }
        authentication = context
        context.evaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, localizedReason: "Sblocca il controllo genitori") { [weak self] success, error in
            DispatchQueue.main.async {
                self?.authentication = nil
                let fallback = (error as? LAError)?.code == .userFallback
                result.complete(outcome: success ? 1 : (fallback ? 0 : -1), message: error?.localizedDescription)
            }
        }
    }

    func notify(identifier: String, title: String, body: String) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: identifier, content: content, trigger: nil))
    }

    @discardableResult
    private func present(_ controller: UIViewController) -> Bool {
        guard let presenter, presenter.presentedViewController == nil else { return false }
        presenter.present(controller, animated: true)
        return true
    }

    private func showError(_ title: String, message: String) {
        let alert = UIAlertController(title: title, message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "OK", style: .default))
        _ = present(alert)
    }
}
