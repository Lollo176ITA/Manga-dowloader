package com.lorenzo.mangadownloader.data.store

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lorenzo.mangadownloader.platform.settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Chi aggiorna l'app ha le preferenze scritte con SharedPreferences: lette attraverso [Settings]
 * devono restituire gli stessi valori, e ciò che scriviamo ora deve restare leggibile dal formato
 * nativo (widget, eventuale downgrade).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SettingsCompatibilityTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun valuesWrittenWithSharedPreferencesAreReadThroughSettings() {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("text", "ciao")
            .putBoolean("flag", true)
            .putInt("count", 7)
            .putLong("millis", 1_700_000_000_000L)
            .putFloat("brightness", 0.4f)
            .putStringSet("ids", setOf("url:a", "number:3"))
            .commit()

        val settings = context.settings(PREFS)

        assertEquals("ciao", settings.getString("text", null))
        assertNull(settings.getString("missing", null))
        assertEquals(true, settings.getBoolean("flag", false))
        assertEquals(7, settings.getInt("count", 0))
        assertEquals(1_700_000_000_000L, settings.getLong("millis", 0L))
        assertEquals(0.4f, settings.getFloat("brightness", 1f))
        assertEquals(setOf("url:a", "number:3"), settings.getStringSet("ids"))
        assertEquals(emptySet<String>(), settings.getStringSet("missing-set"))
    }

    @Test
    fun valuesWrittenThroughSettingsStayReadableAsSharedPreferences() {
        val settings = context.settings(PREFS)
        settings.edit {
            putString("text", "ciao")
            putStringSet("ids", setOf("slug:x"))
            putNullableString("salt", null)
        }

        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        assertEquals("ciao", raw.getString("text", null))
        assertEquals(setOf("slug:x"), raw.getStringSet("ids", null))
        assertTrue(!raw.contains("salt"))
    }

    private companion object {
        const val PREFS = "settings_compat_test"
    }
}
