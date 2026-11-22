package com.lorenzo.mangadownloader.data.sources

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lorenzo.mangadownloader.data.network.MangaNetworkClient
import com.lorenzo.mangadownloader.data.network.SharedHttpClient
import java.io.File
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Controllo delle fonti **sui siti veri**: ricerca → scheda → pagine di un capitolo → prima
 * immagine. Gli altri test usano HTML salvato e dicono solo che il parser legge quella pagina;
 * questo dice se il sito è ancora fatto così.
 *
 * Non gira coi test normali (usa la rete e dipende da siti di terzi): solo con
 * `-PliveSources=true`, come fa il workflow `sources-check.yml` ogni giorno.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [28])
class LiveSourcesCheckTest(private val sourceId: String) {

    @After
    fun resetOverrides() {
        RemoteSourceConfig.replaceForTest(null)
    }

    @Test
    fun searchDetailsAndPagesStillWork() {
        assumeTrue("Solo con -PliveSources=true", System.getProperty("liveSources") == "true")
        // Lo stesso `sources.json` che legge l'app: una fonte traslocata si prova sul dominio
        // nuovo, una in manutenzione non si prova (l'app non la interroga).
        val overrides = parseSourceOverrides(File(SOURCES_JSON).readText())
        RemoteSourceConfig.replaceForTest(overrides)
        assumeFalse("In manutenzione da sources.json", sourceId in overrides.maintenance)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = MangaSourceRegistry(context).requireById(sourceId)
        val query = QUERIES[sourceId] ?: DEFAULT_QUERY

        val results = source.searchManga(query)
        assertTrue("Ricerca \"$query\": nessun risultato", results.isNotEmpty())

        // Il primo risultato può essere un caso particolare (un oneshot, una serie senza
        // capitoli): basta che uno dei primi si legga fino all'immagine. Se falliscono tutti,
        // vale l'errore del primo.
        val errors = results.take(RESULTS_TO_TRY).mapNotNull { result ->
            runCatching { readFirstPage(context, source, result.mangaUrl) }.exceptionOrNull()
        }
        if (errors.size == minOf(results.size, RESULTS_TO_TRY)) throw errors.first()
    }

    private fun readFirstPage(context: Context, source: MangaSource, mangaUrl: String) {
        val details = source.fetchMangaDetails(mangaUrl)
        assertTrue("Scheda ${details.mangaUrl}: nessun capitolo", details.chapters.isNotEmpty())

        val chapter = details.chapters.first()
        val pages = source.fetchChapterPageImageUrls(chapter.url)
        assertTrue("Capitolo ${chapter.url}: nessuna pagina", pages.isNotEmpty())

        val image = MangaNetworkClient(SharedHttpClient.get(context))
            .fetchBytes(pages.first(), referer = chapter.url)
        assertTrue("Immagine ${pages.first()}: ${image.size} byte", image.size > MIN_IMAGE_BYTES)
    }

    companion object {
        /** I test girano dalla cartella del modulo `app`. */
        private const val SOURCES_JSON = "../sources.json"
        private const val DEFAULT_QUERY = "one piece"
        private const val MIN_IMAGE_BYTES = 1_000
        private const val RESULTS_TO_TRY = 3

        /** Una serie che la fonte ha di sicuro, dove "one piece" non darebbe risultati. */
        private val QUERIES = mapOf(
            MangaSourceIds.ASURA_SCANS to "solo",
        )

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun sources(): List<Array<String>> = MangaSourceCatalog.descriptors.map { arrayOf(it.id) }
    }
}
