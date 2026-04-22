package com.lorenzo.mangadownloader

import android.content.Context
import android.os.Environment
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.math.BigDecimal
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class ChapterEntry(
    val numberText: String,
    val numberValue: BigDecimal,
    val url: String,
    val slug: String,
) {
    fun displayNumber(): String = numberValue.stripTrailingZeros().toPlainString()
}

data class DownloadPlan(
    val seriesTitle: String,
    val outputDir: File,
    val chapters: List<ChapterEntry>,
    val startChapterLabel: String,
)

data class MangaSearchResult(
    val title: String,
    val mangaUrl: String,
    val coverUrl: String?,
)

data class MangaDetails(
    val title: String,
    val coverUrl: String?,
    val mangaUrl: String,
    val chapters: List<ChapterEntry>,
)

enum class DownloadResult {
    DOWNLOADED,
    SKIPPED_EXISTING,
}

class MangapillClient(
    private val context: Context,
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()

    fun searchManga(query: String): List<MangaSearchResult> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return emptyList()
        }
        val url = "https://mangapill.com/search".toHttpUrl()
            .newBuilder()
            .addQueryParameter("q", trimmed)
            .build()
            .toString()

        val document = fetchDocument(url)
        val accumulated = linkedMapOf<String, Pair<String?, String?>>()

        for (anchor in document.select("""a[href^="/manga/"]""")) {
            val href = anchor.attr("href").trim()
            val mangaUrl = canonicalMangaUrl(absolutize(url, href)) ?: continue

            val image = anchor.selectFirst("img")
            val coverRaw = image?.let { img ->
                sequenceOf(img.attr("data-src"), img.attr("src"))
                    .map { it.trim() }
                    .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
            }
            val cover = coverRaw?.let { absolutize(url, it) }

            val anchorText = anchor.text().trim()
            val titleCandidate = firstNonBlank(
                image?.attr("alt"),
                anchor.attr("title"),
                anchorText.takeIf { it.isNotBlank() },
            )

            val prior = accumulated[mangaUrl]
            accumulated[mangaUrl] = Pair(
                prior?.first ?: titleCandidate,
                prior?.second ?: cover,
            )
        }

        return accumulated.entries.mapNotNull { (mangaUrl, pair) ->
            val (titleRaw, cover) = pair
            val title = titleRaw?.trim().orEmpty().ifBlank {
                mangaUrl.substringAfterLast('/').replace('-', ' ').trim()
            }
            if (title.isBlank()) null
            else MangaSearchResult(title = title, mangaUrl = mangaUrl, coverUrl = cover)
        }
    }

    fun fetchMangaDetails(mangaUrl: String): MangaDetails {
        val canonical = canonicalMangaUrl(mangaUrl)
            ?: throw IllegalArgumentException("URL manga non valido")
        val document = fetchDocument(canonical)
        val title = document.selectFirst("h1")?.text()?.trim().orEmpty().ifBlank { "manga" }
        val cover = findCoverImage(document)?.let { absolutize(canonical, it) }
        val chapters = fetchChapterEntries(document, canonical)
        return MangaDetails(
            title = title,
            coverUrl = cover,
            mangaUrl = canonical,
            chapters = chapters,
        )
    }

    fun buildDownloadPlan(firstChapterUrl: String): DownloadPlan {
        val normalizedFirstUrl = firstChapterUrl.trim()
        val startChapter = parseChapterNumber(
            Regex("""chapter-(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
                .find(normalizedFirstUrl)
                ?.groupValues
                ?.getOrNull(1)
                ?: throw IllegalArgumentException("URL capitolo non valido"),
        )

        val canonical = canonicalMangaUrl(normalizedFirstUrl)
            ?: throw IllegalArgumentException("Per ora l'app supporta solo URL capitolo di Mangapill")
        val document = fetchDocument(canonical)
        val seriesTitle = document.selectFirst("h1")?.text()?.trim().orEmpty().ifBlank { "manga" }
        val allChapters = fetchChapterEntries(document, canonical)
        val selected = allChapters.filter { it.numberValue >= startChapter }

        if (selected.isEmpty()) {
            throw IllegalStateException("Nessun capitolo trovato da ${startChapter.stripTrailingZeros().toPlainString()} in poi")
        }

        val root = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: throw IllegalStateException("Cartella download dell'app non disponibile")
        val outputDir = File(root, "MangaDownloader/${safeFilename(seriesTitle)}")
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }

        return DownloadPlan(
            seriesTitle = seriesTitle,
            outputDir = outputDir,
            chapters = selected,
            startChapterLabel = startChapter.stripTrailingZeros().toPlainString(),
        )
    }

    suspend fun downloadChapterAsCbz(
        chapter: ChapterEntry,
        outputDir: File,
        onPageProgress: suspend (pageIndex: Int, pageTotal: Int) -> Unit,
    ): DownloadResult {
        val outputFile = buildChapterOutputFile(outputDir, chapter)
        if (outputFile.exists()) {
            return DownloadResult.SKIPPED_EXISTING
        }

        val tempFile = File(outputDir, "${outputFile.name}.part")
        if (tempFile.exists()) {
            tempFile.delete()
        }

        val pageUrls = fetchPageImageUrls(chapter.url)
        ZipOutputStream(BufferedOutputStream(FileOutputStream(tempFile))).use { zip ->
            for ((index, pageUrl) in pageUrls.withIndex()) {
                val imageBytes = fetchBytes(pageUrl, referer = chapter.url)
                val extension = extractImageExtension(pageUrl)
                zip.putNextEntry(ZipEntry("${(index + 1).toString().padStart(3, '0')}.$extension"))
                zip.write(imageBytes)
                zip.closeEntry()
                onPageProgress(index + 1, pageUrls.size)
            }
        }

        if (!tempFile.renameTo(outputFile)) {
            tempFile.delete()
            throw IOException("Impossibile finalizzare ${outputFile.name}")
        }
        return DownloadResult.DOWNLOADED
    }

    private fun buildChapterOutputFile(outputDir: File, chapter: ChapterEntry): File {
        val chapterLabel = chapter.displayNumber()
        val padded = if (chapterLabel.all(Char::isDigit)) {
            chapterLabel.padStart(3, '0')
        } else {
            chapterLabel
        }
        return File(outputDir, "chapter_${safeFilename(padded)}.cbz")
    }

    private fun fetchChapterEntries(document: Document, mangaUrl: String): List<ChapterEntry> {
        val entries = linkedMapOf<String, ChapterEntry>()

        for (link in document.select("""#chapters a[href^="/chapters/"]""")) {
            val href = link.attr("href").trim()
            if (href.isBlank()) {
                continue
            }
            val chapterUrl = absolutize(mangaUrl, href)
            val title = link.attr("title").trim().ifBlank { link.text().trim() }
            val numberText = Regex("""chapter\s+(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
                .find(title)
                ?.groupValues
                ?.getOrNull(1)
                ?: Regex("""chapter-(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
                    .find(chapterUrl)
                    ?.groupValues
                    ?.getOrNull(1)
                ?: continue

            entries[chapterUrl] = ChapterEntry(
                numberText = numberText,
                numberValue = parseChapterNumber(numberText),
                url = chapterUrl,
                slug = chapterUrl.substringAfterLast('/'),
            )
        }

        if (entries.isEmpty()) {
            throw IllegalStateException("Nessun capitolo trovato sulla pagina manga")
        }

        return entries.values.sortedBy { it.numberValue }
    }

    private fun fetchPageImageUrls(chapterUrl: String): List<String> {
        val document = fetchDocument(chapterUrl)
        val selectors = listOf(
            "chapter-page img.js-page",
            "chapter-page picture img",
            "img.page-image",
        )

        val ordered = linkedSetOf<String>()
        for (selector in selectors) {
            for (image in document.select(selector)) {
                val src = image.attr("data-src").ifBlank { image.attr("src") }.trim()
                if (src.isNotBlank()) {
                    ordered.add(absolutize(chapterUrl, src))
                }
            }
        }

        if (ordered.isEmpty()) {
            throw IllegalStateException("Nessuna immagine trovata per il capitolo")
        }

        return ordered.toList()
    }

    private fun fetchDocument(url: String): Document {
        val html = fetchString(url)
        return Jsoup.parse(html, url)
    }

    private fun fetchString(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
            .header("Accept-Language", "it,en;q=0.8")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} su $url")
            }
            return response.body?.string() ?: throw IOException("Risposta vuota da $url")
        }
    }

    private fun fetchBytes(url: String, referer: String): ByteArray {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", referer)
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} scaricando $url")
            }
            return response.body?.bytes() ?: throw IOException("Immagine vuota da $url")
        }
    }

    private fun canonicalMangaUrl(url: String): String? {
        val normalized = url.trim()
        val mangaMatch = Regex("""^https?://mangapill\.com/manga/([^/?#]+)(?:/([^/?#]+))?""", RegexOption.IGNORE_CASE)
            .find(normalized)
        if (mangaMatch != null) {
            val id = mangaMatch.groupValues[1]
            val slug = mangaMatch.groupValues.getOrNull(2).orEmpty()
            return if (slug.isBlank()) "https://mangapill.com/manga/$id" else "https://mangapill.com/manga/$id/$slug"
        }
        val chapterMatch = Regex("""^https?://mangapill\.com/chapters/([^/]+)/""", RegexOption.IGNORE_CASE)
            .find(normalized)
        if (chapterMatch != null) {
            val mangaId = chapterMatch.groupValues[1].substringBefore('-')
            if (mangaId.isNotBlank()) {
                return "https://mangapill.com/manga/$mangaId"
            }
        }
        return null
    }

    private fun findCoverImage(document: Document): String? {
        val candidates = listOf(
            "figure img",
            "picture img",
            "div.flex img",
            "img",
        )
        for (selector in candidates) {
            for (image in document.select(selector)) {
                val src = image.attr("data-src").ifBlank { image.attr("src") }.trim()
                if (src.isBlank()) {
                    continue
                }
                if (looksLikeCover(src, image)) {
                    return src
                }
            }
        }
        return null
    }

    private fun looksLikeCover(src: String, image: Element): Boolean {
        val lowered = src.lowercase(Locale.US)
        if ("mangapill.com" in lowered || "cdn.mangapill" in lowered || "cover" in lowered) {
            return true
        }
        val alt = image.attr("alt").lowercase(Locale.US)
        return alt.isNotBlank() && alt != "logo"
    }

    private fun firstNonBlank(vararg values: String?): String? {
        for (v in values) {
            if (!v.isNullOrBlank()) {
                return v
            }
        }
        return null
    }

    private fun absolutize(baseUrl: String, value: String): String {
        return URI(baseUrl).resolve(value).toString()
    }

    private fun parseChapterNumber(text: String): BigDecimal {
        val cleaned = Regex("""\d+(?:\.\d+)?""").find(text)?.value
            ?: throw IllegalArgumentException("Numero capitolo non valido: $text")
        return cleaned.toBigDecimal()
    }

    private fun extractImageExtension(url: String): String {
        val raw = url.substringBefore('?').substringAfterLast('.', "jpg")
        val cleaned = raw.lowercase(Locale.US).filter { it.isLetterOrDigit() }
        return if (cleaned.isBlank()) "jpg" else cleaned
    }

    private fun safeFilename(input: String): String {
        return input.replace(Regex("""[^A-Za-z0-9._-]+"""), "_").trim('_').ifBlank { "manga" }
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    }
}
