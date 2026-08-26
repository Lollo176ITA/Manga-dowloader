package com.lorenzo.mangadownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeriesGroupingTest {

    private fun result(sourceId: String, title: String, url: String) =
        MangaSearchResult(sourceId = sourceId, title = title, mangaUrl = url, coverUrl = null)

    private fun candidate(
        id: Int,
        english: String?,
        romaji: String?,
        synonyms: List<String> = emptyList(),
    ) = AniListManga(
        id = id,
        titleRomaji = romaji,
        titleEnglish = english,
        titleNative = null,
        synonyms = synonyms,
        coverUrl = "https://anilist/cover$id.jpg",
        genres = emptyList(),
        averageScore = null,
        description = null,
        status = MangaPublicationStatus.UNKNOWN,
    )

    @Test
    fun `raggruppa ITA e ENG sotto lo stesso media via sinonimi`() {
        val results = listOf(
            result("mangapill", "Attack on Titan", "https://mangapill.com/manga/1"),
            result("manga_world", "L'Attacco dei Giganti", "https://www.mangaworld.mx/manga/2"),
        )
        val candidates = listOf(
            candidate(53390, "Attack on Titan", "Shingeki no Kyojin", synonyms = listOf("L'attacco dei giganti")),
        )
        val groups = SeriesGrouping.groupResults(results, candidates)
        assertEquals(1, groups.size)
        assertEquals("anilist:53390", groups.single().seriesKey)
        assertEquals(2, groups.single().results.size)
        assertEquals("Attack on Titan", groups.single().title)
        assertEquals("https://anilist/cover53390.jpg", groups.single().coverUrl)
    }

    @Test
    fun `senza candidati degrada al raggruppamento per titolo`() {
        val results = listOf(
            result("mangapill", "One Piece", "https://mangapill.com/manga/3"),
            result("vymanga", "One Piece!", "https://vymanga.com/manga/4"),
            result("manga_world", "Naruto", "https://www.mangaworld.mx/manga/5"),
        )
        val groups = SeriesGrouping.groupResults(results, emptyList())
        assertEquals(2, groups.size)
        assertEquals("title:one piece", groups[0].seriesKey)
        assertNull(groups[0].aniListId)
        assertEquals(2, groups[0].results.size)
        assertEquals("title:naruto", groups[1].seriesKey)
    }

    @Test
    fun `titolo non matchato resta card singola e ordine preservato`() {
        val results = listOf(
            result("mangapill", "Doujin Sconosciuto", "https://mangapill.com/manga/6"),
            result("mangapill", "Attack on Titan", "https://mangapill.com/manga/1"),
        )
        val candidates = listOf(candidate(53390, "Attack on Titan", null))
        val groups = SeriesGrouping.groupResults(results, candidates)
        assertEquals(2, groups.size)
        assertEquals("title:doujin sconosciuto", groups[0].seriesKey)
        assertEquals("anilist:53390", groups[1].seriesKey)
    }

    @Test
    fun `match solo per uguaglianza normalizzata esatta`() {
        val results = listOf(
            result("mangapill", "Attack on Titan: Before the Fall", "https://mangapill.com/manga/7"),
        )
        val candidates = listOf(candidate(53390, "Attack on Titan", null))
        val groups = SeriesGrouping.groupResults(results, candidates)
        assertEquals("title:attack on titan before the fall", groups.single().seriesKey)
    }

    @Test
    fun `cover di gruppo ripiega sulla prima fonte se AniList non la ha`() {
        val results = listOf(
            MangaSearchResult("mangapill", "Attack on Titan", "https://mangapill.com/manga/1", "https://mp/cover.jpg"),
        )
        val noCover = candidate(53390, "Attack on Titan", null).copy(coverUrl = null)
        val groups = SeriesGrouping.groupResults(results, listOf(noCover))
        assertEquals("https://mp/cover.jpg", groups.single().coverUrl)
    }
}
