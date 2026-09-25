package com.lorenzo.mangadownloader.perftest

import android.os.SystemClock
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until

private const val UI_TIMEOUT_MS = 15_000L

/** Porta l'app in uno stato noto prima di ogni iterazione. */
object AppSetup {
    /**
     * Dati puliti + permesso notifiche (senza, l'app apre un dialog) + serie finte.
     * Le serie si copiano DOPO un primo avvio: se è la shell a creare la cartella esterna
     * dell'app (cancellata da `pm clear`), la cartella resta sua e l'app non vede i file.
     */
    fun resetApp(device: UiDevice) {
        val pkg = BenchData.TARGET_PACKAGE
        device.executeShellCommand("pm clear $pkg")
        device.executeShellCommand("pm grant $pkg android.permission.POST_NOTIFICATIONS")
        device.executeShellCommand("am start -W -n $pkg/com.lorenzo.mangadownloader.MainActivity")
        val deadline = SystemClock.uptimeMillis() + UI_TIMEOUT_MS
        while (!BenchData.libraryDirExists(device)) {
            check(SystemClock.uptimeMillis() < deadline) { "L'app non ha creato la cartella della libreria" }
            SystemClock.sleep(250)
        }
        device.executeShellCommand("am force-stop $pkg")
        BenchData.install(device)
    }
}

fun MacrobenchmarkScope.waitForText(text: String, timeoutMs: Long = UI_TIMEOUT_MS) {
    check(device.wait(Until.hasObject(By.text(text)), timeoutMs)) {
        // I testi a schermo nel messaggio: dicono subito se è cambiata un'etichetta o la schermata.
        val visible = device.findObjects(By.textContains("")).mapNotNull { it.text }.filter(String::isNotBlank)
        "Elemento \"$text\" non trovato entro ${timeoutMs}ms. A schermo: $visible"
    }
}

/** Avvia l'app, aspetta la bottom bar e chiude i popup del primo avvio. */
fun MacrobenchmarkScope.launchToHome() {
    startActivityAndWait()
    waitForText("Home")
    dismissPopups()
}

/**
 * Chiude i popup noti del primo avvio: aggiornamento disponibile ("Più tardi"), card del
 * tutorial e spiegazione notifiche ("Non ora"). Più giri: possono comparire in sequenza.
 */
fun MacrobenchmarkScope.dismissPopups() {
    repeat(3) {
        val button = device.findObject(By.text("Più tardi"))
            ?: device.findObject(By.text("Non ora"))
            ?: return
        button.click()
        device.waitForIdle()
    }
}

/**
 * Tocca [target] finché non compare [expected] (max 3 tentativi): su un emulatore lento il primo
 * tocco può arrivare mentre la lista si sta ancora ricomponendo e andare perso.
 */
fun MacrobenchmarkScope.clickUntilText(target: String, expected: String) {
    waitForText(target)
    repeat(3) {
        device.findObject(By.text(target))?.click()
        if (device.wait(Until.hasObject(By.text(expected)), 5_000)) return
    }
    waitForText(expected)
}

/**
 * Al primo uso su un emulatore appena ripulito Gboard apre il tutorial "Try out your stylus",
 * che si prende la digitazione: va chiuso.
 */
fun MacrobenchmarkScope.dismissKeyboardOnboarding(): Boolean {
    if (!device.wait(Until.hasObject(By.text("Try out your stylus")), 1_500)) return false
    device.findObject(By.text("Cancel"))?.click()
    device.waitForIdle()
    return true
}

/** Tocca una voce della bottom bar. Lo stesso testo può essere anche il titolo in alto:
 * la voce della barra è quella più in basso. */
fun MacrobenchmarkScope.openTab(label: String) {
    waitForText(label)
    device.findObjects(By.text(label)).maxBy { it.visibleBounds.centerY() }.click()
    device.waitForIdle()
}

/**
 * Swipe verticali al centro dello schermo (contenuto verso l'alto: niente pull-to-refresh).
 * Via `input swipe` della shell: `UiDevice.swipe` inietta i passi uno alla volta aspettando
 * l'app, e sull'emulatore sotto tracing costava ~1,7 s a gesto contro ~0,3 s.
 */
fun MacrobenchmarkScope.swipeUp(times: Int) {
    val x = device.displayWidth / 2
    val from = device.displayHeight * 3 / 4
    val to = device.displayHeight / 4
    repeat(times) {
        device.executeShellCommand("input swipe $x $from $x $to 150")
        device.waitForIdle()
    }
}

/** Un carattere alla volta via IME, come una persona: ogni tasto è un'emissione di stato. */
fun MacrobenchmarkScope.typeSlowly(text: String) {
    text.forEach { char ->
        device.executeShellCommand("input text $char")
        SystemClock.sleep(300)
    }
}
