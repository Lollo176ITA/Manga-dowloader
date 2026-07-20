package com.lorenzo.mangadownloader

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup

/**
 * Fonte per **Asura Scans** (`asurascans.com`).
 *
 * Il sito è una SPA Astro, ma tutti i dati passano da un'**API JSON pubblica** su
 * `api.asurascans.com`, quindi la fonte è interamente API-based (niente scraping HTML):
 *
 * - ricerca:  `GET /api/search?q=<query>` → `{ data: [ { slug, title, cover, status, ... } ] }`
 * - dettagli: `GET /api/series/<slug>` → `{ series: { title, description, cover, status } }`
 * - capitoli: `GET /api/series/<slug>/chapters` → `{ data: [ { number, slug, is_premium } ] }`
 * - pagine:   `GET /api/series/<slug>/chapters/<numero>` → `{ data: { is_locked, chapter: { pages: [ { url } ] } } }`
 *
 * Lo slug dell'API è quello "pulito" (`chronicles-of-the-demon-faction`); gli URL
 * pubblici del sito hanno in più un suffisso `-<hash8>` costante
 * (`...-f886a8af`). L'API accetta entrambe le forme, ma per un'identità stabile la
 * serie è normalizzata sullo slug pulito: `https://asurascans.com/comics/<slug>`.
 *
 * I capitoli `is_premium` (accesso anticipato a pagamento Asura+) sono **esclusi**
 * dalla lista scaricabile; un capitolo ancora bloccato risponde con `is_locked=true`
 * e [parsePageImageUrls] solleva un errore chiaro.
 */
class AsuraScansSource(
    context: Context,
    networkClient: MangaNetworkClient,
    libraryRepository: LibraryRepository = LibraryRepository(context),
) : BaseMangaSource(context, networkClient, libraryRepository) {
    override val descriptor = MangaSourceDescriptor(
        id = MangaSourceIds.ASURA_SCANS,
        displayName = "Asura Scans",
        shortName = "AS",
        language = MangaSourceLanguage.ENG,
    )

    override val invalidChapterUrlMessage: String =
        "URL manga o capitolo Asura Scans non valido"

    override fun canHandleUrl(url: String): Boolean = handlesUrl(url)

    override fun searchManga(query: String): List<MangaSearchResult> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return emptyList()
        }
        val url = "$API_URL/api/search".toHttpUrl()
            .newBuilder()
            .addQueryParameter("q", trimmed)
            .build()
            .toString()
        return parseSearchResponse(fetchString(url, jsonHeaders()))
    }

    override fun fetchMangaDetails(mangaUrl: String): MangaDetails {
        val canonical = canonicalMangaUrl(mangaUrl)
            ?: throw IllegalArgumentException("URL manga Asura Scans non valido")
        val slug = seriesSlug(canonical)
            ?: throw IllegalArgumentException("URL manga Asura Scans non valido")
        val seriesJson = fetchString("$API_URL/api/series/$slug", jsonHeaders())
        val chaptersJson = fetchString("$API_URL/api/series/$slug/chapters", jsonHeaders())
        return parseMangaDetails(seriesJson, chaptersJson, canonical)
    }

    override fun fetchPageImageUrls(chapterUrl: String): List<String> {
        val ref = parseChapterRef(chapterUrl)
            ?: throw IllegalArgumentException(invalidChapterUrlMessage)
        val json = fetchString("$API_URL/api/series/${ref.slug}/chapters/${ref.number}", jsonHeaders())
        return parsePageImageUrls(json)
    }

    override fun canonicalMangaUrl(url: String): String? = canonicalSeriesUrl(url)

    /**
     * L'API sta dietro Cloudflare come il sito: invia header da browser (lo User-Agent
     * lo mette già [MangaNetworkClient]) più `Referer`/`Origin` del sito, così la
     * richiesta non viene scambiata per una chiamata cross-site anomala.
     */
    private fun jsonHeaders(): Map<String, String> = mapOf(
        "Accept" to "application/json, text/plain, */*",
        "Referer" to "$BASE_URL/",
        "Origin" to BASE_URL,
    )

    companion object {
        private const val BASE_URL = "https://asurascans.com"
        private const val API_URL = "https://api.asurascans.com"
        private val json = Json { ignoreUnknownKeys = true }

        private val seriesRegex =
            Regex("""^https?://asurascans\.com/comics/([^/?#]+)""", RegexOption.IGNORE_CASE)
        private val chapterRegex =
            Regex(
                """^https?://asurascans\.com/comics/([^/?#]+)/chapter/([^/?#]+)""",
                RegexOption.IGNORE_CASE,
            )
        // Suffisso hash costante degli URL pubblici (`-f886a8af`): 8 esadecimali in coda.
        private val hashSuffix = Regex("""-[0-9a-f]{8}$""", RegexOption.IGNORE_CASE)

        /** Riferimento a un capitolo: slug serie (pulito) + numero capitolo. */
        data class ChapterRef(val slug: String, val number: String)

        fun handlesUrl(url: String): Boolean = seriesRegex.containsMatchIn(url.trim())

        fun canonicalSeriesUrl(url: String): String? {
            val match = seriesRegex.find(url.trim()) ?: return null
            val slug = stripHash(match.groupValues[1])
            if (slug.isBlank()) {
                return null
            }
            return "$BASE_URL/comics/$slug"
        }

        fun parseChapterRef(url: String): ChapterRef? {
            val match = chapterRegex.find(url.trim()) ?: return null
            val slug = stripHash(match.groupValues[1])
            val number = match.groupValues[2].trim()
            if (slug.isBlank() || number.isBlank()) {
                return null
            }
            return ChapterRef(slug = slug, number = number)
        }

        private fun seriesSlug(canonicalUrl: String): String? =
            seriesRegex.find(canonicalUrl)?.groupValues?.get(1)?.let(::stripHash)?.takeIf(String::isNotBlank)

        private fun stripHash(slug: String): String = hashSuffix.replace(slug.trim(), "")

        // --- Parsing puro, testabile senza rete ---

        fun parseSearchResponse(raw: String): List<MangaSearchResult> {
            val data = json.parseToJsonElement(raw).jsonObject["data"]?.jsonArray.orEmpty()
            val seen = linkedMapOf<String, MangaSearchResult>()
            for (element in data) {
                val obj = element.jsonObject
                val slug = stripHash(obj["slug"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty())
                val title = obj["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (slug.isBlank() || title.isBlank()) {
                    continue
                }
                val mangaUrl = "$BASE_URL/comics/$slug"
                seen[mangaUrl] = MangaSearchResult(
                    sourceId = MangaSourceIds.ASURA_SCANS,
                    title = title,
                    mangaUrl = mangaUrl,
                    coverUrl = obj["cover"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotBlank),
                )
            }
            return seen.values.toList()
        }

        fun parseMangaDetails(
            seriesJson: String,
            chaptersJson: String,
            mangaUrl: String,
        ): MangaDetails {
            val series = json.parseToJsonElement(seriesJson).jsonObject["series"]?.jsonObject
                ?: throw IllegalStateException("Dettagli serie Asura Scans non validi")
            val title = series["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifBlank { "manga" }
            val cover = series["cover"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotBlank)
            val description = series["description"]?.jsonPrimitive?.contentOrNull
                ?.let { Jsoup.parse(it).text().trim() }
                ?.takeIf(String::isNotBlank)
            val canonical = canonicalSeriesUrl(mangaUrl) ?: mangaUrl
            val chapters = parseChapters(chaptersJson, canonical)
            return MangaDetails(
                sourceId = MangaSourceIds.ASURA_SCANS,
                title = title,
                coverUrl = cover,
                mangaUrl = canonical,
                chapters = chapters,
                description = description,
                status = mangaStatusFromText(series["status"]?.jsonPrimitive?.contentOrNull),
            )
        }

        fun parseChapters(raw: String, seriesUrl: String): List<ChapterEntry> {
            val data = json.parseToJsonElement(raw).jsonObject["data"]?.jsonArray.orEmpty()
            val entries = linkedMapOf<String, ChapterEntry>()
            for (element in data) {
                val obj = element.jsonObject
                val isPremium = obj["is_premium"]?.jsonPrimitive?.booleanOrNull ?: false
                if (isPremium) {
                    // Accesso anticipato Asura+: non scaricabile, la escludiamo dalla lista.
                    continue
                }
                val numberText = obj["number"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotBlank)
                    ?: continue
                val numberValue = DownloadStorage.parseChapterValueOrNull(numberText) ?: continue
                val chapterUrl = "$seriesUrl/chapter/$numberText"
                entries[chapterUrl] = ChapterEntry(
                    numberText = numberText,
                    numberValue = numberValue,
                    url = chapterUrl,
                    slug = obj["slug"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotBlank)
                        ?: "chapter-$numberText",
                )
            }
            if (entries.isEmpty()) {
                throw IllegalStateException("Nessun capitolo trovato per la serie")
            }
            return entries.values.sortedBy { it.numberValue }
        }

        fun parsePageImageUrls(raw: String): List<String> {
            val data = json.parseToJsonElement(raw).jsonObject["data"]?.jsonObject
                ?: throw IllegalStateException("Risposta capitolo Asura Scans non valida")
            val locked = data["is_locked"]?.jsonPrimitive?.booleanOrNull ?: false
            if (locked) {
                throw IllegalStateException("Capitolo bloccato: richiede Asura+ (premium)")
            }
            val pages = data["chapter"]
                ?.jsonObject
                ?.get("pages")
                ?.jsonArray
                .orEmpty()
                .mapNotNull { it.jsonObject["url"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotBlank) }
            if (pages.isEmpty()) {
                throw IllegalStateException("Nessuna immagine trovata per il capitolo")
            }
            return pages
        }
    }
}
