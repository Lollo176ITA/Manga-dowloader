package com.lorenzo.mangadownloader

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fallback tra i mirror di una serie preferita: ordine dei tentativi, stop al primo successo,
 * e salute registrata. Tutto puro — il fetch è iniettato, nessuna rete.
 */
class FavoriteSourceFallbackTest {

    private fun favorite(sourceId: String, url: String) = FavoriteManga(
        sourceId = sourceId,
        title = "One Piece",
        mangaUrl = url,
        coverUrl = null,
        seriesKey = "anilist:21",
    )

    private fun link(vararg sources: Pair<String, String>, preferred: String? = null) = SeriesLink(
        seriesKey = "anilist:21",
        aniListId = 21,
        canonicalTitle = "One Piece",
        coverUrl = null,
        sources = sources.map { (id, url) -> SeriesSourceBinding(id, url) },
        preferredSourceId = preferred,
    )

    private fun details(sourceId: String, url: String) = MangaDetails(
        sourceId = sourceId,
        title = "One Piece",
        coverUrl = null,
        mangaUrl = url,
        chapters = emptyList(),
    )

    @Test
    fun laFontePreferitaVienePrimaDelleAltre() {
        val candidates = favoriteSourceCandidates(
            favorite = favorite("mangapill", "https://mangapill.com/manga/2"),
            link = link(
                "mangapill" to "https://mangapill.com/manga/2",
                "vymanga" to "https://vymanga.com/manga/9",
                preferred = "vymanga",
            ),
        )
        assertEquals(listOf("vymanga", "mangapill"), candidates.map { it.sourceId })
    }

    @Test
    fun ilBindingCorrenteEProvatoAncheSeIlLinkNonLoConosce() {
        val candidates = favoriteSourceCandidates(
            favorite = favorite("asura", "https://asuracomic.net/series/x"),
            link = link("mangapill" to "https://mangapill.com/manga/2"),
        )
        assertEquals(listOf("asura", "mangapill"), candidates.map { it.sourceId })
    }

    @Test
    fun leFontiDisabilitateNonVengonoInterrogate() {
        val candidates = favoriteSourceCandidates(
            favorite = favorite("mangapill", "https://mangapill.com/manga/2"),
            link = link(
                "mangapill" to "https://mangapill.com/manga/2",
                "vymanga" to "https://vymanga.com/manga/9",
            ),
            disabledSourceIds = setOf("vymanga"),
        )
        assertEquals(listOf("mangapill"), candidates.map { it.sourceId })
    }

    @Test
    fun conTutteLeFontiDisabilitateRestaComunqueQuellaCorrente() {
        val candidates = favoriteSourceCandidates(
            favorite = favorite("mangapill", "https://mangapill.com/manga/2"),
            link = link("mangapill" to "https://mangapill.com/manga/2"),
            disabledSourceIds = setOf("mangapill"),
        )
        assertEquals(listOf("mangapill"), candidates.map { it.sourceId })
    }

    /**
     * Un link può avere due URL della stessa fonte (schema del sito cambiato): i tentativi
     * devono comunque andare su fonti diverse, altrimenti il fallback non serve a niente.
     */
    @Test
    fun dueUrlDellaStessaFonteContanoComeUnSoloTentativo() {
        val candidates = favoriteSourceCandidates(
            favorite = favorite("mangapill", "https://mangapill.com/manga/2"),
            link = link(
                "mangapill" to "https://mangapill.com/manga/2",
                "mangapill" to "https://mangapill.com/manga/2-vecchio",
                "vymanga" to "https://vymanga.com/manga/9",
            ),
        )
        assertEquals(listOf("mangapill", "vymanga"), candidates.map { it.sourceId })
    }

    @Test
    fun siProvanoAlMassimoTreFonti() {
        val candidates = favoriteSourceCandidates(
            favorite = favorite("mangapill", "https://mangapill.com/manga/2"),
            link = link(
                "mangapill" to "https://mangapill.com/manga/2",
                "vymanga" to "https://vymanga.com/manga/9",
                "asura" to "https://asuracomic.net/series/x",
                "demonicscans" to "https://demonicscans.org/manga/y",
            ),
        )
        assertEquals(MAX_SOURCE_CANDIDATES, candidates.size)
    }

    @Test
    fun ciSiFermaAlPrimoMirrorCheRisponde() = runBlocking {
        val tried = mutableListOf<String>()
        val result = fetchFromFirstAvailable(
            listOf(
                SeriesSourceBinding("mangapill", "https://mangapill.com/manga/2"),
                SeriesSourceBinding("vymanga", "https://vymanga.com/manga/9"),
            ),
        ) { binding ->
            tried += binding.sourceId
            details(binding.sourceId, binding.mangaUrl)
        }
        assertEquals("mangapill", result?.binding?.sourceId)
        assertEquals(listOf("mangapill"), tried)
    }

    @Test
    fun sePrimoMirrorFallisceSiUsaIlSecondo() = runBlocking {
        val result = fetchFromFirstAvailable(
            listOf(
                SeriesSourceBinding("mangapill", "https://mangapill.com/manga/2"),
                SeriesSourceBinding("vymanga", "https://vymanga.com/manga/9"),
            ),
        ) { binding ->
            if (binding.sourceId == "mangapill") throw IOException("giù")
            details(binding.sourceId, binding.mangaUrl)
        }
        assertEquals("vymanga", result?.binding?.sourceId)
    }

    @Test
    fun seFallisconoTutteIlRisultatoENullo() = runBlocking {
        val result = fetchFromFirstAvailable(
            listOf(SeriesSourceBinding("mangapill", "https://mangapill.com/manga/2")),
        ) { throw IOException("giù") }
        assertNull(result)
    }

    @Test
    fun ilCambioMirrorVieneRegistratoSoloSeDiverso() {
        val invariato = recordSourceSuccess(null, "mangapill", "mangapill", 1_000L)
        assertNull(invariato.switchedToSourceId)

        val cambiato = recordSourceSuccess(null, "mangapill", "vymanga", 1_000L)
        assertEquals("vymanga", cambiato.switchedToSourceId)
        assertEquals(0, cambiato.consecutiveFailures)
    }

    @Test
    fun ilSuccessoAzzeraIFallimentiPrecedenti() {
        val dopoTreFallimenti = FavoriteSourceHealth(consecutiveFailures = 3)
        val risanato = recordSourceSuccess(dopoTreFallimenti, "mangapill", "mangapill", 1_000L)
        assertEquals(0, risanato.consecutiveFailures)
        assertNull(favoriteSourceNotice(risanato))
    }

    @Test
    fun lAvvisoDiIrraggiungibilitaArrivaSoloAllaTerzaVolta() {
        var health: FavoriteSourceHealth? = null
        repeat(UNREACHABLE_FAILURE_THRESHOLD - 1) {
            health = recordSourceFailure(health)
            assertNull("Nessun avviso prima della soglia", favoriteSourceNotice(health))
        }
        health = recordSourceFailure(health)
        assertEquals(FavoriteNoticeKind.UNREACHABLE, favoriteSourceNotice(health)?.kind)
    }

    @Test
    fun lIrraggiungibilitaHaLaPrecedenzaSulCambioFonte() {
        val health = FavoriteSourceHealth(
            consecutiveFailures = UNREACHABLE_FAILURE_THRESHOLD,
            switchedToSourceId = "vymanga",
        )
        assertEquals(FavoriteNoticeKind.UNREACHABLE, favoriteSourceNotice(health)?.kind)
    }

    @Test
    fun lAvvisoDiCambioFonteNominaLaFonteNuova() {
        val notice = favoriteSourceNotice(FavoriteSourceHealth(switchedToSourceId = "vymanga"))
        assertEquals(FavoriteNoticeKind.SOURCE_SWITCHED, notice?.kind)
        assertTrue(
            "L'etichetta deve dire per esteso da dove si legge ora: ${notice?.label}",
            notice?.label?.contains(MangaSourceCatalog.displayName("vymanga")) == true,
        )
    }
}
