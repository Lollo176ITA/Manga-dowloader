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
    fun readingDiary_roundTripsAndSkipsCorruptDayKeys() {
        val store = ReadingDiaryStore(prefs())
        val expected = mapOf("2026-07-16" to ReadingDayStats(chaptersRead = 3, pagesRead = 40))

        store.persist(expected)
        assertEquals(expected, store.read())

        prefs().edit()
            .putString(
                "reading_diary_json",
                """{"2026-07-16":{"chaptersRead":1,"pagesRead":2},"garbage":{"chaptersRead":9}}""",
            )
            .commit()
        assertEquals(setOf("2026-07-16"), store.read().keys)
    }

    @Test
    fun aniListTrackings_roundTripKeepsDomainMapping() {
        val store = AniListStore(prefs())
        val tracking = AniListTracking(
            mediaId = 2,
            title = "Berserk",
            totalChapters = 380,
            status = AniListListStatus.CURRENT,
            progress = 42,
            score = 9.5,
            pendingProgress = 43,
        )

        // Chiave canonica (SeriesKey): round-trip senza sorprese.
        store.persistTrackings(mapOf("anilist:2" to tracking))
        assertEquals(mapOf("anilist:2" to tracking), store.readTrackings())

        // Chiave legacy per-fonte: in lettura viene migrata alla SeriesKey del media.
        store.persistTrackings(mapOf("mangapill::berserk" to tracking))
        assertEquals(mapOf("anilist:2" to tracking), store.readTrackings())
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

        // La chiave non canonica viene migrata alla SeriesKey del media in lettura.
        assertEquals(setOf("anilist:7"), trackings.keys)
        assertEquals(AniListListStatus.PLANNING, trackings.getValue("anilist:7").status)
    }

    private fun prefs() =
        application.getSharedPreferences("json_stores_test", Context.MODE_PRIVATE)
}
