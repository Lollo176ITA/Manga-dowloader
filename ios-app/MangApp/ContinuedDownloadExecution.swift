import BackgroundTasks
import Foundation

/** Runtime CPU/rete per un'operazione avviata dall'utente; URLSession resta il fallback. */
@available(iOS 26.0, *)
final class ContinuedDownloadExecution {
    static let identifierPrefix = "com.lorenzo.mangapp.downloads.continued."
    private var requests: [String: String] = [:]
    private var active: [String: BGContinuedProcessingTask] = [:]
    private var registered = false
    var onLaunch: ((String) -> Void)?
    var onExpiration: ((String) -> Void)?

    init() {
        registered = BGTaskScheduler.shared.register(forTaskWithIdentifier: Self.identifierPrefix + "*", using: .main) { [weak self] task in
            guard let self, let task = task as? BGContinuedProcessingTask else { task.setTaskCompleted(success: false); return }
            let id = String(task.identifier.dropFirst(Self.identifierPrefix.count))
            guard self.requests[id] == task.identifier else { task.setTaskCompleted(success: false); return }
            self.active[id] = task
            task.progress.totalUnitCount = 1
            task.progress.completedUnitCount = 0
            task.expirationHandler = { [weak self, weak task] in
                DispatchQueue.main.async {
                    guard let self, let task, self.active[id] === task else { return }
                    self.onExpiration?(id)
                }
            }
            self.onLaunch?(id)
        }
    }

    func begin(id: String, title: String) {
        guard registered, requests[id] == nil else { return }
        let identifier = Self.identifierPrefix + id
        let request = BGContinuedProcessingTaskRequest(identifier: identifier, title: title, subtitle: "Preparazione download")
        request.strategy = .fail
        requests[id] = identifier
        do { try BGTaskScheduler.shared.submit(request) }
        catch {
            requests.removeValue(forKey: id)
            NSLog("Continued download non disponibile: %@", error.localizedDescription)
        }
    }

    func hasRuntime(id: String) -> Bool { active[id] != nil }

    func update(id: String, completed: Int64, total: Int64, title: String, message: String) {
        guard let task = active[id] else { return }
        task.progress.totalUnitCount = max(1, total)
        task.progress.completedUnitCount = min(max(0, completed), task.progress.totalUnitCount)
        task.updateTitle(title, subtitle: message)
    }

    func finish(id: String, success: Bool) {
        if let identifier = requests.removeValue(forKey: id) { BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: identifier) }
        if let task = active.removeValue(forKey: id) {
            task.expirationHandler = nil
            if success { task.progress.completedUnitCount = task.progress.totalUnitCount }
            task.setTaskCompleted(success: success)
        }
    }
}
