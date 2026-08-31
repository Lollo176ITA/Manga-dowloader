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
 * Il selettore fonte è stato per-serie: le voci di una scheda non devono sopravviverle.
 *
 * Due preferiti diversi ("One Piece Databook" su Mangapill, "One Piece" su TCB): aprire il
 * primo, guardarne le fonti, tornare indietro e aprire il secondo mostrava ancora i mirror
 * del primo — e sceglierne uno riscriveva il binding del *secondo* preferito con l'URL del
 * primo, mandando i tap successivi sulla serie sbagliata.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MangaViewModelSourceOptionsLeakTest {

    private lateinit var application: Application
    private lateinit var prefs: android.content.SharedPreferences

    private val databook = FavoriteManga(
        sourceId = MangaSourceIds.MANGAPILL,
        title = "One Piece Databook",
        mangaUrl = "https://mangapill.com/manga/1/one-piece-databook",
        coverUrl = null,
        seriesKey = "title:one piece databook",
    )
    private val onePiece = FavoriteManga(
        sourceId = MangaSourceIds.TCB_SCANS,
        title = "One Piece",
        mangaUrl = "https://tcbscans.com/mangas/5/one-piece",
        coverUrl = null,
        seriesKey = "title:one piece",
    )

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        prefs = application.getSharedPreferences(SettingsStore.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        FavoritesStore(prefs).persist(listOf(databook, onePiece))
        val links = SeriesLinksStore(prefs)
        links.ensureLink(
            seriesKey = databook.seriesKey,
            title = databook.title,
            coverUrl = null,
            binding = SeriesSourceBinding(databook.sourceId, databook.mangaUrl),
        )
        // Il databook è agganciato anche a un secondo mirror: è ciò che rende visibile il
        // selettore fonte sulla sua scheda.
        links.addBinding(
            databook.seriesKey,
            SeriesSourceBinding(MangaSourceIds.VYMANGA, "https://vymanga.net/manga/one-piece-databook"),
        )
        links.ensureLink(
            seriesKey = onePiece.seriesKey,
            title = onePiece.title,
            coverUrl = null,
            binding = SeriesSourceBinding(onePiece.sourceId, onePiece.mangaUrl),
        )
    }

    private fun createViewModel() = MangaViewModel(application, AppUpdateRepository(application))

    @Test
    fun openingAnotherSeriesAfterBack_doesNotKeepThePreviousSourceOptions() {
        val viewModel = createViewModel()

        viewModel.selectManga(databook.toSearchResult())
        viewModel.loadSourceOptions()
        assertEquals(
            "il selettore del primo elenca i suoi due mirror",
            2,
            viewModel.state.value.sourceOptions.size,
        )

        viewModel.clearSelection()
        viewModel.selectManga(onePiece.toSearchResult())

        val leaked = viewModel.state.value.sourceOptions.filter { it.mangaUrl == databook.mangaUrl }
        assertTrue("le fonti del primo sono rimaste nel selettore del secondo: $leaked", leaked.isEmpty())
    }

    @Test
    fun switchingToASourceThatIsNotLinkedToTheOpenSeries_leavesItsFavoriteBindingAlone() {
        val viewModel = createViewModel()

        viewModel.selectManga(onePiece.toSearchResult())
        // Voce rimasta a schermo da un'altra serie: non deve poter riscrivere questo preferito.
        viewModel.switchSource(
            SourceOptionUi(
                sourceId = databook.sourceId,
                mangaUrl = databook.mangaUrl,
                chapterCount = null,
                lastChapterLabel = null,
                isLoading = false,
            ),
        )

        val stored = FavoritesStore(prefs).read().first { it.title == onePiece.title }
        assertEquals(onePiece.sourceId, stored.sourceId)
        assertEquals(onePiece.mangaUrl, stored.mangaUrl)
    }
}
