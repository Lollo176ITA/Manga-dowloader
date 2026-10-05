import Foundation
import UIKit
import Shared

/** Tutti i metodi e i delegate girano sul main thread; soltanto parsing/CBZ lavorano su IO Kotlin. */
final class NativeDownloadManager: NSObject, IosDownloadServices {
    private let store: DownloadQueueStore
    private let transfers: BackgroundTransfers
    private var continued: AnyObject?
    private var engine: IosDownloadEngine?
    private var observer: IosDownloadObserver?
    private var jobs: [NativeDownloadJob] = []
    private var ready = false
    private var foreground = UIApplication.shared.applicationState != .background
    private var backgroundTimeExpired = false
    private var lease: UIBackgroundTaskIdentifier = .invalid
    private var work: (id: String, chapter: Int?)?
    private var pausedWork: Set<String> = []
    private var backgroundCompletion: (() -> Void)?
    private var eventsFinished = false
    private var lastProgressUpdate = Date.distantPast
    private var partialProgress: [String: (written: Int64, expected: Int64)] = [:]
    private var persistenceError: String?

    init(root: URL) {
        store = DownloadQueueStore(root: root)
        transfers = BackgroundTransfers(root: root, configuration: BackgroundTransfers.backgroundConfiguration())
        super.init()
        do {
            try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true,
                attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
            jobs = try store.load()
            for index in jobs.indices where jobs[index].phase.isActive {
                jobs[index].phase = jobs[index].manifest == nil ? .queued : .transferring
                jobs[index].message = "Ripresa del download"
            }
        } catch { persistenceError = error.localizedDescription }
        if #available(iOS 26.0, *) {
            let runtime = ContinuedDownloadExecution()
            runtime.onLaunch = { [weak self] id in self?.updateContinuedProgress(id); self?.pump() }
            runtime.onExpiration = { [weak self] id in self?.stop(ids: [id]) }
            continued = runtime
        }
        transfers.onFinished = { [weak self] key, result in self?.pageFinished(key: key, result: result) }
        transfers.onProgress = { [weak self] key, written, expected in self?.pageProgress(key: key, written: written, expected: expected) }
        transfers.onEventsFinished = { [weak self] in self?.eventsFinished = true; self?.drainBackgroundCompletion() }
    }

    func connect(engine: IosDownloadEngine, observer: IosDownloadObserver) {
        self.engine = engine
        self.observer = observer
        publish()
        guard persistenceError == nil else { return }
        transfers.reconnect(jobs: jobs) { [weak self] files, reschedule in
            guard let self else { return }
            for index in self.jobs.indices where self.jobs[index].phase.isActive {
                self.jobs[index].completedPages = Set(self.jobs[index].transfers.filter {
                    files.contains($0.key) || self.jobs[index].finishedChapters.contains($0.chapterIndex)
                }.map(\.key))
            }
            self.ready = true
            if self.persist() {
                let activeIds = Set(self.jobs.filter { $0.phase.isActive }.map(\.id))
                self.jobs.filter { !$0.phase.isActive }.forEach { self.transfers.cancel(jobId: $0.id) }
                self.transfers.schedule(reschedule.filter { activeIds.contains($0.jobId) })
                self.pump()
            }
        }
    }

    func enqueue(request_: String) {
        do {
            let decoded = try JSONDecoder().decode(NativeDownloadRequest.self, from: Data(request_.utf8))
            let job = NativeDownloadJob(id: UUID().uuidString, request: decoded)
            jobs.append(job)
            guard persist() else { return }
            if foreground && job.allowsContinuedProcessingRequest, #available(iOS 26.0, *) {
                (continued as? ContinuedDownloadExecution)?.begin(id: job.id, title: decoded.seriesTitle ?? "Download manga")
            }
            pump()
        } catch { NSLog("Richiesta download non valida: %@", error.localizedDescription) }
    }

    func stop(ids: [String]) {
        for id in ids {
            guard let index = jobs.firstIndex(where: { $0.id == id && $0.phase.isActive }) else { continue }
            jobs[index].phase = .cancelled
            jobs[index].message = "Download annullato"
            transfers.cancel(jobId: id)
            engine?.cancel(jobId: id)
            finishContinued(id, success: false)
            // Il lavoro IO annullato potrebbe ancora usare la directory: cleanup al suo callback.
            if work?.id != id { removeStaging(id) }
        }
        if persist() { pump() }
    }

    func becameActive() {
        foreground = true
        backgroundTimeExpired = false
        endLease()
        for index in jobs.indices where jobs[index].phase == .paused && !pausedWork.contains(jobs[index].id) {
            jobs[index].phase = jobs[index].manifest == nil ? .queued : .transferring
        }
        if persist() { pump() }
    }

    func enteredBackground() {
        foreground = false
        ensureLease()
    }

    func handleBackgroundEvents(identifier: String, completion: @escaping () -> Void) {
        guard identifier == BackgroundTransfers.sessionIdentifier else { completion(); return }
        backgroundCompletion = completion
        backgroundTimeExpired = false
        // didFinishEvents può arrivare prima del metodo UIApplicationDelegate.
        ensureLease()
        pump()
        drainBackgroundCompletion()
    }

    private func pump() {
        guard ready, persistenceError == nil, engine != nil else { drainBackgroundCompletion(); return }
        // Un solo lavoro CPU alla volta: evita finalizzazioni concorrenti dello stesso archivio.
        guard work == nil else { return }
        let candidates = jobs.filter { $0.phase.isActive && $0.phase != .paused }
        for job in candidates {
            guard mayUseCPU(job.id) else { continue }
            guard let manifest = job.manifest else {
                startPreparation(job.id)
                return
            }
            if job.finishedChapters.count == manifest.chapters.count {
                complete(job.id)
                continue
            }
            if let chapterIndex = manifest.chapters.indices.first(where: { index in
                !job.finishedChapters.contains(index) && job.transfers.filter { $0.chapterIndex == index }.allSatisfy { job.completedPages.contains($0.key) }
            }) {
                startFinalization(job.id, chapter: chapterIndex)
                return
            }
        }
        drainBackgroundCompletion()
        endLeaseIfIdle()
    }

    private func startPreparation(_ id: String) {
        guard let index = indexOf(id), let engine else { return }
        jobs[index].phase = .preparing
        jobs[index].message = "Preparazione elenco pagine"
        guard persist(), let request = encoded(jobs[index].request) else { return }
        work = (id, nil)
        let checkpoint = PlanningCheckpoint { [weak self] json in self?.planningCheckpointed(id, json: json) }
        engine.prepare(jobId: id, request: request, previous: jobs[index].planningCheckpoint, checkpoint: checkpoint,
                       result: DownloadCallback { [weak self] value, error in
            guard let self else { return }
            self.work = nil
            if self.resumeInterruptedWork(id) { self.pump(); return }
            guard let index = self.indexOf(id), self.jobs[index].phase.isActive else { self.removeStaging(id); self.pump(); return }
            if self.jobs[index].phase == .paused { self.pump(); return }
            do {
                guard let value else { throw NSError(domain: "Download", code: 1, userInfo: [NSLocalizedDescriptionKey: error ?? "Preparazione non riuscita"]) }
                let manifest = try JSONDecoder().decode(NativeDownloadManifest.self, from: Data(value.utf8))
                guard validManifest(manifest) else { throw DownloadCoreError.invalidSnapshot }
                self.jobs[index].manifest = manifest
                self.jobs[index].planningCheckpoint = nil
                self.jobs[index].finishedChapters = Set(manifest.chapters.indices.filter { manifest.chapters[$0].alreadyPresent })
                self.jobs[index].phase = .transferring
                self.jobs[index].message = "Download pagine"
                if self.persist() {
                    self.transfers.schedule(self.jobs[index].pendingTransfers)
                    self.updateContinuedProgress(id)
                }
            } catch { self.fail(id, message: error.localizedDescription) }
            self.pump()
        })
    }

    /** Salva il checkpoint: se iOS chiude l'app, la preparazione riparte dal capitolo successivo. */
    private func planningCheckpointed(_ id: String, json: String) {
        guard let index = indexOf(id), jobs[index].phase == .preparing,
              let progress = try? JSONDecoder().decode(PlanningProgress.self, from: Data(json.utf8)) else { return }
        jobs[index].planningCheckpoint = json
        jobs[index].message = "Preparazione elenco pagine: \(progress.chapters.filter { $0.prepared ?? true }.count)/\(progress.chapters.count) capitoli"
        persist()
    }

    private func startFinalization(_ id: String, chapter: Int) {
        guard let index = indexOf(id), let manifest = jobs[index].manifest, let engine, let json = encoded(manifest) else { return }
        jobs[index].phase = .finalizing
        jobs[index].message = "\(manifest.chapters[chapter].label): preparazione CBZ"
        guard persist() else { return }
        work = (id, chapter)
        engine.finalizeChapter(jobId: id, manifest: json, index: Int32(chapter), stagingDirectory: store.root.appendingPathComponent("\(id)/\(chapter)").path,
            result: DownloadCallback { [weak self] value, error in
                guard let self else { return }
                self.work = nil
                if self.resumeInterruptedWork(id) { self.pump(); return }
                guard let index = self.indexOf(id), self.jobs[index].phase.isActive else { self.removeStaging(id); self.pump(); return }
                if self.jobs[index].phase == .paused { self.pump(); return }
                if value != nil {
                    self.jobs[index].finishedChapters.insert(chapter)
                    self.jobs[index].phase = .transferring
                    self.jobs[index].message = "\(manifest.chapters[chapter].label) completato"
                    // Prima checkpoint, poi cleanup: se il processo termina tra i due, il CBZ resta valido.
                    if self.persist() {
                        try? FileManager.default.removeItem(at: self.store.root.appendingPathComponent("\(id)/\(chapter)"))
                        self.updateContinuedProgress(id)
                    }
                } else { self.fail(id, message: error ?? "Creazione CBZ non riuscita") }
                self.pump()
            })
    }

    private func pageFinished(key: String, result: Result<Int64, Error>) {
        let id = key.components(separatedBy: "/").first ?? ""
        guard let index = indexOf(id), jobs[index].phase.isActive else { return }
        partialProgress.removeValue(forKey: key)
        switch result {
        case .success:
            jobs[index].completedPages.insert(key)
            jobs[index].message = "Pagine scaricate: \(jobs[index].completedPages.count)/\(jobs[index].transfers.count)"
            if persist() { updateContinuedProgress(id); pump() }
        case .failure(let error): fail(id, message: error.localizedDescription); pump()
        }
    }

    private func pageProgress(key: String, written: Int64, expected: Int64) {
        let id = key.components(separatedBy: "/").first ?? ""
        guard let index = indexOf(id), jobs[index].phase.isActive else { return }
        partialProgress[key] = (written, expected)
        guard Date().timeIntervalSince(lastProgressUpdate) > 0.75 else { return }
        lastProgressUpdate = Date()
        jobs[index].message = "Download pagine: \(jobs[index].completedPages.count)/\(jobs[index].transfers.count) · \(written / 1024) KB ricevuti"
        if persist() { updateContinuedProgress(id) }
    }

    private func complete(_ id: String) {
        guard let index = indexOf(id) else { return }
        jobs[index].phase = .succeeded
        jobs[index].message = "Download completato: \(jobs[index].finishedChapters.count) capitoli"
        if persist() { finishContinued(id, success: true); removeStaging(id) }
    }

    private func fail(_ id: String, message: String) {
        guard let index = indexOf(id), jobs[index].phase.isActive else { return }
        jobs[index].phase = .failed
        jobs[index].message = message
        jobs[index].planningCheckpoint = nil
        transfers.cancel(jobId: id)
        engine?.cancel(jobId: id)
        finishContinued(id, success: false)
        if work?.id != id { removeStaging(id) }
        _ = persist()
    }

    private func mayUseCPU(_ id: String) -> Bool {
        if foreground || hasContinuedRuntime(id) { return true }
        ensureLease()
        return !backgroundTimeExpired && lease != .invalid
    }

    private func ensureLease() {
        guard !foreground, lease == .invalid, !backgroundTimeExpired, jobs.contains(where: { $0.phase.isActive }) else { return }
        lease = UIApplication.shared.beginBackgroundTask(withName: "Finalizzazione download manga") { [weak self] in
            guard let self else { return }
            self.backgroundTimeExpired = true
            if let work = self.work, !self.hasContinuedRuntime(work.id), let index = self.indexOf(work.id), self.jobs[index].phase.isActive {
                self.jobs[index].phase = .paused
                self.pausedWork.insert(work.id)
                self.jobs[index].message = "Pagine salvate; riprende alla riapertura dell'app"
                self.engine?.cancel(jobId: work.id)
                _ = self.persist()
            }
            self.endLease()
            self.drainBackgroundCompletion(force: true)
        }
    }

    private func endLeaseIfIdle() {
        if work == nil && backgroundCompletion == nil { endLease() }
    }
    private func endLease() {
        if lease != .invalid { let current = lease; lease = .invalid; UIApplication.shared.endBackgroundTask(current) }
    }
    private func drainBackgroundCompletion(force: Bool = false) {
        guard let completion = backgroundCompletion, force || (eventsFinished && work == nil) else { return }
        backgroundCompletion = nil
        eventsFinished = false
        completion()
        endLeaseIfIdle()
    }

    private func indexOf(_ id: String) -> Int? { jobs.firstIndex { $0.id == id } }
    private func resumeInterruptedWork(_ id: String) -> Bool {
        guard pausedWork.remove(id) != nil else { return false }
        if let index = indexOf(id), jobs[index].phase.isActive {
            jobs[index].phase = foreground ? (jobs[index].manifest == nil ? .queued : .transferring) : .paused
            _ = persist()
        } else { removeStaging(id) }
        return true
    }
    private func removeStaging(_ id: String) {
        partialProgress = partialProgress.filter { !$0.key.hasPrefix(id + "/") }
        try? FileManager.default.removeItem(at: store.root.appendingPathComponent(id))
    }
    private func hasContinuedRuntime(_ id: String) -> Bool {
        if #available(iOS 26.0, *) { return (continued as? ContinuedDownloadExecution)?.hasRuntime(id: id) == true }
        return false
    }
    private func finishContinued(_ id: String, success: Bool) {
        if #available(iOS 26.0, *) { (continued as? ContinuedDownloadExecution)?.finish(id: id, success: success) }
    }
    private func updateContinuedProgress(_ id: String) {
        guard let index = indexOf(id) else { return }
        let job = jobs[index]
        if !job.phase.isActive { finishContinued(id, success: job.phase == .succeeded); return }
        if #available(iOS 26.0, *) {
            let partial = job.pendingTransfers.reduce(Int64(0)) { sum, transfer in
                guard !job.completedPages.contains(transfer.key), let bytes = partialProgress[transfer.key], bytes.expected > 0 else { return sum }
                return sum + min(999, bytes.written * 1000 / bytes.expected)
            }
            (continued as? ContinuedDownloadExecution)?.update(id: id, completed: Int64(job.completedUnits) * 1000 + partial,
                total: Int64(job.totalUnits) * 1000, title: job.manifest?.seriesTitle ?? job.request.seriesTitle ?? "Download manga", message: job.message)
        }
    }
    @discardableResult private func persist() -> Bool {
        guard persistenceError == nil else { publish(); return false }
        do { try store.save(jobs); publish(); return true }
        catch {
            persistenceError = error.localizedDescription
            ready = false
            for index in jobs.indices where jobs[index].phase.isActive {
                jobs[index].phase = .failed
                jobs[index].message = "Impossibile salvare la coda: \(error.localizedDescription)"
                transfers.cancel(jobId: jobs[index].id)
                engine?.cancel(jobId: jobs[index].id)
                finishContinued(jobs[index].id, success: false)
            }
            publish()
            return false
        }
    }
    private func publish() { if let snapshot = encoded(jobs) { observer?.update(snapshot: snapshot) } }
    private func encoded<T: Encodable>(_ value: T) -> String? {
        guard let data = try? JSONEncoder().encode(value) else { return nil }
        return String(data: data, encoding: .utf8)
    }
}

private struct PlanningProgress: Decodable {
    struct Chapter: Decodable { var prepared: Bool? }
    var chapters: [Chapter]
}

private final class PlanningCheckpoint: NSObject, IosDownloadObserver {
    private let callback: (String) -> Void
    init(_ callback: @escaping (String) -> Void) { self.callback = callback }
    func update(snapshot: String) { callback(snapshot) }
}

private final class DownloadCallback: NSObject, IosDownloadResult {
    private let callback: (String?, String?) -> Void
    init(_ callback: @escaping (String?, String?) -> Void) { self.callback = callback }
    func complete(value: String?, error: String?) { callback(value, error) }
}
