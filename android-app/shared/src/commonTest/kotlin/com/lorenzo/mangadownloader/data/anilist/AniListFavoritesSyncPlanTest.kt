package com.lorenzo.mangadownloader.data.anilist

import com.lorenzo.mangadownloader.app.FavoriteManga
import com.lorenzo.mangadownloader.data.model.MangaPublicationStatus
import com.lorenzo.mangadownloader.data.model.MangaSearchResult
import com.lorenzo.mangadownloader.data.sources.MangaSourceIds
import com.lorenzo.mangadownloader.domain.series.SeriesIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Le regole di riconciliazione dei preferiti, senza rete né SharedPreferences.
 *
 * Il caso che conta davvero è la resurrezione: un preferito già riconciliato che sparisce da
 * un lato è una rimozione voluta e deve restare fuori, altrimenti tornerebbe dentro a ogni
 * giro e l'utente non riuscirebbe mai a toglierlo.
 */
class AniListFavoritesSyncPlanTest {

    @Test
    fun un_preferito_solo_in_app_viene_spinto_su_AniList() {
        val plan = planAniListFavoritesSync(
            appMediaIds = setOf(1),
            aniListMediaIds = emptySet(),
            alreadyReconciled = emptySet(),
        )

        assertEquals(listOf(1), plan.toPushToAniList)
        assertEquals(emptyList<Int>(), plan.toImportInApp)
    }

    @Test
    fun un_favourite_solo_su_AniList_viene_importato_in_app() {
        val plan = planAniListFavoritesSync(
            appMediaIds = emptySet(),
            aniListMediaIds = setOf(2),
            alreadyReconciled = emptySet(),
        )

        assertEquals(emptyList<Int>(), plan.toPushToAniList)
        assertEquals(listOf(2), plan.toImportInApp)
    }

    @Test
    fun un_preferito_tolto_in_app_dopo_la_riconciliazione_non_viene_reimportato() {
        val plan = planAniListFavoritesSync(
            appMediaIds = emptySet(),
            aniListMediaIds = setOf(3),
            alreadyReconciled = setOf(3),
        )

        assertEquals(emptyList<Int>(), plan.toImportInApp)
    }

    @Test
    fun un_favourite_tolto_su_AniList_dopo_la_riconciliazione_non_viene_rispinto() {
        val plan = planAniListFavoritesSync(
            appMediaIds = setOf(4),
            aniListMediaIds = emptySet(),
            alreadyReconciled = setOf(4),
        )

        assertEquals(emptyList<Int>(), plan.toPushToAniList)
    }

    @Test
    fun i_titoli_introvabili_sulle_fonti_non_vengono_ricercati_di_nuovo() {
        val plan = planAniListFavoritesSync(
            appMediaIds = emptySet(),
            aniListMediaIds = setOf(5, 6),
            alreadyReconciled = emptySet(),
            failedImports = setOf(5),
        )

        assertEquals(listOf(6), plan.toImportInApp)
    }

    @Test
    fun gli_import_sono_limitati_per_giro() {
        val plan = planAniListFavoritesSync(
            appMediaIds = emptySet(),
            aniListMediaIds = (1..50).toSet(),
            alreadyReconciled = emptySet(),
        )

        assertEquals(MAX_ANILIST_FAVORITE_IMPORTS_PER_RUN, plan.toImportInApp.size)
        // Quel che avanza non viene perso: non essendo riconciliato, torna al giro dopo.
        assertEquals((1..MAX_ANILIST_FAVORITE_IMPORTS_PER_RUN).toList(), plan.toImportInApp)
    }

    @Test
    fun un_id_propagato_con_successo_diventa_riconciliato() {
        val reconciled = reconciledAniListFavoriteIds(
            alreadyReconciled = emptySet(),
            appMediaIds = setOf(1),
            aniListMediaIds = emptySet(),
            succeeded = setOf(1),
        )

        assertEquals(setOf(1), reconciled)
    }

    @Test
    fun un_id_fallito_resta_fuori_dai_riconciliati_e_verra_ritentato() {
        val reconciled = reconciledAniListFavoriteIds(
            alreadyReconciled = emptySet(),
            appMediaIds = setOf(1),
            aniListMediaIds = emptySet(),
            succeeded = emptySet(),
        )

        assertEquals(emptySet<Int>(), reconciled)
    }

    @Test
    fun un_id_presente_da_entrambe_le_parti_e_gia_riconciliato() {
        val reconciled = reconciledAniListFavoriteIds(
            alreadyReconciled = emptySet(),
            appMediaIds = setOf(7),
            aniListMediaIds = setOf(7),
            succeeded = emptySet(),
        )

        assertEquals(setOf(7), reconciled)
    }

    @Test
    fun il_match_usa_anche_i_sinonimi_e_ignora_accenti_e_punteggiatura() {
        val media = aniListManga(
            id = 1,
            english = "Attack on Titan",
            romaji = "Shingeki no Kyojin",
            synonyms = listOf("L'attacco dei giganti"),
        )
        val results = listOf(
            searchResult("Berserk"),
            searchResult("L attacco dei Giganti"),
        )

        assertEquals("L attacco dei Giganti", matchSourceResultForAniList(media, results)?.title)
    }

    @Test
    fun un_titolo_solo_somigliante_non_viene_importato() {
        val media = aniListManga(id = 1, english = "One Piece", romaji = "One Piece")
        val results = listOf(searchResult("One Piece Party"), searchResult("One Punch Man"))

        assertNull(matchSourceResultForAniList(media, results))
    }

    @Test
    fun le_query_di_ricerca_sono_al_massimo_due_e_senza_doppioni() {
        val media = aniListManga(id = 1, english = "Berserk", romaji = "Berserk")

        assertEquals(listOf("Berserk"), aniListImportSearchQueries(media))
        assertEquals(listOf("Attack on Titan", "Shingeki no Kyojin"), aniListImportSearchQueries(
                aniListManga(id = 2, english = "Attack on Titan", romaji = "Shingeki no Kyojin"),
            ))
    }

    private fun aniListManga(
        id: Int,
        english: String?,
        romaji: String?,
        synonyms: List<String> = emptyList(),
    ) = AniListManga(
        id = id,
        titleRomaji = romaji,
        titleEnglish = english,
        synonyms = synonyms,
        coverUrl = null,
        genres = emptyList(),
        averageScore = null,
        description = null,
        status = MangaPublicationStatus.ONGOING,
    )

    private fun searchResult(title: String) = MangaSearchResult(
        sourceId = MangaSourceIds.MANGAPILL,
        title = title,
        mangaUrl = "https://mangapill.com/manga/${title.hashCode()}",
        coverUrl = null,
    )

    @Test
    fun un_import_gia_presente_in_app_sotto_chiave_titolo_non_crea_un_doppione() {
        // La serie c'e' gia', ma AniList non era riuscito ad agganciarla: chiave `title:`.
        val esistente = FavoriteManga(
            sourceId = MangaSourceIds.MANGAPILL,
            title = "Berserk",
            mangaUrl = "https://mangapill.com/manga/1",
            coverUrl = null,
            seriesKey = SeriesIdentity.keyForTitle("Berserk").orEmpty(),
        )
        val importato = FavoriteManga(
            sourceId = MangaSourceIds.MANGA_WORLD,
            title = "Berserk",
            mangaUrl = "https://www.mangaworld.mx/manga/2",
            coverUrl = null,
            seriesKey = SeriesIdentity.keyForAniList(30002),
        )

        assertEquals(emptyList<FavoriteManga>(), newAniListFavorites(listOf(importato), listOf(esistente)))
    }

    @Test
    fun un_import_di_una_serie_che_l_app_non_ha_viene_tenuto() {
        val esistente = FavoriteManga(
            sourceId = MangaSourceIds.MANGAPILL,
            title = "Berserk",
            mangaUrl = "https://mangapill.com/manga/1",
            coverUrl = null,
            seriesKey = SeriesIdentity.keyForAniList(30002),
        )
        val importato = FavoriteManga(
            sourceId = MangaSourceIds.MANGAPILL,
            title = "Vinland Saga",
            mangaUrl = "https://mangapill.com/manga/3",
            coverUrl = null,
            seriesKey = SeriesIdentity.keyForAniList(30642),
        )

        assertEquals(listOf(importato), newAniListFavorites(listOf(importato), listOf(esistente)))
    }

    @Test
    fun senza_import_non_c_e_niente_da_aggiungere() {
        assertEquals(emptyList<FavoriteManga>(), newAniListFavorites(emptyList(), emptyList()))
    }

    @Test
    fun il_match_sulle_fonti_rispetta_l_ordine_in_cui_arrivano_i_risultati() {
        // I risultati arrivano alternati fra le fonti (come nella ricerca dell'app), quindi il
        // primo che combacia non e' piu' per forza quello della fonte in cima al catalogo.
        val media = AniListManga(
            id = 30002,
            titleRomaji = "Berserk",
            titleEnglish = "Berserk",
            coverUrl = null,
            genres = emptyList(),
            averageScore = null,
            description = null,
            status = MangaPublicationStatus.ONGOING,
        )
        val alternati = listOf(
            MangaSearchResult(MangaSourceIds.MANGA_WORLD, "Berserk", "https://mw/1", null),
            MangaSearchResult(MangaSourceIds.MANGAPILL, "Berserk", "https://mp/1", null),
        )

        assertEquals(MangaSourceIds.MANGA_WORLD, matchSourceResultForAniList(media, alternati)?.sourceId)
    }
}
