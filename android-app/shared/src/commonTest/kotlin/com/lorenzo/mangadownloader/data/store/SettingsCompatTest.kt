package com.lorenzo.mangadownloader.data.store

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class SettingsCompatTest {

    private val settings = MapSettings()

    @Test
    fun stringSetsRoundTripWhereTheyAreNotNative() {
        settings.putStringSet("ids", setOf("url:a", "number:3"))

        assertEquals(setOf("url:a", "number:3"), settings.getStringSet("ids"))
        assertEquals(emptySet(), settings.getStringSet("missing"))
    }

    @Test
    fun nullableStringsBehaveLikeSharedPreferences() {
        settings.edit {
            putNullableString("salt", "abc")
            putNullableString("hash", null)
        }

        assertEquals("abc", settings.getString("salt", null))
        assertNull(settings.getString("hash", null))
        assertFalse(settings.hasKey("hash"))

        settings.putNullableString("salt", null)
        assertFalse(settings.hasKey("salt"))
    }
}
