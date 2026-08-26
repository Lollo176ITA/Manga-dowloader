package com.lorenzo.mangadownloader

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Migrazione lazy dei legami tracking: le chiavi storiche erano identityKey per-fonte
 * (`sourceId::url`); il mediaId nel valore permette di ri-ancorarle alla SeriesKey
 * canonica `anilist:<id>`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AniListStoreMigrationTest {

    private lateinit var store: AniListStore

    @Before
    fun setUp() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val prefs = application.getSharedPreferences(SettingsStore.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        store = AniListStore(prefs)
    }

    @Test
    fun `readTrackings migra le chiavi legacy identityKey ad anilist`() {
        store.persistTrackings(
            mapOf(
                "mangapill::https://mangapill.com/manga/1" to
                    AniListTracking(mediaId = 53390, title = "AoT", progress = 12),
            ),
        )

        val migrated = store.readTrackings()

        assertEquals(setOf("anilist:53390"), migrated.keys)
        assertEquals(12, migrated["anilist:53390"]!!.progress)
        // La migrazione viene ri-persistita: una seconda lettura è già canonica.
        assertEquals(setOf("anilist:53390"), store.readTrackings().keys)
    }

    @Test
    fun `migrazione con duplicati tiene il progress piu alto`() {
        store.persistTrackings(
            mapOf(
                "mangapill::https://mangapill.com/manga/1" to
                    AniListTracking(mediaId = 53390, title = "AoT", progress = 12),
                "vymanga::https://vymanga.com/manga/2" to
                    AniListTracking(mediaId = 53390, title = "AoT", progress = 30),
            ),
        )

        assertEquals(30, store.readTrackings()["anilist:53390"]!!.progress)
    }

    @Test
    fun `chiavi gia canoniche restano invariate`() {
        store.persistTrackings(
            mapOf(
                "anilist:1" to AniListTracking(mediaId = 1, title = "A", progress = 3),
                "title:one piece" to AniListTracking(mediaId = 2, title = "B", progress = 4),
            ),
        )

        assertEquals(setOf("anilist:1", "title:one piece"), store.readTrackings().keys)
    }
}
