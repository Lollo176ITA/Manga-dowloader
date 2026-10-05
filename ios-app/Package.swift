// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "MangAppDownloadCore",
    platforms: [.macOS(.v13), .iOS(.v15)],
    products: [.library(name: "DownloadCore", targets: ["DownloadCore"])],
    targets: [
        .target(name: "DownloadCore", path: "MangApp", exclude: ["AppDelegate.swift", "NativePlatformServices.swift", "NativeDownloadManager.swift", "ContinuedDownloadExecution.swift", "Info.plist"], sources: ["DownloadQueueState.swift", "DownloadQueueStore.swift", "BackgroundTransfers.swift"]),
        .testTarget(name: "DownloadCoreTests", dependencies: ["DownloadCore"], path: "Tests/DownloadCoreTests")
    ]
)
