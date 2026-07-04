package com.lorenzo.mangadownloader

import android.app.Application
import android.content.Context
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class StreamingReadStateTest {

    private lateinit var application: Application

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        application.getSharedPreferences(LIBRARY_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        application.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?.resolve(DownloadStorage.LIBRARY_FOLDER_NAME)
            ?.takeIf(File::exists)
            ?.deleteRecursively()
    }

    @Test
    fun markStreamingChapterRead_persistsStableChapterId() {
        val repository = LibraryRepository(application)
        val chapter = chapter("1")

        repository.markStreamingChapterRead(
            sourceId = MangaSourceIds.MANGAPILL,
            mangaUrl = MANGA_URL,
            chapter = chapter,
        )

        assertEquals(
            setOf(chapter.stableId()),
            repository.streamingReadChapterIds(MangaSourceIds.MANGAPILL, MANGA_URL),
        )
    }

    @Test
    fun prepareSeriesStorage_mergesStreamingReadIdsIntoDownloadedMetadataAndScan() {
        val readChapter = chapter("1")
        val unreadChapter = chapter("2")
        val repository = LibraryRepository(application)
        repository.markStreamingChapterRead(
            sourceId = MangaSourceIds.MANGAPILL,
            mangaUrl = MANGA_URL,
            chapter = readChapter,
        )
        val source = TestMangaSource(
            context = application,
            details = MangaDetails(
                sourceId = MangaSourceIds.MANGAPILL,
                title = "Streaming Read Manga",
                coverUrl = null,
                mangaUrl = MANGA_URL,
                chapters = listOf(readChapter, unreadChapter),
            ),
        )

        val plan = source.buildDownloadPlan(readChapter.url, unreadChapter.url)
        source.prepareSeriesStorage(plan)
        plan.chapters.forEach { selected ->
            File(plan.outputDir, DownloadStorage.buildChapterFileName(selected)).writeText(selected.displayLabel())
        }

        val metadata = SeriesMetadataJson.read(File(plan.outputDir, DownloadStorage.SERIES_METADATA_FILE_NAME))
        val scanned = LibraryScanner.scan(
            root = DownloadStorage.libraryRoot(application),
            isRead = { false },
        ).single()

        assertNotNull(metadata)
        assertTrue(readChapter.stableId() in metadata!!.readChapterIds)
        assertTrue(scanned.chapters.first { it.numberText == "1" }.isRead)
        assertFalse(scanned.chapters.first { it.numberText == "2" }.isRead)
    }

    @Test
    fun completingStreamingReader_marksCurrentChapterAndSelectedListRead() {
        val chapter = chapter("1")
        val viewModel = MangaViewModel(application, AppUpdateRepository(application))
        val details = MangaDetails(
            sourceId = MangaSourceIds.MANGAPILL,
            title = "Streaming Read Manga",
            coverUrl = null,
            mangaUrl = MANGA_URL,
            chapters = listOf(chapter),
        )

        viewModel.openStreamingReader(details, chapter)
        viewModel.saveReaderPagePosition(pageIndex = 1, pageCount = 2, allowCompletion = true)

        val state = viewModel.state.value
        assertTrue(state.readerChapter?.isRead == true)
        assertTrue(chapter.stableId() in state.selectedMangaReadChapterIds)
        assertEquals(
            setOf(chapter.stableId()),
            LibraryRepository(application).streamingReadChapterIds(MangaSourceIds.MANGAPILL, MANGA_URL),
        )
    }

    private fun chapter(number: String): ChapterEntry {
        return ChapterEntry(
            numberText = number,
            numberValue = BigDecimal(number),
            url = "$MANGA_URL/chapter-$number",
            slug = "chapter-$number",
        )
    }

    private fun ChapterEntry.stableId(): String {
        return DownloadStorage.stableChapterId(
            numberText = displayNumber(),
            url = url,
            slug = slug,
        )
    }

    private class TestMangaSource(
        context: Context,
        private val details: MangaDetails,
    ) : BaseMangaSource(
        context = context,
        networkClient = MangaNetworkClient(SharedHttpClient.get(context)),
    ) {
        override val descriptor: MangaSourceDescriptor = MangaSourceDescriptor(
            id = MangaSourceIds.MANGAPILL,
            displayName = "Test",
            shortName = "T",
            language = MangaSourceLanguage.ENG,
        )
        override val invalidChapterUrlMessage: String = "Invalid test URL"

        override fun canHandleUrl(url: String): Boolean = url.startsWith(MANGA_URL)

        override fun searchManga(query: String): List<MangaSearchResult> = emptyList()

        override fun fetchMangaDetails(mangaUrl: String): MangaDetails = details

        override fun canonicalMangaUrl(url: String): String? = MANGA_URL

        override fun fetchPageImageUrls(chapterUrl: String): List<String> = emptyList()
    }

    private companion object {
        const val LIBRARY_PREFS_NAME = "manga_library_prefs"
        const val MANGA_URL = "https://mangapill.com/manga/1/streaming-read"
    }
}
