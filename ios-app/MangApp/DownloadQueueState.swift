import Foundation

struct NativeDownloadRequest: Codable, Equatable {
    var firstUrl: String
    var lastUrl: String? = nil
    var sourceId: String? = nil
    var seriesTitle: String? = nil
    var mangaUrl: String? = nil
    var coverUrl: String? = nil
    var userInitiated: Bool = true
}

struct NativeDownloadPage: Codable, Equatable { var url: String; var name: String }
struct NativeDownloadChapter: Codable, Equatable {
    var fileName: String
    var label: String
    var url: String
    var pages: [NativeDownloadPage]
    var alreadyPresent: Bool
}
struct NativeDownloadManifest: Codable, Equatable {
    var sourceId: String
    var seriesTitle: String
    var mangaUrl: String
    var directory: String
    var chapters: [NativeDownloadChapter]
}

enum NativeDownloadPhase: String, Codable {
    case queued, preparing, transferring, finalizing, paused, succeeded, failed, cancelled
    var isActive: Bool { ![.succeeded, .failed, .cancelled].contains(self) }
}

struct NativeDownloadJob: Codable, Equatable {
    var id: String
    var request: NativeDownloadRequest
    var phase: NativeDownloadPhase = .queued
    var manifest: NativeDownloadManifest? = nil
    /** Manifest parziale prodotto da Kotlin durante la preparazione; opaco per Swift. */
    var planningCheckpoint: String? = nil
    var finishedChapters: Set<Int> = []
    var completedPages: Set<String> = []
    var message: String = "In coda"

    var transfers: [NativeTransfer] {
        guard let manifest else { return [] }
        return manifest.chapters.enumerated().flatMap { chapterIndex, chapter in
            chapter.pages.enumerated().map { pageIndex, page in
                NativeTransfer(key: "\(id)/\(chapterIndex)/\(pageIndex)", jobId: id, chapterIndex: chapterIndex,
                               url: page.url, referer: chapter.url, relativePath: "\(id)/\(chapterIndex)/\(page.name)")
            }
        }
    }
    var pendingTransfers: [NativeTransfer] { transfers.filter { !finishedChapters.contains($0.chapterIndex) } }
    var totalUnits: Int { max(1, (manifest?.chapters.count ?? 0) + transfers.count) }
    var completedUnits: Int {
        finishedChapters.count + transfers.filter { finishedChapters.contains($0.chapterIndex) || completedPages.contains($0.key) }.count
    }
    var allowsContinuedProcessingRequest: Bool { request.userInitiated && phase.isActive }
}

struct NativeTransfer: Equatable {
    var key: String
    var jobId: String
    var chapterIndex: Int
    var url: String
    var referer: String
    var relativePath: String
}

struct TransferRecoveryPlan {
    var keptTaskIds: Set<Int>
    var cancelledTaskIds: Set<Int>
    var toSchedule: [NativeTransfer]
}

enum DownloadRecovery {
    static func reconcile(jobs: [NativeDownloadJob], tasks: [Int: String], files: Set<String>) -> TransferRecoveryPlan {
        let expected = jobs.filter { $0.phase.isActive }.flatMap(\.pendingTransfers)
        let byKey = Dictionary(expected.map { ($0.key, $0) }, uniquingKeysWith: { first, _ in first })
        var kept: Set<Int> = [], cancelled: Set<Int> = [], liveKeys: Set<String> = []
        for (taskId, key) in tasks.sorted(by: { $0.key < $1.key }) {
            if byKey[key] != nil && !files.contains(key) && liveKeys.insert(key).inserted { kept.insert(taskId) }
            else { cancelled.insert(taskId) }
        }
        return TransferRecoveryPlan(keptTaskIds: kept, cancelledTaskIds: cancelled,
                                    toSchedule: expected.filter { !files.contains($0.key) && !liveKeys.contains($0.key) })
    }
}
