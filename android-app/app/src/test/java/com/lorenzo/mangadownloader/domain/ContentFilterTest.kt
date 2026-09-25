package com.lorenzo.mangadownloader.domain

import com.lorenzo.mangadownloader.data.anilist.AniListManga
import com.lorenzo.mangadownloader.data.model.MangaPublicationStatus
import com.lorenzo.mangadownloader.data.model.MangaSearchResult
import com.lorenzo.mangadownloader.domain.series.SeriesGrouping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentFilterTest {

    private fun media(id: Int, title: String, genres: List<String> = emptyList(), isAdult: Boolean = false) =
        AniListManga(
            id = id,
            titleRomaji = title,
            titleEnglish = title,
            coverUrl = null,
            genres = genres,
            averageScore = null,
            description = null,
            status = MangaPublicationStatus.ONGOING,
            isAdult = isAdult,
        )

    private fun result(title: String, source: String = "mangapill") =
        MangaSearchResult(source, title, "https://$source/${title.hashCode()}", null)

    @Test
    fun adultContent_isAdultFlagOrHentaiEcchiGenres() {
        assertTrue(media(1, "x", isAdult = true).isAdultContent())
        assertTrue(media(2, "x", genres = listOf("Comedy", "Ecchi")).isAdultContent())
        assertTrue(media(3, "x", genres = listOf(" hentai ")).isAdultContent())
        assertFalse(media(4, "Berserk", genres = listOf("Action", "Drama", "Horror")).isAdultContent())
    }

    @Test
    fun adultTitleMarkers_areConservative() {
        assertTrue(looksLikeAdultTitle("Qualcosa (Hentai)"))
        assertTrue(looksLikeAdultTitle("Storia [R18]"))
        assertTrue(looksLikeAdultTitle("Doujin 18+ edition"))
        assertFalse(looksLikeAdultTitle("Kaiju No. 8"))
        assertFalse(looksLikeAdultTitle("Classe 1-A"))
        assertFalse(looksLikeAdultTitle("Area 18"))
    }

    @Test
    fun searchFilter_dropsGroupsLinkedToAdultMedia_andTheirFlatResults() {
        val safe = result("One Piece")
        val adult = result("Oshi no Nudes", source = "asura")
        val adultOther = result("Oshi no Nudes")
        val unknown = result("Serie Sconosciuta")
        val flat = listOf(safe, adult, adultOther, unknown)
        val candidates = listOf(media(1, "One Piece"), media(2, "Oshi no Nudes", isAdult = true))
        val groups = SeriesGrouping.groupResults(flat, candidates)

        val filtered = filterAdultSearchResults(flat, groups, candidates)

        assertEquals(listOf(safe, unknown), filtered.results)
        assertEquals(listOf("One Piece", "Serie Sconosciuta"), filtered.groups.map { it.title })
    }

    @Test
    fun adultGenre_coversExplicitAndEcchiButNotMature() {
        listOf("Hentai", "ecchi", "Smut", "Adult,", " adulti ", "adulto", "Erotico", "Lolicon", "shotacon")
            .forEach { assertTrue(it, isAdultGenre(it)) }
        listOf("Mature", "maturo", "Romance", "Harem", "Seinen", "Horror", "violence")
            .forEach { assertFalse(it, isAdultGenre(it)) }
    }

    @Test
    fun searchFilter_dropsTheWholeGroupWhenAnySourceFlagsTheSeries() {
        // AniList non conosce la serie: il segnale arriva solo da una delle due fonti, ma è la
        // stessa serie, quindi sparisce anche il mirror che non la segnala.
        val flagged = result("Serie Ecchi", source = "manga_world").copy(isAdult = true)
        val mirror = result("Serie Ecchi", source = "hasta_team")
        val safe = result("One Piece")
        val flat = listOf(flagged, mirror, safe)
        val groups = SeriesGrouping.groupResults(flat, emptyList())

        val filtered = filterAdultSearchResults(flat, groups, emptyList())

        assertEquals(listOf(safe), filtered.results)
        assertEquals(listOf("One Piece"), filtered.groups.map { it.title })
    }
}
