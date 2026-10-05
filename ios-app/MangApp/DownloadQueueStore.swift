import Foundation

final class DownloadQueueStore {
    let root: URL
    private var snapshot: URL { root.appendingPathComponent("queue.json") }
    private struct Snapshot: Codable { var version: Int; var jobs: [NativeDownloadJob] }

    init(root: URL) { self.root = root }

    func load() throws -> [NativeDownloadJob] {
        guard FileManager.default.fileExists(atPath: snapshot.path) else { return [] }
        let saved = try JSONDecoder().decode(Snapshot.self, from: Data(contentsOf: snapshot))
        guard saved.version == 1 else { throw DownloadCoreError.invalidSnapshot }
        let ids = saved.jobs.map(\.id)
        guard Set(ids).count == ids.count, saved.jobs.allSatisfy({ isSafeComponent($0.id) && validManifest($0.manifest) }) else {
            throw DownloadCoreError.invalidSnapshot
        }
        return saved.jobs
    }

    func save(_ jobs: [NativeDownloadJob]) throws {
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        try JSONEncoder().encode(Snapshot(version: 1, jobs: jobs)).write(to: snapshot, options: .atomic)
    }
}

enum DownloadCoreError: LocalizedError {
    case invalidSnapshot, invalidTransfer, emptyFile, http(Int), htmlResponse
    var errorDescription: String? {
        switch self {
        case .invalidSnapshot: return "Coda download non leggibile. I dati originali sono conservati."
        case .invalidTransfer: return "Indirizzo o percorso del download non valido."
        case .emptyFile: return "La fonte ha restituito una pagina vuota."
        case .http(let code): return "Errore HTTP \(code) scaricando una pagina."
        case .htmlResponse: return "La fonte ha restituito una pagina web al posto dell'immagine."
        }
    }
}

func isSafeComponent(_ value: String) -> Bool {
    !value.isEmpty && value != "." && value != ".." && !value.contains("/") && !value.contains("\\") && !value.contains(":")
}
func validRelativePath(_ value: String) -> Bool { value.split(separator: "/", omittingEmptySubsequences: false).allSatisfy { isSafeComponent(String($0)) } }
func validManifest(_ manifest: NativeDownloadManifest?) -> Bool {
    guard let manifest else { return true }
    return validRelativePath(manifest.directory) && !manifest.chapters.isEmpty && manifest.chapters.allSatisfy { chapter in
        isSafeComponent(chapter.fileName) && (chapter.alreadyPresent || !chapter.pages.isEmpty) && chapter.pages.allSatisfy { isSafeComponent($0.name) }
    }
}
