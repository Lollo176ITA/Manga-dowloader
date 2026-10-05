import XCTest
@testable import DownloadCore

final class DownloadRecoveryTests: XCTestCase {
    private func job() -> NativeDownloadJob {
        NativeDownloadJob(id: "job-1", request: NativeDownloadRequest(firstUrl: "https://test/c/1"), phase: .transferring,
                          manifest: NativeDownloadManifest(sourceId: "mangapill", seriesTitle: "Serie", mangaUrl: "https://test/manga", directory: "Serie",
                            chapters: [NativeDownloadChapter(fileName: "chapter_001.cbz", label: "Capitolo 1", url: "https://test/c/1", pages: [NativeDownloadPage(url: "https://test/1.jpg", name: "001.jpg"), NativeDownloadPage(url: "https://test/2.jpg", name: "002.jpg")], alreadyPresent: false)]))
    }

    func testReloadKeepsRequestAndProgressWithoutPersistingSandboxPaths() throws {
        let firstRoot = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let secondRoot = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: firstRoot); try? FileManager.default.removeItem(at: secondRoot) }
        let store = DownloadQueueStore(root: firstRoot)
        var record = job()
        record.completedPages = ["job-1/0/0"]
        record.message = "pagina 1/2"
        try store.save([record])
        try FileManager.default.moveItem(at: firstRoot, to: secondRoot)
        let reloaded = try DownloadQueueStore(root: secondRoot).load()
        XCTAssertEqual(reloaded, [record])
        XCTAssertEqual(reloaded[0].transfers[1].relativePath, "job-1/0/002.jpg")
        XCTAssertEqual(reloaded[0].completedUnits, 1)
        XCTAssertEqual(reloaded[0].totalUnits, 3)
        XCTAssertFalse(String(data: try Data(contentsOf: secondRoot.appendingPathComponent("queue.json")), encoding: .utf8)!.contains(firstRoot.path))
    }

    func testRecoveryReusesLiveTaskAndFileAndCancelsDuplicates() {
        let recovered = DownloadRecovery.reconcile(jobs: [job()], tasks: [1: "job-1/0/0", 2: "job-1/0/0", 3: "unknown"], files: ["job-1/0/1"])
        XCTAssertEqual(recovered.keptTaskIds, [1])
        XCTAssertEqual(recovered.cancelledTaskIds, [2, 3])
        XCTAssertTrue(recovered.toSchedule.isEmpty)
    }

    func testRecoveryReschedulesMissingTasksButNeverCancelledJobs() {
        var cancelled = job()
        cancelled.id = "cancelled"
        cancelled.phase = .cancelled
        let recovered = DownloadRecovery.reconcile(jobs: [job(), cancelled], tasks: [9: "cancelled/0/0"], files: [])
        XCTAssertEqual(recovered.toSchedule.map(\.key).sorted(), ["job-1/0/0", "job-1/0/1"])
        XCTAssertEqual(recovered.cancelledTaskIds, [9])
    }

    func testFinalizedChapterDoesNotRedownloadItsRemovedStagingFiles() {
        var record = job()
        record.finishedChapters = [0]
        let recovered = DownloadRecovery.reconcile(jobs: [record], tasks: [:], files: [])
        XCTAssertTrue(recovered.toSchedule.isEmpty)
        XCTAssertEqual(record.completedUnits, 3)
    }

    func testPlanningCheckpointSurvivesReloadAndOlderSnapshotsStillLoad() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        var record = NativeDownloadJob(id: "job-2", request: NativeDownloadRequest(firstUrl: "https://test/c/1"), phase: .preparing)
        record.planningCheckpoint = #"{"directory":"Serie","chapters":[{"prepared":true},{"prepared":false}]}"#
        try DownloadQueueStore(root: root).save([job(), record])
        XCTAssertEqual(try DownloadQueueStore(root: root).load()[1].planningCheckpoint, record.planningCheckpoint)

        // Una coda salvata prima del checkpoint non ha la chiave: deve caricarsi uguale.
        let legacy = #"{"version":1,"jobs":[{"id":"old","request":{"firstUrl":"https://test/c/1","userInitiated":true},"phase":"queued","finishedChapters":[],"completedPages":[],"message":"In coda"}]}"#
        try Data(legacy.utf8).write(to: root.appendingPathComponent("queue.json"))
        let loaded = try DownloadQueueStore(root: root).load()
        XCTAssertEqual(loaded.map(\.id), ["old"])
        XCTAssertNil(loaded[0].planningCheckpoint)
    }

    func testCorruptSnapshotIsRejectedAndPreserved() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let path = root.appendingPathComponent("queue.json")
        let corrupt = Data("not json".utf8)
        try corrupt.write(to: path)
        XCTAssertThrowsError(try DownloadQueueStore(root: root).load())
        XCTAssertEqual(try Data(contentsOf: path), corrupt)
    }
}
