package com.lorenzo.mangadownloader

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Copre la diagnostica dei crash: il file viene scritto dall'handler installato, riletto
 * e cancellato (round-trip), e l'assenza del file dà `null`. Serve Robolectric perché
 * `CrashReporter` scrive su `getExternalFilesDir`/`filesDir` del Context.
 *
 * Nota: la scrittura è privata e parte solo dall'`UncaughtExceptionHandler` registrato da
 * `install`. Per testarla senza terminare la JVM, prima di `install` si imposta un handler
 * "precedente" non-null: così l'handler installato delega a quello invece di chiamare
 * `exitProcess`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CrashReporterTest {

    private lateinit var application: Application
    private var originalHandler: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        // Lo stato persiste su file nella sandbox di Robolectric: parti pulito.
        CrashReporter.clearLastCrash(application)
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(originalHandler)
        CrashReporter.clearLastCrash(application)
    }

    @Test
    fun readLastCrash_returnsNullWhenNoCrashRecorded() {
        assertNull(CrashReporter.readLastCrash(application))
    }

    @Test
    fun clearLastCrash_isNoOpWhenFileAbsent() {
        // Non deve sollevare eccezioni anche se non c'è nulla da cancellare.
        CrashReporter.clearLastCrash(application)

        assertNull(CrashReporter.readLastCrash(application))
    }

    @Test
    fun crashFilePath_pointsAtDiagnosticsFile() {
        val path = CrashReporter.crashFilePath(application)

        assertNotNull(path)
        assertTrue(path!!.replace('\\', '/').endsWith("diagnostics/last_crash.txt"))
    }

    @Test
    fun installedHandler_writesReportThenReadAndClearRoundTrip() {
        // Handler precedente non-null: cattura l'eccezione e impedisce exitProcess.
        val delivered = mutableListOf<Throwable>()
        Thread.setDefaultUncaughtExceptionHandler { _, throwable -> delivered.add(throwable) }

        CrashReporter.install(application)
        val handler = Thread.getDefaultUncaughtExceptionHandler()
        assertNotNull(handler)

        val boom = IllegalStateException("boom-da-test")
        handler!!.uncaughtException(Thread.currentThread(), boom)

        // La catena è rispettata: anche l'handler precedente riceve l'eccezione.
        assertEquals(listOf<Throwable>(boom), delivered)

        val report = CrashReporter.readLastCrash(application)
        assertNotNull(report)
        assertTrue(report!!.contains("boom-da-test"))
        assertTrue(report.contains(IllegalStateException::class.java.name))
        assertTrue(report.contains(Thread.currentThread().name))

        CrashReporter.clearLastCrash(application)
        assertNull(CrashReporter.readLastCrash(application))
    }
}
