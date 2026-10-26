package com.lorenzo.mangadownloader.data.store

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ParentalLockoutStoreTest {

    private lateinit var prefs: SharedPreferences
    private var clock = BootInstant(bootCount = 3, elapsedMillis = 1_000_000L)

    @Before
    fun setUp() {
        prefs = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("parental_lockout_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @Test
    fun `nello stesso avvio il blocco scorre col tempo dall avvio`() {
        val store = store()
        store.recordFailure(lockoutMillis = 60_000L)

        advance(20_000L)
        assertEquals(40_000L, store.remainingLockoutMillis())

        advance(40_000L)
        assertEquals(0L, store.remainingLockoutMillis())
    }

    @Test
    fun `dopo un riavvio il blocco riparte intero`() {
        val store = store()
        store.recordFailure(lockoutMillis = 60_000L)
        advance(50_000L)

        clock = BootInstant(bootCount = 4, elapsedMillis = 5_000L)
        assertEquals(60_000L, store.remainingLockoutMillis())

        // Il nuovo blocco poi scorre normalmente nel nuovo avvio.
        advance(15_000L)
        assertEquals(45_000L, store.remainingLockoutMillis())
    }

    @Test
    fun `senza BOOT_COUNT il riavvio si riconosce dal tempo dall avvio tornato indietro`() {
        clock = BootInstant(bootCount = -1, elapsedMillis = 1_000_000L)
        val store = store()
        store.recordFailure(lockoutMillis = 60_000L)

        clock = BootInstant(bootCount = -1, elapsedMillis = 5_000L)
        assertEquals(60_000L, store.remainingLockoutMillis())
    }

    @Test
    fun `un blocco scaduto e gia visto non riparte dopo un riavvio`() {
        val store = store()
        store.recordFailure(lockoutMillis = 30_000L)
        advance(30_000L)
        assertEquals(0L, store.remainingLockoutMillis())

        clock = BootInstant(bootCount = 4, elapsedMillis = 5_000L)
        assertEquals(0L, store.remainingLockoutMillis())
    }

    @Test
    fun `gli errori senza blocco non bloccano e reset azzera tutto`() {
        val store = store()
        assertEquals(1, store.recordFailure(lockoutMillis = 0L))
        assertEquals(0L, store.remainingLockoutMillis())

        store.recordFailure(lockoutMillis = 60_000L)
        store.reset()

        assertEquals(0, store.failedAttempts())
        assertEquals(0L, store.remainingLockoutMillis())
    }

    @Test
    fun `il vecchio blocco a orologio viene convertito`() {
        prefs.edit()
            .putLong("parental_pin_locked_until", System.currentTimeMillis() + 60 * 60_000L)
            .commit()

        val store = store()

        assertFalse(prefs.contains("parental_pin_locked_until"))
        // Anche se il vecchio valore era più lungo, non si supera il blocco massimo.
        assertEquals(15 * 60_000L, store.remainingLockoutMillis())
    }

    private fun store() = ParentalLockoutStore(prefs) { clock }

    private fun advance(millis: Long) {
        clock = clock.copy(elapsedMillis = clock.elapsedMillis + millis)
    }
}
