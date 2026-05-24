package com.lorenzo.mangadownloader

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifica la persistenza/fedeltà della posizione di lettura dopo il refactoring:
 * la posizione è salvata con la stessa chiave (relativePath) per scaricati e streaming,
 * quindi anche lo streaming riprende dalla pagina giusta alla riapertura.
 *
 * Sincrono: il main looper di Robolectric è in pausa, quindi i job di rete di
 * openStreamingReader restano in coda; la parte di stato/persistenza è sincrona.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReaderProgressTest {

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
    fun streamingReader_persistsAndRestoresPageAcrossReopen() {
        val viewModel = createViewModel()
        val details = sampleDetails()
        val chapter = details.chapters.first()

        viewModel.openStreamingReader(details, chapter)
        assertEquals("Prima apertura: nessuna posizione salvata", 0, viewModel.state.value.readerInitialPageIndex)

        viewModel.saveReaderPagePosition(pageIndex = 4, pageCount = 12, allowCompletion = true)
        assertEquals(4, viewModel.state.value.readerChapter?.readerPageIndex)

        // Riapro lo stesso capitolo: deve ripartire da dove ero (pagina 4).
        viewModel.openStreamingReader(details, chapter)
        assertEquals(4, viewModel.state.value.readerInitialPageIndex)
        assertEquals(4, viewModel.state.value.readerChapter?.readerPageIndex)
    }

    @Test
    fun streamingReader_positionSurvivesNewViewModel() {
        createViewModel().also { viewModel ->
            val details = sampleDetails()
            viewModel.openStreamingReader(details, details.chapters.first())
            viewModel.saveReaderPagePosition(pageIndex = 6, pageCount = 20, allowCompletion = true)
        }

        // Nuovo ViewModel (come dopo un riavvio app): la posizione è ancora su disco.
        val recreated = createViewModel()
        val details = sampleDetails()
        recreated.openStreamingReader(details, details.chapters.first())

        assertEquals(6, recreated.state.value.readerInitialPageIndex)
    }

    @Test
    fun streamingReader_marksReadOnLastPage() {
        val viewModel = createViewModel()
        val details = sampleDetails()
        val chapter = details.chapters.first()

        viewModel.openStreamingReader(details, chapter)
        assertFalse(viewModel.state.value.readerChapter?.isRead ?: true)

        viewModel.saveReaderPagePosition(pageIndex = 11, pageCount = 12, allowCompletion = true)

        assertTrue("L'ultima pagina marca il capitolo come letto", viewModel.state.value.readerChapter?.isRead == true)
    }

    @Test
    fun savingBackwardWithoutCompletion_doesNotRewindProgress() {
        val viewModel = createViewModel()
        val details = sampleDetails()
        viewModel.openStreamingReader(details, details.chapters.first())

        viewModel.saveReaderPagePosition(pageIndex = 8, pageCount = 20, allowCompletion = true)
        // Un report "non-completion" su una pagina precedente non deve far arretrare il progresso.
        viewModel.saveReaderPagePosition(pageIndex = 2, pageCount = 20, allowCompletion = false)

        assertEquals(8, viewModel.state.value.readerChapter?.readerPageIndex)
    }

    private fun sampleDetails(): MangaDetails {
        val chapter = ChapterEntry(
            numberText = "1",
            numberValue = BigDecimal("1"),
            url = "https://mangapill.com/chapters/1-1/test-chapter-1",
            slug = "test-chapter-1",
        )
        return MangaDetails(
            sourceId = MangaSourceIds.MANGAPILL,
            title = "Test Manga",
            coverUrl = null,
            mangaUrl = "https://mangapill.com/manga/1/test-manga",
            chapters = listOf(chapter),
        )
    }

    private fun createViewModel(): MangaViewModel =
        MangaViewModel(application, AppUpdateRepository(application))

    companion object {
        private const val PREFS_NAME = "manga_downloader_prefs"
    }
}
