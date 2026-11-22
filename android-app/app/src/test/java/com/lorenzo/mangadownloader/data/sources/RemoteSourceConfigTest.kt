package com.lorenzo.mangadownloader.data.sources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** `sources.json`: domini aggiornati e fonti in manutenzione, senza rilasciare l'app. */
class RemoteSourceConfigTest {

    private fun overrides(json: String) = parseSourceOverrides(json)

    @Test
    fun movedSource_requestsGoToTheNewDomain_subdomainsIncluded() {
        val overrides = overrides("""{"sources":{"manga_world":{"domain":"mangaworld.nz"}}}""")

        assertEquals("www.mangaworld.nz", overrides.liveHost("www.mangaworld.mx"))
        assertEquals("mangaworld.nz", overrides.liveHost("mangaworld.mx"))
        assertEquals("cdn.mangaworld.nz", overrides.liveHost("CDN.mangaworld.mx"))
        // Un altro sito che finisce allo stesso modo non è un sottodominio.
        assertNull(overrides.liveHost("notmangaworld.mx"))
        assertNull(overrides.liveHost("mangapill.com"))
    }

    @Test
    fun pagesFromTheNewDomain_areReadWithTheSavedDomain() {
        val overrides = overrides("""{"sources":{"manga_world":{"domain":"mangaworld.nz"}}}""")

        // Preferiti, download e cronologia sono salvati col dominio di partenza: i link letti
        // dal sito nuovo devono tornare a quella forma, o non combacerebbero più.
        assertEquals(
            """<a href="https://www.mangaworld.mx/manga/1/x">x</a> <img src="https:\/\/cdn.mangaworld.mx\/1.jpg">""",
            overrides.toSavedDomains(
                """<a href="https://www.mangaworld.nz/manga/1/x">x</a> <img src="https:\/\/cdn.mangaworld.nz\/1.jpg">""",
            ),
        )
    }

    @Test
    fun maintenance_isReportedBySourceAndByHost() {
        val overrides = overrides(
            """{"sources":{"tcb_scans":{"maintenance":"Il sito ha cambiato struttura, arriva un aggiornamento."}}}""",
        )

        assertEquals(
            mapOf(MangaSourceIds.TCB_SCANS to "Il sito ha cambiato struttura, arriva un aggiornamento."),
            overrides.maintenance,
        )
        assertEquals(
            "TCB Scans è in manutenzione: Il sito ha cambiato struttura, arriva un aggiornamento.",
            overrides.maintenanceMessageForHost("tcbonepiecechapters.com"),
        )
        assertNull(overrides.maintenanceMessageForHost("mangapill.com"))
        assertNull(overrides.liveHost("tcbonepiecechapters.com"))
    }

    @Test
    fun onlyKnownSourcesAndPlainDomainsAreAccepted() {
        val overrides = overrides(
            """
            {"sources":{
              "anilist":{"domain":"evil.example"},
              "mangapill":{"domain":"https://evil.example/path"},
              "asura_scans":{"domain":"asurascans.com"},
              "weeb_central":{"domain":" WeebCentral.org ","maintenance":"  "},
              "demonic_scans":{"domain":"localhost"}
            },"altro":1}
            """,
        )

        // Solo le fonti del catalogo si possono spostare, e solo su un nome di dominio: il file
        // non deve poter dirottare altre richieste (AniList porta un token) né indicare percorsi.
        assertEquals(mapOf("weebcentral.com" to "weebcentral.org"), overrides.liveDomains)
        assertTrue(overrides.maintenance.isEmpty())
    }

    @Test
    fun emptyFile_changesNothing() {
        val overrides = overrides("{}")

        assertTrue(overrides.liveDomains.isEmpty())
        assertNull(overrides.liveHost("www.mangaworld.mx"))
        assertEquals("testo", overrides.toSavedDomains("testo"))
    }

    @Test
    fun brokenFile_isRejected_soTheCachedOneStays() {
        assertThrows(Exception::class.java) { overrides("<html>404</html>") }
    }

    @Test
    fun everySourceDeclaresTheDomainItsUrlsUse() {
        MangaSourceCatalog.specs.forEach { spec ->
            assertTrue(
                "${spec.descriptor.id}: ${spec.networkDomain}",
                Regex("""[a-z0-9-]+(\.[a-z0-9-]+)+""").matches(spec.networkDomain),
            )
        }
        assertEquals(
            MangaSourceCatalog.specs.size,
            MangaSourceCatalog.specs.map { it.networkDomain }.toSet().size,
        )
    }
}
