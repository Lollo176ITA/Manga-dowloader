import XCTest

final class SettingsUITests: XCTestCase {
    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication(bundleIdentifier: "com.lorenzo.mangapp")
        app.launch()
        openSettings()
    }

    override func tearDownWithError() throws {
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.lifetime = .keepAlways
        add(screenshot)
        app.terminate()
    }

    func testAppearanceOptionsAndNoUnsupportedDynamicColors() {
        XCTAssertFalse(element("Colori dinamici").exists)
        exerciseChoices(["Auto", "Chiaro", "Scuro"])
        exerciseChoices(["Compatta", "Normale", "Grande"])
        exerciseChoices(["Verticale", "Pagine", "Manga"])
        exerciseChoices(["Adatta", "Dividi", "Ruota"])
        exerciseChoices(["Zero", "Poco", "Medio", "Tanto"])
    }

    func testReaderTogglesAndPersistence() {
        exerciseToggle("Doppio tap per zoomare")
        let label = "Mantieni schermo acceso"
        let original = state(reach(label))
        tapControl(label)
        XCTAssertTrue(wait { self.state(self.element(label)) != original })
        app.terminate()
        app.launch()
        openSettings()
        XCTAssertNotEqual(state(reach(label)), original, "La preferenza deve sopravvivere al riavvio")
        tapControl(label)
        XCTAssertTrue(wait { self.state(self.element(label)) == original })
        exerciseToggle("Mostra la tab Home")
    }

    func testDownloadAndCleanupOptions() {
        exerciseExpandableToggle("Auto-download", child: "Capitoli rimanenti prima di scaricare")
        exerciseExpandableToggle("Pulizia intelligente", child: "Capitoli precedenti da mantenere")
        tapControl("Gestisci memoria")
        XCTAssertTrue(wait {
            self.element("Spazio occupato").exists || self.element("Nessun manga in memoria").exists
        }, app.debugDescription)
    }

    func testNotificationDenialAndParentalSetupCancellation() {
        let notifications = "Notifiche preferiti"
        reach(notifications)
        if !isOn(element(notifications)) {
            tapControl(notifications)
            let deny = app.buttons["Non consentire"]
            if deny.waitForExistence(timeout: 2) { deny.tap() }
            XCTAssertTrue(element("Le notifiche sono bloccate per l'app").waitForExistence(timeout: 5))
            XCTAssertFalse(isOn(element(notifications)))
        }
        let parental = "Controllo genitori"
        reach(parental)
        if !isOn(element(parental)) {
            tapControl(parental)
            XCTAssertTrue(element("Configura parental control").waitForExistence(timeout: 5))
            app.buttons["Annulla"].tap()
            XCTAssertFalse(isOn(element(parental)))
        }
    }

    func testSourceAndAdultFilterToggles() {
        exerciseToggle("Nascondi manga per adulti")
        exerciseToggle("Hasta Team")
        exerciseToggle("MangaWorld")
        exerciseToggle("Weeb Central")
    }

    func testBackupPickerCancellationDoesNotReportSuccess() {
        tapControl("Backup e ripristino")
        tapControl("Esporta backup")
        // File può riaprire l'ultima cartella: lì la barra mostra Salva/Altro.
        // Il comando Annulla è disponibile tornando alla schermata Sfoglia.
        let browse = app.buttons["Sfoglia"]
        if browse.waitForExistence(timeout: 5) { browse.tap() }
        let cancel = app.buttons.matching(NSPredicate(format: "label IN %@", ["Annulla", "Cancel"])).firstMatch
        XCTAssertTrue(cancel.waitForExistence(timeout: 10), app.debugDescription)
        cancel.tap()
        XCTAssertTrue(wait { !cancel.exists })
        XCTAssertTrue(element("Esporta backup").isHittable)
        XCTAssertFalse(element("Backup esportato").exists)
        tapControl("Importa e unisci")
        XCTAssertTrue(cancel.waitForExistence(timeout: 10))
        cancel.tap()
        XCTAssertTrue(wait { !cancel.exists })
        XCTAssertTrue(element("Importa e unisci").isHittable)
    }

    func testLabsBrightnessAndLandscape() {
        let labs = "Abilita funzioni sperimentali"
        let original = state(reach(labs))
        if !isOn(element(labs)) { tapControl(labs) }
        exerciseToggle("Luminosità lettore")
        let rotation = "Rotazione schermo"
        let rotationOriginal = state(reach(rotation))
        if !isOn(element(rotation)) { tapControl(rotation) }
        XCUIDevice.shared.orientation = .landscapeLeft
        XCTAssertTrue(wait { self.app.frame.width > self.app.frame.height })
        XCUIDevice.shared.orientation = .portrait
        XCTAssertTrue(wait { self.app.frame.height > self.app.frame.width })
        let currentRotation = reach(rotation)
        if state(currentRotation) != rotationOriginal { tapControl(rotation) }
        if state(reach(labs)) != original { tapControl(labs) }
    }

    private func openSettings() {
        let settings = element("Impostazioni")
        XCTAssertTrue(settings.waitForExistence(timeout: 15), app.debugDescription)
        settings.tap()
        XCTAssertTrue(element("Tema").waitForExistence(timeout: 5))
    }

    private func element(_ label: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label == %@ OR label BEGINSWITH %@", label, label + ", ")).firstMatch
    }

    @discardableResult
    private func reach(_ label: String, file: StaticString = #filePath, line: UInt = #line) -> XCUIElement {
        for _ in 0..<18 {
            let item = element(label)
            let top = app.buttons["Indietro"].frame.maxY + 8
            if item.exists && item.isHittable && item.frame.minY > top && item.frame.maxY < app.frame.height - 45 {
                var previousFrame = item.frame
                var quietSince = Date()
                XCTAssertTrue(wait {
                    let currentFrame = self.element(label).frame
                    if abs(currentFrame.minY - previousFrame.minY) > 1 || abs(currentFrame.height - previousFrame.height) > 1 {
                        previousFrame = currentFrame
                        quietSince = Date()
                    }
                    return Date().timeIntervalSince(quietSince) > 0.6
                }, "Lo scorrimento deve fermarsi prima del tap")
                return element(label)
            }
            let moveDown = item.exists && item.frame.minY <= top
            let start = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: moveDown ? 0.32 : 0.78))
            let end = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: moveDown ? 0.78 : 0.32))
            start.press(forDuration: 0.05, thenDragTo: end, withVelocity: .slow, thenHoldForDuration: 0.2)
        }
        XCTFail("Controllo non raggiungibile: \(label)\n\(app.debugDescription)", file: file, line: line)
        return element(label)
    }

    private func exerciseChoices(_ labels: [String]) {
        reach(labels[0])
        let original = labels.first { element($0).isSelected }
        for label in labels {
            tapControl(label)
            XCTAssertTrue(wait { self.element(label).isSelected }, label)
        }
        if let original { element(original).tap() }
    }

    private func exerciseToggle(_ label: String) {
        let original = state(reach(label))
        tapControl(label)
        XCTAssertTrue(wait { self.state(self.element(label)) != original }, label)
        tapControl(label)
        XCTAssertTrue(wait { self.state(self.element(label)) == original }, label)
    }

    private func exerciseExpandableToggle(_ label: String, child: String) {
        let original = state(reach(label))
        if !isOn(element(label)) { tapControl(label) }
        XCTAssertTrue(element(child).waitForExistence(timeout: 5), child)
        if state(element(label)) != original { tapControl(label) }
    }

    private func tapControl(_ label: String) {
        reach(label).coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
    }

    private func isOn(_ item: XCUIElement) -> Bool {
        item.isSelected || (item.value as? String) == "1"
    }

    private func state(_ item: XCUIElement) -> String {
        "\(item.isSelected):\(item.value ?? "")"
    }

    private func wait(_ predicate: @escaping () -> Bool) -> Bool {
        XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in predicate() }, object: nil)], timeout: 5) == .completed
    }
}
