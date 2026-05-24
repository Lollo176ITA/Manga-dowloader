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

/**
 * Copre la cronologia di ricerca aggiunta al ViewModel: registrazione al momento in cui
 * l'utente apre un risultato dalla ricerca, deduplica, limite, persistenza e cancellazione.
 *
 * I test sono sincroni: in Robolectric il main looper è in pausa, quindi i job di rete
 * lanciati da selectManga/onQueryChange restano in coda e non partono — la registrazione
 * dei recenti, invece, avviene in modo sincrono prima del launch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MangaViewModelRecentSearchesTest {

    private lateinit var application: Application

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun recentSearches_emptyByDefault() {
        val viewModel = createViewModel()

        assertEquals(emptyList<String>(), viewModel.state.value.recentSearches)
    }

    @Test
    fun openingResultFromSearchTab_recordsQuery() {
        val viewModel = createViewModel()

        search(viewModel, "Berserk")

        assertEquals(listOf("Berserk"), viewModel.state.value.recentSearches)
    }

    @Test
    fun openingResultWhenNotOnSearchTab_doesNotRecord() {
        val viewModel = createViewModel()
        viewModel.selectTab(AppTab.FAVORITES)

        viewModel.onQueryChange("Naruto")
        viewModel.selectManga(sampleResult("Naruto"))

        assertEquals(emptyList<String>(), viewModel.state.value.recentSearches)
    }

    @Test
    fun blankQuery_notRecorded() {
        val viewModel = createViewModel()

        search(viewModel, "   ")

        assertEquals(emptyList<String>(), viewModel.state.value.recentSearches)
    }

    @Test
    fun mostRecentQueryComesFirst() {
        val viewModel = createViewModel()

        search(viewModel, "One Piece")
        search(viewModel, "Bleach")

        assertEquals(listOf("Bleach", "One Piece"), viewModel.state.value.recentSearches)
    }

    @Test
    fun repeatingQuery_deduplicatesCaseInsensitivelyKeepingLatest() {
        val viewModel = createViewModel()

        search(viewModel, "Naruto")
        search(viewModel, "Bleach")
        search(viewModel, "naruto")

        // La voce ripetuta risale in cima e mantiene la grafia più recente.
        assertEquals(listOf("naruto", "Bleach"), viewModel.state.value.recentSearches)
    }

    @Test
    fun recentSearches_areCappedAtEight() {
        val viewModel = createViewModel()

        (1..10).forEach { index -> search(viewModel, "Manga $index") }

        val recents = viewModel.state.value.recentSearches
        assertEquals(8, recents.size)
        assertEquals("Manga 10", recents.first())
        assertEquals("Manga 3", recents.last())
        assertTrue("Le query più vecchie vengono scartate", recents.none { it == "Manga 1" || it == "Manga 2" })
    }

    @Test
    fun recentSearches_persistAcrossRecreation() {
        createViewModel().also { search(it, "Vinland Saga") }

        val recreated = createViewModel()

        assertEquals(listOf("Vinland Saga"), recreated.state.value.recentSearches)
    }

    @Test
    fun clearRecentSearches_emptiesAndPersists() {
        val viewModel = createViewModel()
        search(viewModel, "Vagabond")

        viewModel.clearRecentSearches()

        assertEquals(emptyList<String>(), viewModel.state.value.recentSearches)
        assertEquals(emptyList<String>(), createViewModel().state.value.recentSearches)
    }

    private fun search(viewModel: MangaViewModel, query: String) {
        viewModel.onQueryChange(query)
        viewModel.selectManga(sampleResult(query))
    }

    private fun sampleResult(query: String): MangaSearchResult {
        val slug = query.trim().ifBlank { "x" }.lowercase().replace(' ', '-')
        return MangaSearchResult(
            sourceId = MangaSourceIds.MANGAPILL,
            title = query.trim().ifBlank { "Senza titolo" },
            mangaUrl = "https://mangapill.com/manga/$slug",
            coverUrl = null,
        )
    }

    private fun createViewModel(): MangaViewModel =
        MangaViewModel(application, AppUpdateRepository(application))

    companion object {
        private const val PREFS_NAME = "manga_downloader_prefs"
    }
}
