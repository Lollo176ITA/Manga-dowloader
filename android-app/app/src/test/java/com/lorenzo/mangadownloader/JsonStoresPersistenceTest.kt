package com.lorenzo.mangadownloader

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class JsonStoresPersistenceTest {

    private lateinit var application: Application

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        prefs().edit().clear().commit()
    }

    @Test
    fun recentSearches_roundTripsAndFallsBackOnCorruptJson() {
        val store = RecentSearchesStore(prefs())
        store.persist(listOf(" Berserk ", "", "Vinland Saga"))

        assertEquals(listOf("Berserk", "Vinland Saga"), store.read())

        prefs().edit().putString("recent_searches_json", "{broken").commit()
        assertTrue(store.read().isEmpty())
    }

    @Test
    fun readingMemory_roundTripsAndFallsBackOnCorruptJson() {
        val store = ReadingMemoryStore(prefs())
        val expected = mapOf(
            "Berserk/chapter_1.cbz" to ReadChapterMemory(
                seriesKey = "Berserk",
                seriesTitle = "Berserk",
                chapterLabel = "Capitolo 1",
                pagesRead = 20,
                pageCount = 20,
                isRead = true,
                lastReadAtMillis = 1_000L,
            ),
        )

        store.persist(expected)
        assertEquals(expected, store.read())

        prefs().edit().putString("reading_memory_json", "{broken").commit()
        assertTrue(store.read().isEmpty())
    }

    @Test
    fun aniListTrackings_roundTripKeepsDomainMapping() {
        val store = AniListStore(prefs())
        val expected = mapOf(
            "mangapill::berserk" to AniListTracking(
                mediaId = 2,
                title = "Berserk",
                totalChapters = 380,
                status = AniListListStatus.CURRENT,
                progress = 42,
                score = 9.5,
                pendingProgress = 43,
            ),
        )

        store.persistTrackings(expected)

        assertEquals(expected, store.readTrackings())
    }

    @Test
    fun aniListTrackings_tolerateFutureFieldsAndDiscardInvalidMediaIds() {
        prefs().edit().putString(
            "anilist_trackings_json",
            """
            {
              "valid": {
                "mediaId": 7,
                "title": "Yotsuba",
                "status": "PLANNING",
                "futureField": true
              },
              "invalid": {"mediaId": 0, "title": "Ignored"}
            }
            """.trimIndent(),
        ).commit()

        val trackings = AniListStore(prefs()).readTrackings()

        assertEquals(setOf("valid"), trackings.keys)
        assertEquals(AniListListStatus.PLANNING, trackings.getValue("valid").status)
    }

    private fun prefs() =
        application.getSharedPreferences("json_stores_test", Context.MODE_PRIVATE)
}
