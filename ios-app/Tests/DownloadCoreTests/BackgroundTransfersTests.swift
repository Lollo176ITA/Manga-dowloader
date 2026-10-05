import XCTest
@testable import DownloadCore

final class BackgroundTransfersTests: XCTestCase {
    private var server: Process!
    private var root: URL!
    private var baseURL: String!

    override func setUpWithError() throws {
        root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        server = Process()
        server.executableURL = URL(fileURLWithPath: "/usr/bin/python3")
        server.arguments = ["-u", "-c", """
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import time
class Handler(BaseHTTPRequestHandler):
 def do_GET(self):
  if self.path == '/missing':
   self.send_error(404); return
  if self.headers.get('Referer') != 'https://test/chapter':
   self.send_error(403); return
  body = b'page-bytes' if self.path != '/slow' else b'x'*32768
  self.send_response(200)
  self.send_header('Content-Type', 'image/jpeg')
  self.send_header('Content-Length', str(len(body)))
  self.end_headers()
  if self.path == '/slow':
   self.wfile.write(body[:1024]); self.wfile.flush(); time.sleep(2)
   body = body[1024:]
  try: self.wfile.write(body)
  except BrokenPipeError: pass
 def log_message(self, *args): pass
server=ThreadingHTTPServer(('127.0.0.1',0),Handler)
print(server.server_address[1],flush=True)
server.serve_forever()
"""]
        let pipe = Pipe()
        server.standardOutput = pipe
        server.standardError = Pipe()
        try server.run()
        let port = String(data: pipe.fileHandleForReading.availableData, encoding: .utf8)!.trimmingCharacters(in: .whitespacesAndNewlines)
        baseURL = "http://127.0.0.1:\(port)"
    }
    override func tearDownWithError() throws {
        if server?.isRunning == true { server.terminate(); server.waitUntilExit() }
        try? FileManager.default.removeItem(at: root)
    }
    private func transfer(_ path: String) -> NativeTransfer {
        NativeTransfer(key: "job/0/0", jobId: "job", chapterIndex: 0, url: baseURL + path, referer: "https://test/chapter", relativePath: "job/0/001.jpg")
    }

    func testSuccessfulHttpPageIsMovedBeforeCompletionAndRetainsReferer() throws {
        let done = expectation(description: "download")
        let transport = BackgroundTransfers(root: root, configuration: .ephemeral)
        transport.onFinished = { key, result in
            XCTAssertEqual(key, "job/0/0")
            XCTAssertEqual(try? result.get(), 10)
            XCTAssertEqual(try? Data(contentsOf: self.root.appendingPathComponent("job/0/001.jpg")), Data("page-bytes".utf8))
            done.fulfill()
        }
        transport.schedule([transfer("/image")])
        wait(for: [done], timeout: 10)
        transport.invalidate()
    }

    func testHttpFailureDoesNotCreateACompletedPage() {
        let done = expectation(description: "404")
        let transport = BackgroundTransfers(root: root, configuration: .ephemeral)
        transport.onFinished = { _, result in
            if case .success = result { XCTFail("HTTP 404 must fail") }
            XCTAssertFalse(FileManager.default.fileExists(atPath: self.root.appendingPathComponent("job/0/001.jpg").path))
            done.fulfill()
        }
        transport.schedule([transfer("/missing")])
        wait(for: [done], timeout: 10)
        transport.invalidate()
    }

    private func pages(_ count: Int, job: String = "job") -> [NativeTransfer] {
        (0..<count).map { NativeTransfer(key: "\(job)/0/\($0)", jobId: job, chapterIndex: 0, url: baseURL + "/image", referer: "https://test/chapter", relativePath: "\(job)/0/\($0).jpg") }
    }

    func testTransfersBeyondOneBatchAreAllScheduled() {
        let done = expectation(description: "all pages")
        done.expectedFulfillmentCount = 20
        let transport = BackgroundTransfers(root: root, configuration: .ephemeral)
        transport.onFinished = { _, result in
            XCTAssertEqual(try? result.get(), 10)
            done.fulfill()
        }
        transport.schedule(pages(20))
        wait(for: [done], timeout: 20)
        transport.invalidate()
    }

    func testCancelDropsTransfersStillWaitingForTheirBatch() {
        let kept = expectation(description: "other job completes")
        kept.expectedFulfillmentCount = 3
        let transport = BackgroundTransfers(root: root, configuration: .ephemeral)
        var finished: [String] = []
        transport.onFinished = { key, _ in
            finished.append(key)
            if key.hasPrefix("other/") { kept.fulfill() }
        }
        // Il primo blocco parte subito; il resto di "job" è ancora in coda quando arriva l'annullamento.
        transport.schedule(pages(20) + pages(3, job: "other"))
        transport.cancel(jobId: "job")
        wait(for: [kept], timeout: 10)
        XCTAssertTrue(finished.allSatisfy { $0.hasPrefix("other/") })
        transport.invalidate()
    }

    func testCancelledTransferCannotPublishALateCompletedFile() {
        let progressed = expectation(description: "began transfer")
        let noCompletion = expectation(description: "cancelled transfer stays cancelled")
        noCompletion.isInverted = true
        let transport = BackgroundTransfers(root: root, configuration: .ephemeral)
        var cancelled = false
        transport.onProgress = { _, bytes, _ in
            if bytes > 0 && !cancelled {
                cancelled = true
                transport.cancel(jobId: "job")
                progressed.fulfill()
            }
        }
        transport.onFinished = { _, _ in noCompletion.fulfill() }
        transport.schedule([transfer("/slow")])
        wait(for: [progressed], timeout: 10)
        wait(for: [noCompletion], timeout: 2.5)
        XCTAssertFalse(FileManager.default.fileExists(atPath: root.appendingPathComponent("job/0/001.jpg").path))
        transport.invalidate()
    }
}
