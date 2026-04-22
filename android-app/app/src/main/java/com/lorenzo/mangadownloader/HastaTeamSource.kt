package com.lorenzo.mangadownloader

import android.content.Context
import java.math.BigDecimal
import java.net.URI
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl

class HastaTeamSource(
    context: Context,
    networkClient: MangaNetworkClient,
) : BaseMangaSource(context, networkClient) {
    override val descriptor = MangaSourceDescriptor(
        id = MangaSourceIds.HASTA_TEAM,
        displayName = "Hasta Team",
    )

    override val invalidChapterUrlMessage: String =
        "URL manga o capitolo Hasta Team non valido"

    override fun canHandleUrl(url: String): Boolean = handlesUrl(url)

    override fun searchManga(query: String): List<MangaSearchResult> {
        val trimmed = query.trim()
        val results = when {
            trimmed.isEmpty() || trimmed.length < REMOTE_SEARCH_MIN_QUERY_LENGTH -> {
                parseSearchResponse(fetchString(allComicsApiUrl(), jsonHeaders()))
                    .filterByTitle(trimmed)
            }
            else -> {
                val url = BASE_URL.toHttpUrl()
                    .newBuilder()
                    .addPathSegment("api")
                    .addPathSegment("search")
                    .addPathSegment(trimmed)
                    .build()
                    .toString()
                parseSearchResponse(fetchString(url, jsonHeaders()))
            }
        }
        return results.sortedAlphabetically()
    }

    override fun fetchMangaDetails(mangaUrl: String): MangaDetails {
        val canonical = canonicalMangaUrl(mangaUrl)
            ?: throw IllegalArgumentException("URL manga Hasta Team non valido")
        val slug = canonical.substringAfterLast('/')
        val apiUrl = BASE_URL.toHttpUrl()
            .newBuilder()
            .addPathSegment("api")
            .addPathSegment("comics")
            .addPathSegment(slug)
            .build()
            .toString()
        return parseMangaDetails(fetchString(apiUrl, jsonHeaders()))
    }

    override fun canonicalMangaUrl(url: String): String? = canonicalSeriesUrl(url)

    override fun fetchPageImageUrls(chapterUrl: String): List<String> {
        val apiUrl = chapterApiUrl(chapterUrl)
            ?: throw IllegalArgumentException("URL capitolo Hasta Team non valido")
        return parseChapterPageUrls(fetchString(apiUrl, jsonHeaders()))
    }

    private fun jsonHeaders(): Map<String, String> {
        return mapOf("Accept" to "application/json, text/plain, */*")
    }

    companion object {
        private const val BASE_URL = "https://reader.hastateam.com/"
        private const val REMOTE_SEARCH_MIN_QUERY_LENGTH = 3
        private val json = Json { ignoreUnknownKeys = true }
        private val comicsRegex =
            Regex("""^https?://reader\.hastateam\.com/comics/([^/?#]+)""", RegexOption.IGNORE_CASE)
        private val readRegex =
            Regex(
                """^https?://reader\.hastateam\.com/read/([^/?#]+)/([^/?#]+)(?:/vol/(\d+)/ch/(\d+)(?:/sub/(\d+))?)?""",
                RegexOption.IGNORE_CASE,
            )

        fun handlesUrl(url: String): Boolean {
            return canonicalSeriesUrl(url) != null || chapterApiUrl(url) != null
        }

        fun canonicalSeriesUrl(url: String): String? {
            val normalized = url.trim()
            comicsRegex.find(normalized)?.let { match ->
                return "$BASE_URL" + "comics/${match.groupValues[1]}"
            }
            readRegex.find(normalized)?.let { match ->
                return "$BASE_URL" + "comics/${match.groupValues[1]}"
            }
            val apiComicRegex =
                Regex("""^https?://reader\.hastateam\.com/api/comics/([^/?#]+)""", RegexOption.IGNORE_CASE)
            apiComicRegex.find(normalized)?.let { match ->
                return "$BASE_URL" + "comics/${match.groupValues[1]}"
            }
            return null
        }

        fun chapterApiUrl(url: String): String? {
            val normalized = url.trim()
            if (normalized.isBlank()) {
                return null
            }
            if (normalized.startsWith("${BASE_URL}api/read/", ignoreCase = true)) {
                return normalized
            }
            val match = readRegex.find(normalized) ?: return null
            val slug = match.groupValues[1]
            val language = match.groupValues[2]
            val volume = match.groupValues[3]
            val chapter = match.groupValues[4]
            val subchapter = match.groupValues.getOrNull(5).orEmpty()
            if (volume.isBlank() || chapter.isBlank()) {
                return null
            }
            return buildString {
                append(BASE_URL)
                append("api/read/")
                append(slug)
                append('/')
                append(language)
                append("/vol/")
                append(volume)
                append("/ch/")
                append(chapter)
                if (subchapter.isNotBlank()) {
                    append("/sub/")
                    append(subchapter)
                }
            }
        }

        fun parseSearchResponse(raw: String): List<MangaSearchResult> {
            val root = json.parseToJsonElement(raw).jsonObject
            val seen = linkedMapOf<String, MangaSearchResult>()
            root["comics"]?.jsonArray.orEmpty().forEach { element ->
                val item = element.jsonObject
                val title = item["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val mangaUrl = item["url"]?.jsonPrimitive?.contentOrNull
                    ?.let(::absolutize)
                    ?.let(::canonicalSeriesUrl)
                    .orEmpty()
                if (title.isBlank() || mangaUrl.isBlank()) {
                    return@forEach
                }
                seen[mangaUrl] = MangaSearchResult(
                    sourceId = MangaSourceIds.HASTA_TEAM,
                    title = title,
                    mangaUrl = mangaUrl,
                    coverUrl = item["thumbnail"]?.jsonPrimitive?.contentOrNull?.let(::absolutize),
                )
            }
            return seen.values.toList()
        }

        fun parseMangaDetails(raw: String): MangaDetails {
            val root = json.parseToJsonElement(raw).jsonObject
            val comic = root["comic"]?.jsonObject ?: throw IllegalStateException("Risposta manga non valida")
            val title = comic["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val mangaUrl = comic["url"]?.jsonPrimitive?.contentOrNull
                ?.let(::absolutize)
                ?.let(::canonicalSeriesUrl)
                .orEmpty()
            if (title.isBlank() || mangaUrl.isBlank()) {
                throw IllegalStateException("Dettagli manga Hasta Team non validi")
            }
            val chapters = comic["chapters"]?.jsonArray.orEmpty()
                .mapNotNull { element -> parseChapter(element.jsonObject) }
                .sortedBy { it.numberValue }

            return MangaDetails(
                sourceId = MangaSourceIds.HASTA_TEAM,
                title = title,
                coverUrl = comic["thumbnail"]?.jsonPrimitive?.contentOrNull?.let(::absolutize),
                mangaUrl = mangaUrl,
                chapters = chapters,
            )
        }

        fun parseChapterPageUrls(raw: String): List<String> {
            val root = json.parseToJsonElement(raw).jsonObject
            val pages = root["chapter"]
                ?.jsonObject
                ?.get("pages")
                ?.jsonArray
                .orEmpty()
                .mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotBlank) }
                .map(::absolutize)
            if (pages.isEmpty()) {
                throw IllegalStateException("Nessuna immagine trovata per il capitolo")
            }
            return pages
        }

        private fun parseChapter(item: JsonObject): ChapterEntry? {
            val chapterUrl = item["url"]?.jsonPrimitive?.contentOrNull?.let(::absolutize).orEmpty()
            val slug = item["slug_lang_vol_ch_sub"]?.jsonPrimitive?.contentOrNull
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?: chapterUrl.substringAfterLast('/').trim()
            if (chapterUrl.isBlank() || slug.isBlank()) {
                return null
            }
            val numberText = chapterNumberText(
                chapter = item["chapter"]?.jsonPrimitive?.contentOrNull,
                subchapter = item["subchapter"]?.jsonPrimitive?.contentOrNull,
            ) ?: return null
            return ChapterEntry(
                numberText = numberText,
                numberValue = numberText.toBigDecimalOrNull() ?: BigDecimal(numberText),
                url = chapterUrl,
                slug = slug,
            )
        }

        private fun chapterNumberText(
            chapter: String?,
            subchapter: String?,
        ): String? {
            val normalizedChapter = chapter?.trim()?.takeIf(String::isNotBlank) ?: return null
            val normalizedSubchapter = subchapter?.trim()?.takeIf(String::isNotBlank)
            return if (normalizedSubchapter != null && normalizedSubchapter != "0") {
                "${normalizedChapter.toIntOrNull() ?: normalizedChapter}.${normalizedSubchapter.toIntOrNull() ?: normalizedSubchapter}"
            } else {
                normalizedChapter.toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString() ?: normalizedChapter
            }
        }

        private fun absolutize(value: String): String {
            val normalized = value.trim()
            return if (normalized.startsWith("http://", ignoreCase = true) ||
                normalized.startsWith("https://", ignoreCase = true)
            ) {
                normalized
            } else {
                URI(BASE_URL).resolve(normalized).toString()
            }
        }

        private fun allComicsApiUrl(): String {
            return BASE_URL.toHttpUrl()
                .newBuilder()
                .addPathSegment("api")
                .addPathSegment("comics")
                .build()
                .toString()
        }

        fun List<MangaSearchResult>.filterByTitle(query: String): List<MangaSearchResult> {
            val trimmed = query.trim()
            if (trimmed.isEmpty()) {
                return this
            }
            return filter { result -> result.title.contains(trimmed, ignoreCase = true) }
        }

        fun List<MangaSearchResult>.sortedAlphabetically(): List<MangaSearchResult> {
            return sortedBy { result -> result.title.lowercase(Locale.ROOT) }
        }
    }
}
