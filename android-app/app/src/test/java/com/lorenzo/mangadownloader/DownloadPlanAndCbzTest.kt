package com.lorenzo.mangadownloader

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigDecimal
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Copre i rami d'errore di `BaseMangaSource.buildDownloadPlan` (URL non riconosciuto,
 * capitolo iniziale/finale assente, intervallo invertito) e i due esiti di
 * `downloadChapterAsCbz` (zip prodotto, file già esistente → skip).
 *
 * Niente rete: il `MangaNetworkClient` è costruito con un `OkHttpClient` il cui
 * interceptor o restituisce byte finti, o fallisce rumorosamente se contattato.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DownloadPlanAndCbzTest {

    private lateinit var application: Application

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        // buildDownloadPlan crea cartelle sotto la library root: parti pulito.
        application.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?.resolve(DownloadStorage.LIBRARY_FOLDER_NAME)
            ?.takeIf(File::exists)
            ?.deleteRecursively()
    }

    // ---- buildDownloadPlan ----

    @Test
    fun buildDownloadPlan_throwsWhenChapterUrlNotRecognized() {
        val source = testSource(canonical = null)

        val error = assertThrows(IllegalArgumentException::class.java) {
            source.buildDownloadPlan("non-un-url", null)
        }
        assertEquals("Invalid test URL", error.message)
    }

    @Test
    fun buildDownloadPlan_throwsWhenFirstChapterMissing() {
        val source = testSource(chapters = listOf(chapter("1"), chapter("2")))

        val error = assertThrows(IllegalStateException::class.java) {
            source.buildDownloadPlan(chapterUrl("99"), null)
        }
        assertTrue(error.message!!.contains("iniziale"))
    }

    @Test
    fun buildDownloadPlan_throwsWhenLastChapterMissing() {
        val source = testSource(chapters = listOf(chapter("1"), chapter("2")))

        val error = assertThrows(IllegalStateException::class.java) {
            source.buildDownloadPlan(chapterUrl("1"), chapterUrl("99"))
        }
        assertTrue(error.message!!.contains("finale non trovato"))
    }

    @Test
    fun buildDownloadPlan_throwsWhenRangeInverted() {
        val source = testSource(chapters = listOf(chapter("1"), chapter("2"), chapter("3")))

        val error = assertThrows(IllegalStateException::class.java) {
            source.buildDownloadPlan(chapterUrl("3"), chapterUrl("1"))
        }
        assertTrue(error.message!!.contains("successivo o uguale"))
    }

    @Test
    fun buildDownloadPlan_selectsInclusiveRangeAndKeepsTotalCount() {
        val source = testSource(chapters = listOf(chapter("1"), chapter("2"), chapter("3")))

        val plan = source.buildDownloadPlan(chapterUrl("1"), chapterUrl("2"))

        assertEquals(listOf("1", "2"), plan.chapters.map { it.numberText })
        assertEquals(3, plan.totalChapterCount)
        assertEquals(SERIES_TITLE, plan.seriesTitle)
        assertTrue(plan.outputDir.exists())
    }

    @Test
    fun buildDownloadPlan_defaultsToLastChapterWhenNoEndGiven() {
        val source = testSource(chapters = listOf(chapter("1"), chapter("2"), chapter("3")))

        val plan = source.buildDownloadPlan(chapterUrl("2"), null)

        assertEquals(listOf("2", "3"), plan.chapters.map { it.numberText })
    }

    // ---- downloadChapterAsCbz ----

    @Test
    fun downloadChapterAsCbz_archivesNormalPagesByteForByteInDownloadOrderWithoutRecompression() {
        val firstPng = pngBytes(width = 8, height = 8, color = Color.rgb(32, 64, 128))
        val secondPng = pngBytes(width = 9, height = 7, color = Color.rgb(96, 48, 16))
        val source = testSource(
            pageUrls = listOf("https://img.test/1.png", "https://img.test/2.png"),
            networkClient = fakeImageClient(
                mapOf(
                    "/1.png" to firstPng,
                    "/2.png" to secondPng,
                ),
            ),
        )
        val outputDir = freshOutputDir()
        val chapter = chapter("1")

        val result = runBlocking {
            source.downloadChapterAsCbz(chapter, outputDir, pageConcurrency = 2) { _, _ -> }
        }

        assertEquals(DownloadResult.DOWNLOADED, result)
        val cbz = File(outputDir, DownloadStorage.buildChapterFileName(chapter))
        assertTrue(cbz.exists())
        ZipFile(cbz).use { zip ->
            val entries = zip.entries().toList()
            assertEquals(listOf("001.png", "002.png"), entries.map(ZipEntry::getName))
            entries.zip(listOf(firstPng, secondPng)).forEach { (entry, expectedBytes) ->
                // Le immagini sono già compresse: DEFLATED a livello zero evita una seconda
                // compressione e non richiede il prepass CRC necessario per le entry STORED.
                assertEquals(ZipEntry.DEFLATED, entry.method)
                assertTrue(entry.compressedSize >= entry.size)
                zip.getInputStream(entry).use { input ->
                    assertArrayEquals(expectedBytes, input.readBytes())
                }
            }
        }
        // Nessun residuo temporaneo dopo la finalizzazione.
        assertFalse(File(outputDir, ".${cbz.nameWithoutExtension}_pages").exists())
        assertFalse(File(outputDir, "${cbz.name}.part").exists())
    }

    @Test
    fun downloadChapterAsCbz_splitsTallPageAndReportsOriginalPageProgress() {
        val source = testSource(
            pageUrls = listOf("https://img.test/tall.png"),
            networkClient = fakeImageClient(
                pngBytes(width = 8, height = TallPageNormalizationMinHeightPx),
            ),
        )
        val outputDir = freshOutputDir()
        val chapter = chapter("1")
        val progress = mutableListOf<Pair<Int, Int>>()

        val result = runBlocking {
            source.downloadChapterAsCbz(chapter, outputDir, pageConcurrency = 1) { completed, total ->
                progress += completed to total
            }
        }

        assertEquals(DownloadResult.DOWNLOADED, result)
        assertEquals(listOf(1 to 1), progress)
        val cbz = File(outputDir, DownloadStorage.buildChapterFileName(chapter))
        ZipFile(cbz).use { zip ->
            val names = zip.entries().toList().map { it.name }
            assertEquals(
                listOf("001__part_0001.png", "001__part_0002.png"),
                names,
            )
        }
        assertFalse(File(outputDir, ".${cbz.nameWithoutExtension}_pages").exists())
        assertFalse(File(outputDir, "${cbz.name}.part").exists())
    }

    @Test
    fun downloadChapterAsCbz_reportsDownloadCompletionBeforeImageProcessing() {
        val image = pngBytes(width = 8, height = 8)
        val source = testSource(
            pageUrls = listOf("https://img.test/1.png", "https://img.test/2.png"),
            networkClient = fakeImageClient(image),
        )
        val events = mutableListOf<String>()

        runBlocking {
            source.downloadChapterAsCbz(
                chapter = chapter("1"),
                outputDir = freshOutputDir(),
                pageConcurrency = 1,
                onProcessingProgress = { completed, total ->
                    events += "processing:$completed/$total"
                },
                onPageProgress = { completed, total ->
                    events += "download:$completed/$total"
                },
            )
        }

        assertEquals(
            listOf(
                "download:1/2",
                "download:2/2",
                "processing:0/2",
                "processing:1/2",
                "processing:2/2",
            ),
            events,
        )
    }

    @Test
    fun downloadChapterAsCbz_doesNotNormalizeVyMangaPages() {
        val original = pngBytes(width = 8, height = TallPageNormalizationMinHeightPx)
        val source = testSource(
            sourceId = MangaSourceIds.VYMANGA,
            pageUrls = listOf("https://img.test/tall.png"),
            networkClient = fakeImageClient(original),
        )
        val outputDir = freshOutputDir()
        val chapter = chapter("1")
        val downloadProgress = mutableListOf<Pair<Int, Int>>()
        val processingProgress = mutableListOf<Pair<Int, Int>>()

        runBlocking {
            source.downloadChapterAsCbz(
                chapter = chapter,
                outputDir = outputDir,
                pageConcurrency = 1,
                onProcessingProgress = { completed, total ->
                    processingProgress += completed to total
                },
                onPageProgress = { completed, total ->
                    downloadProgress += completed to total
                },
            )
        }

        assertEquals(listOf(1 to 1), downloadProgress)
        assertTrue(processingProgress.isEmpty())
        val cbz = File(outputDir, DownloadStorage.buildChapterFileName(chapter))
        ZipFile(cbz).use { zip ->
            val entries = zip.entries().toList()
            assertEquals(listOf("001.png"), entries.map { it.name })
            zip.getInputStream(entries.single()).use { input ->
                assertArrayEquals(original, input.readBytes())
            }
        }
    }

    @Test
    fun downloadChapterAsCbz_skipsWhenFileAlreadyExists() {
        val source = testSource(
            pageUrls = listOf("https://img.test/1.png"),
            networkClient = failingClient(),
        )
        val outputDir = freshOutputDir()
        val chapter = chapter("1")
        // Simula un capitolo già scaricato: non deve riscaricare nulla.
        File(outputDir, DownloadStorage.buildChapterFileName(chapter)).writeText("già qui")

        val result = runBlocking {
            source.downloadChapterAsCbz(chapter, outputDir, pageConcurrency = 1) { _, _ -> }
        }

        assertEquals(DownloadResult.SKIPPED_EXISTING, result)
    }

    @Test
    fun downloadChapterAsCbz_cleansTemporaryFilesWhenPageDownloadFails() {
        val source = testSource(
            pageUrls = listOf("https://img.test/1.png"),
            networkClient = failingClient(),
        )
        val outputDir = freshOutputDir()
        val chapter = chapter("1")
        val outputFile = File(outputDir, DownloadStorage.buildChapterFileName(chapter))

        assertThrows(AssertionError::class.java) {
            runBlocking {
                source.downloadChapterAsCbz(chapter, outputDir, pageConcurrency = 1) { _, _ -> }
            }
        }

        assertFalse(outputFile.exists())
        assertFalse(File(outputDir, ".${outputFile.nameWithoutExtension}_pages").exists())
        assertFalse(File(outputDir, "${outputFile.name}.part").exists())
    }

    // ---- spazio su disco ----

    @Test
    fun hasEnoughFreeSpace_comparesAgainstThreshold() {
        assertTrue(DownloadStorage.hasEnoughFreeSpace(DownloadStorage.MIN_FREE_SPACE_BYTES))
        assertTrue(DownloadStorage.hasEnoughFreeSpace(DownloadStorage.MIN_FREE_SPACE_BYTES + 1))
        assertFalse(DownloadStorage.hasEnoughFreeSpace(DownloadStorage.MIN_FREE_SPACE_BYTES - 1))
        assertFalse(DownloadStorage.hasEnoughFreeSpace(0))
    }

    @Test
    fun downloadChapterAsCbz_throwsInsufficientStorageWhenDiskNearlyFull() {
        val source = testSource(
            pageUrls = listOf("https://img.test/1.png"),
            networkClient = failingClient(), // la rete non va contattata: il check precede il download
            freeSpace = 1L * 1024 * 1024, // 1 MB, sotto la soglia
        )
        val outputDir = freshOutputDir()
        val chapter = chapter("1")

        val error = assertThrows(InsufficientStorageException::class.java) {
            runBlocking {
                source.downloadChapterAsCbz(chapter, outputDir, pageConcurrency = 1) { _, _ -> }
            }
        }

        assertTrue(error.message!!.contains("Spazio insufficiente"))
        assertFalse(File(outputDir, DownloadStorage.buildChapterFileName(chapter)).exists())
    }

    // ---- helper ----

    private fun freshOutputDir(): File =
        File(application.cacheDir, "cbz-test-${System.nanoTime()}").apply {
            deleteRecursively()
            mkdirs()
        }

    private fun chapterUrl(number: String) = "$MANGA_URL/chapter-$number"

    private fun chapter(number: String) = ChapterEntry(
        numberText = number,
        numberValue = BigDecimal(number),
        url = chapterUrl(number),
        slug = "chapter-$number",
    )

    private fun testSource(
        sourceId: String = MangaSourceIds.MANGAPILL,
        chapters: List<ChapterEntry> = emptyList(),
        canonical: String? = MANGA_URL,
        pageUrls: List<String> = emptyList(),
        networkClient: MangaNetworkClient = failingClient(),
        freeSpace: Long = Long.MAX_VALUE,
    ): TestMangaSource {
        val details = MangaDetails(
            sourceId = sourceId,
            title = SERIES_TITLE,
            coverUrl = null,
            mangaUrl = MANGA_URL,
            chapters = chapters,
        )
        return TestMangaSource(
            application,
            networkClient,
            details,
            canonical,
            pageUrls,
            freeSpace,
            sourceId,
        )
    }

    private fun fakeImageClient(bytes: ByteArray) = fakeImageClient(
        mapOf("*" to bytes),
    )

    private fun fakeImageClient(bytesByPath: Map<String, ByteArray>) = MangaNetworkClient(
        OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val bytes = bytesByPath[chain.request().url.encodedPath]
                        ?: bytesByPath["*"]
                        ?: error("Nessuna risposta finta per ${chain.request().url.encodedPath}")
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(bytes.toResponseBody("image/png".toMediaType()))
                        .build()
                },
            )
            .build(),
    )

    private fun pngBytes(
        width: Int,
        height: Int,
        color: Int = Color.rgb(32, 64, 128),
    ): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(color)
        }
        return try {
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun failingClient() = MangaNetworkClient(
        OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { throw AssertionError("La rete non deve essere contattata in questo test") },
            )
            .build(),
    )

    private class TestMangaSource(
        context: Context,
        networkClient: MangaNetworkClient,
        private val details: MangaDetails,
        private val canonical: String?,
        private val pageUrls: List<String>,
        private val freeSpace: Long,
        sourceId: String,
    ) : BaseMangaSource(context, networkClient) {
        override val descriptor =
            MangaSourceDescriptor(sourceId, "Test", "T", MangaSourceLanguage.ENG)
        override val invalidChapterUrlMessage = "Invalid test URL"
        override fun canHandleUrl(url: String) = canonical != null
        override fun searchManga(query: String): List<MangaSearchResult> = emptyList()
        override fun fetchMangaDetails(mangaUrl: String) = details
        override fun canonicalMangaUrl(url: String): String? = canonical
        override fun fetchPageImageUrls(chapterUrl: String): List<String> = pageUrls
        override fun availableSpaceBytes(dir: File): Long = freeSpace
    }

    private companion object {
        const val MANGA_URL = "https://mangapill.com/manga/42/test-series"
        const val SERIES_TITLE = "Test Series"
    }
}
