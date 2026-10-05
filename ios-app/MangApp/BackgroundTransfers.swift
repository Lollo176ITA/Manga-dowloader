import Foundation

/** URLSession possiede la rete; questo delegate salva i file prima che iOS elimini i temporanei. */
final class BackgroundTransfers: NSObject, URLSessionDownloadDelegate {
    static let sessionIdentifier = "com.lorenzo.mangapp.downloads.background.v1"
    private let root: URL
    private let configuration: URLSessionConfiguration
    private lazy var session = URLSession(configuration: configuration, delegate: self, delegateQueue: .main)
    private var tasks: [String: URLSessionTask] = [:]
    private var definitions: [String: NativeTransfer] = [:]
    private var reconnecting = false
    /** Trasferimenti in attesa di diventare task: creare un task è una chiamata sincrona a nsurlsessiond. */
    private var queued: [NativeTransfer] = []
    private var draining = false
    private static let scheduleBatch = 8
    var onFinished: ((String, Result<Int64, Error>) -> Void)?
    var onProgress: ((String, Int64, Int64) -> Void)?
    var onEventsFinished: (() -> Void)?

    init(root: URL, configuration: URLSessionConfiguration) {
        self.root = root
        self.configuration = configuration
        super.init()
    }

    static func backgroundConfiguration() -> URLSessionConfiguration {
        let config = URLSessionConfiguration.background(withIdentifier: sessionIdentifier)
        config.isDiscretionary = false
        config.sessionSendsLaunchEvents = true
        config.waitsForConnectivity = true
        config.httpMaximumConnectionsPerHost = 4
        config.timeoutIntervalForRequest = 90
        config.timeoutIntervalForResource = 24 * 60 * 60
        return config
    }

    /** Il confronto usa taskDescription e il sandbox corrente, non task ID di un vecchio processo. */
    func reconnect(jobs: [NativeDownloadJob], completion: @escaping (Set<String>, [NativeTransfer]) -> Void) {
        let transfers = jobs.filter { $0.phase.isActive }.flatMap(\.pendingTransfers)
        definitions = Dictionary(transfers.map { ($0.key, $0) }, uniquingKeysWith: { first, _ in first })
        reconnecting = true
        session.getAllTasks { liveTasks in
            DispatchQueue.main.async {
                let files = Set(transfers.filter { self.isComplete($0) }.map(\.key))
                let descriptions = Dictionary(liveTasks.map { ($0.taskIdentifier, $0.taskDescription ?? "") }, uniquingKeysWith: { first, _ in first })
                let recovery = DownloadRecovery.reconcile(jobs: jobs, tasks: descriptions, files: files)
                for task in liveTasks {
                    if recovery.keptTaskIds.contains(task.taskIdentifier), let key = task.taskDescription, self.definitions[key] != nil {
                        self.tasks[key] = task
                        if task.state == .suspended { task.resume() }
                    } else { task.cancel() }
                }
                // Pubblica il recupero prima di avviare nuovi callback di trasferimento.
                self.reconnecting = false
                completion(files, recovery.toSchedule)
            }
        }
    }

    /** Un capitolo ha centinaia di pagine: i task nascono a blocchi, il main thread resta libero tra un blocco e l'altro. */
    func schedule(_ transfers: [NativeTransfer]) {
        let waiting = Set(queued.map(\.key))
        queued += transfers.filter { tasks[$0.key] == nil && !waiting.contains($0.key) }
        if !draining { drainQueued() }
    }

    private func drainQueued() {
        var started = 0
        while started < Self.scheduleBatch, !queued.isEmpty {
            let transfer = queued.removeFirst()
            guard tasks[transfer.key] == nil else { continue }
            start(transfer)
            started += 1
        }
        draining = !queued.isEmpty
        if draining { DispatchQueue.main.async { [weak self] in self?.drainQueued() } }
    }

    private func start(_ transfer: NativeTransfer) {
        guard validRelativePath(transfer.relativePath), let url = URL(string: transfer.url), ["https", "http"].contains(url.scheme?.lowercased() ?? "") else {
            onFinished?(transfer.key, .failure(DownloadCoreError.invalidTransfer)); return
        }
        if isComplete(transfer) { onFinished?(transfer.key, .success(fileSize(transfer))); return }
        var request = URLRequest(url: url)
        request.setValue("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36", forHTTPHeaderField: "User-Agent")
        request.setValue(transfer.referer, forHTTPHeaderField: "Referer")
        let task = session.downloadTask(with: request)
        task.taskDescription = transfer.key
        definitions[transfer.key] = transfer
        tasks[transfer.key] = task
        task.resume()
    }

    func cancel(jobId: String) {
        queued.removeAll { $0.jobId == jobId }
        let cancelled = tasks.filter { definitions[$0.key]?.jobId == jobId }
        for (key, task) in cancelled {
            tasks.removeValue(forKey: key)
            definitions.removeValue(forKey: key)
            task.cancel()
        }
        definitions = definitions.filter { $0.value.jobId != jobId }
    }

    func invalidate() {
        queued.removeAll()
        session.invalidateAndCancel()
    }

    func fileURL(for transfer: NativeTransfer) -> URL { root.appendingPathComponent(transfer.relativePath) }
    private func fileSize(_ transfer: NativeTransfer) -> Int64 {
        ((try? FileManager.default.attributesOfItem(atPath: fileURL(for: transfer).path)[.size]) as? NSNumber)?.int64Value ?? 0
    }
    private func isComplete(_ transfer: NativeTransfer) -> Bool { validRelativePath(transfer.relativePath) && fileSize(transfer) > 0 }
    private func accepts(_ task: URLSessionTask, key: String) -> Bool {
        tasks[key]?.taskIdentifier == task.taskIdentifier || (reconnecting && tasks[key] == nil && definitions[key] != nil)
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64, totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        guard let key = downloadTask.taskDescription, accepts(downloadTask, key: key) else { return }
        onProgress?(key, totalBytesWritten, totalBytesExpectedToWrite)
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        guard let key = downloadTask.taskDescription, accepts(downloadTask, key: key), let transfer = definitions[key] else { return }
        let result: Result<Int64, Error>
        do {
            guard let response = downloadTask.response as? HTTPURLResponse else { throw DownloadCoreError.invalidTransfer }
            guard (200..<300).contains(response.statusCode) else { throw DownloadCoreError.http(response.statusCode) }
            if response.mimeType?.lowercased().hasPrefix("text/") == true { throw DownloadCoreError.htmlResponse }
            let size = (try FileManager.default.attributesOfItem(atPath: location.path)[.size] as? NSNumber)?.int64Value ?? 0
            guard size > 0 else { throw DownloadCoreError.emptyFile }
            let target = fileURL(for: transfer)
            try FileManager.default.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
            let temporary = target.appendingPathExtension("part")
            try? FileManager.default.removeItem(at: temporary)
            try FileManager.default.moveItem(at: location, to: temporary)
            if FileManager.default.fileExists(atPath: target.path) {
                _ = try FileManager.default.replaceItemAt(target, withItemAt: temporary)
            } else { try FileManager.default.moveItem(at: temporary, to: target) }
            result = .success(size)
        } catch { result = .failure(error) }
        tasks.removeValue(forKey: key)
        definitions.removeValue(forKey: key)
        onFinished?(key, result)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let key = task.taskDescription, accepts(task, key: key) else { return }
        tasks.removeValue(forKey: key)
        definitions.removeValue(forKey: key)
        onFinished?(key, .failure(error ?? DownloadCoreError.emptyFile))
    }

    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) { onEventsFinished?() }
}
