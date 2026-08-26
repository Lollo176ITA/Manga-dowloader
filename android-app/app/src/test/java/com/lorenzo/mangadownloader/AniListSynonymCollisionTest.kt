package com.lorenzo.mangadownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Collisioni fra il titolo proprio di una serie e il sinonimo di un'altra.
 *
 * Il caso è reale e verificato sull'API: cercando "Pick Me Up", AniList restituisce **per
 * prima** la raccolta hentai `Gekka Bijin` (id 94792), che quel titolo ce l'ha fra i suoi
 * diciotto sinonimi — sono i titoli dei singoli capitoli — e solo dopo il webtoon coreano
 * `Pick Me Up!` (id 159441), che si chiama davvero così.
 *
 * Prendendo il primo che rivendica il titolo, l'app agganciava la serie all'hentai: copertina
 * sbagliata nei risultati di ricerca, identità sbagliata sul preferito (e quindi su tracking,
 * progressi e sincronizzazione dei favourites) e consigli seminati dalla serie sbagliata.
 * I titoli qui sotto sono quelli veri restituiti dall'API.
 */
class AniListSynonymCollisionTest {

    private val hentaiConSinonimoOmonimo = AniListManga(
        id = 94792,
        titleRomaji = "Gekka Bijin",
        titleEnglish = null,
        titleNative = "月下美人",
        synonyms = listOf(
            "My Dear Cow", "Sperma Shining Exercise", "Yaei", "Tabi", "In Public",
            "Pleasure Room", "Silk no Uraji", "Anata ga Hoshii", "That Kind of World",
            "Fallen", "Two Sides", "Pick Me Up", "Girls Who Go Too Far: Mayu-chan",
            "A Queen of the Night", "Tsuyatsuya Exercise",
        ),
        coverUrl = "https://anilist/cover94792.jpg",
        genres = listOf("Hentai"),
        averageScore = null,
        description = null,
        status = MangaPublicationStatus.UNKNOWN,
    )

    private val webtoonOmonimo = AniListManga(
        id = 159441,
        titleRomaji = "Pick Me Up!",
        titleEnglish = "Pick Me Up",
        titleNative = "픽 미 업!",
        coverUrl = "https://anilist/cover159441.jpg",
        genres = listOf("Action", "Adventure", "Fantasy"),
        averageScore = null,
        description = null,
        status = MangaPublicationStatus.ONGOING,
    )

    /** Nell'ordine in cui AniList li restituisce: l'hentai per primo. */
    private val candidatiComeLiDaAniList = listOf(hentaiConSinonimoOmonimo, webtoonOmonimo)

    @Test
    fun `il preferito si aggancia alla serie che si chiama davvero cosi`() {
        val match = matchAniListCandidate("Pick Me Up", candidatiComeLiDaAniList)

        assertEquals(159441, match?.id)
    }

    @Test
    fun `il raggruppamento della ricerca usa la copertina del webtoon, non dell hentai`() {
        val results = listOf(
            MangaSearchResult(
                sourceId = MangaSourceIds.MANGAPILL,
                title = "Pick Me Up",
                mangaUrl = "https://mangapill.com/manga/1",
                coverUrl = "https://mangapill/cover.jpg",
            ),
        )

        val group = SeriesGrouping.groupResults(results, candidatiComeLiDaAniList).single()

        assertEquals("anilist:159441", group.seriesKey)
        assertEquals("https://anilist/cover159441.jpg", group.coverUrl)
    }

    @Test
    fun `un sinonimo vale ancora quando nessuno rivendica il titolo come proprio`() {
        // La precedenza non deve rompere il caso per cui i sinonimi esistono: il titolo
        // italiano di una serie sta lì, e senza di esso la card ITA resterebbe separata.
        val aot = AniListManga(
            id = 53390,
            titleRomaji = "Shingeki no Kyojin",
            titleEnglish = "Attack on Titan",
            synonyms = listOf("L'attacco dei giganti"),
            coverUrl = null,
            genres = emptyList(),
            averageScore = null,
            description = null,
            status = MangaPublicationStatus.UNKNOWN,
        )

        val match = matchAniListCandidate("L'Attacco dei Giganti", listOf(aot))

        assertEquals(53390, match?.id)
    }

    @Test
    fun `fra due candidati vince chi ha il titolo proprio anche se arriva dopo`() {
        val soloSinonimo = AniListManga(
            id = 1,
            titleRomaji = "Serie Diversa",
            titleEnglish = null,
            synonyms = listOf("Berserk"),
            coverUrl = null,
            genres = emptyList(),
            averageScore = null,
            description = null,
            status = MangaPublicationStatus.UNKNOWN,
        )
        val titoloProprio = AniListManga(
            id = 2,
            titleRomaji = "Berserk",
            titleEnglish = "Berserk",
            coverUrl = null,
            genres = emptyList(),
            averageScore = null,
            description = null,
            status = MangaPublicationStatus.UNKNOWN,
        )

        assertEquals(2, matchAniListCandidate("Berserk", listOf(soloSinonimo, titoloProprio))?.id)
    }

    @Test
    fun `i titoli propri e i sinonimi restano distinguibili`() {
        assertEquals(
            listOf("Pick Me Up", "Pick Me Up!", "픽 미 업!"),
            webtoonOmonimo.primaryTitles(),
        )
        assertEquals(emptyList<String>(), webtoonOmonimo.synonymTitles())
        assertEquals(listOf("Gekka Bijin", "月下美人"), hentaiConSinonimoOmonimo.primaryTitles())
        assertTrue(
            "il sinonimo che causava la collisione",
            "Pick Me Up" in hentaiConSinonimoOmonimo.synonymTitles(),
        )
    }
}
