package com.lorenzo.mangadownloader.data.sources

import android.content.Context
import com.lorenzo.mangadownloader.app.hidesAdultContent
import com.lorenzo.mangadownloader.data.library.DownloadStorage
import com.lorenzo.mangadownloader.data.library.LibraryRepository
import com.lorenzo.mangadownloader.data.model.ChapterEntry
import com.lorenzo.mangadownloader.data.model.MangaDetails
import com.lorenzo.mangadownloader.data.model.MangaSearchResult
import com.lorenzo.mangadownloader.data.model.mangaStatusFromText
import com.lorenzo.mangadownloader.data.network.MangaNetworkClient
import com.lorenzo.mangadownloader.data.store.SettingsStore
import com.lorenzo.mangadownloader.domain.isAdultGenre
import com.lorenzo.mangadownloader.domain.reading.chapterDateFromIso
import java.math.BigDecimal
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Fonte per **Weeb Central** (`weebcentral.com`), sito HTML server-rendered (htmx) da
 * parsare con jsoup. Nessun Cloudflare challenge: basta una normale richiesta HTTP.
 *
 * - ricerca: `GET /search/data?text=<query>&sort=Best+Match&...` → frammento HTML con un
 *   `<article>` per serie (32 per pagina; si usa solo la prima, come le altre fonti).
 *   Col filtro per adulti attivo si chiede al sito `adult=False` (toglie ciò che marca
 *   adulto, es. Prison School) **e** `excluded_tag` sui generi espliciti ed ecchi: il solo
 *   `adult=False` lascerebbe passare To LOVE-Ru e High-School DxD.
 * - serie:   `/series/<ULID>/<slug>` → `<h1>`, `og:image`, descrizione e stato. Lo slug è
 *   puramente cosmetico (il sito risponde anche a `/series/<ULID>`), quindi l'URL
 *   canonico usa solo l'id: resta stabile se il sito rinomina la serie.
 * - capitoli: la pagina serie ne mostra solo gli ultimi; l'elenco completo è il frammento
 *   `/series/<ULID>/full-chapter-list`, con `<a href="/chapters/<ULID>">` e `<time datetime>`.
 * - pagine:  `/chapters/<ULID>/images?reading_style=long_strip` → tutte le `<img>` del
 *   capitolo in `section#chapter-images`, già alla risoluzione originale della scan (il CDN
 *   non serve varianti ridimensionate) e senza controlli di referer.
 *
 * Come per TCB Scans, l'URL reale di un capitolo (`/chapters/<ULID>`) non contiene l'id
 * della serie, che `canonicalMangaUrl`/`buildDownloadPlan` devono ricavare senza rete.
 * [ChapterEntry.url] usa quindi un URL **sintetico e stabile**
 * `https://weebcentral.com/series/<serieULID>/chapters/<capitoloULID>`, da cui
 * [fetchPageImageUrls] ricava il capitolo reale.
 */
class WeebCentralSource(
    context: Context,
    networkClient: MangaNetworkClient,
    libraryRepository: LibraryRepository = LibraryRepository(context),
) : BaseMangaSource(context, networkClient, libraryRepository) {
    override val descriptor = MangaSourceDescriptor(
        id = MangaSourceIds.WEEB_CENTRAL,
        displayName = "Weeb Central",
        shortName = "WC",
        language = MangaSourceLanguage.ENG,
    )

    override val invalidChapterUrlMessage: String =
        "URL manga o capitolo Weeb Central non valido"

    override fun canHandleUrl(url: String): Boolean = handlesUrl(url)

    override fun searchManga(query: String): List<MangaSearchResult> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return emptyList()
        }
        val url = searchUrl(trimmed, hideAdult = shouldHideAdultResults())
        return parseSearchResults(fetchString(url), url)
    }

    override fun fetchMangaDetails(mangaUrl: String): MangaDetails {
        val canonical = canonicalMangaUrl(mangaUrl)
            ?: throw IllegalArgumentException("URL manga Weeb Central non valido")
        return parseMangaDetails(
            seriesHtml = fetchString(canonical),
            chapterListHtml = fetchString("$canonical/full-chapter-list"),
            mangaUrl = canonical,
        )
    }

    override fun fetchPageImageUrls(chapterUrl: String): List<String> {
        val chapterId = chapterIdFromUrl(chapterUrl)
            ?: throw IllegalArgumentException(invalidChapterUrlMessage)
        return parsePageImageUrls(fetchString(chapterImagesUrl(chapterId)))
    }

    override fun canonicalMangaUrl(url: String): String? = canonicalSeriesUrl(url)

    /** Filtro adulti dell'app (scelta utente o controllo parentale), applicato già lato server. */
    private fun shouldHideAdultResults(): Boolean =
        SettingsStore(context.getSharedPreferences(SettingsStore.PREFS_NAME, Context.MODE_PRIVATE))
            .read()
            .hidesAdultContent()

    companion object {
        private const val BASE_URL = "https://weebcentral.com"

        // Gli id sono ULID: 26 caratteri alfanumerici (Crockford base32).
        private const val ULID = "[0-9A-Za-z]{26}"

        private val seriesRegex =
            Regex("""^https?://(?:www\.)?weebcentral\.com/series/($ULID)(?:[/?#]|$)""", RegexOption.IGNORE_CASE)
        private val syntheticChapterRegex =
            Regex(
                """^https?://(?:www\.)?weebcentral\.com/series/$ULID/chapters/($ULID)(?:[/?#]|$)""",
                RegexOption.IGNORE_CASE,
            )
        private val realChapterRegex =
            Regex("""^https?://(?:www\.)?weebcentral\.com/chapters/($ULID)(?:[/?#]|$)""", RegexOption.IGNORE_CASE)

        /** Tag del sito da escludere col filtro per adulti (nomi esatti del form di ricerca). */
        private val ADULT_EXCLUDED_TAGS = listOf("Adult", "Ecchi", "Hentai", "Smut", "Lolicon", "Shotacon")

        /** "Chapter 12.5" → prefisso "Chapter", numero "12.5". */
        private val chapterLabelRegex = Regex("""^(.*?)\s*(\d+(?:\.\d+)?)\s*$""")

        fun handlesUrl(url: String): Boolean = canonicalSeriesUrl(url) != null

        fun canonicalSeriesUrl(url: String): String? {
            val match = seriesRegex.find(url.trim()) ?: return null
            return "$BASE_URL/series/${match.groupValues[1].uppercase()}"
        }

        /** Id del capitolo da un URL sintetico o dal vero URL del reader `/chapters/<ULID>`. */
        fun chapterIdFromUrl(url: String): String? {
            val normalized = url.trim()
            val match = syntheticChapterRegex.find(normalized) ?: realChapterRegex.find(normalized)
            return match?.groupValues?.get(1)?.uppercase()
        }

        fun syntheticChapterUrl(seriesUrl: String, chapterId: String): String =
            "$seriesUrl/chapters/$chapterId"

        fun chapterImagesUrl(chapterId: String): String =
            "$BASE_URL/chapters/$chapterId/images".toHttpUrl()
                .newBuilder()
                .addQueryParameter("is_prev", "False")
                .addQueryParameter("current_page", "1")
                .addQueryParameter("reading_style", "long_strip")
                .build()
                .toString()

        fun searchUrl(query: String, hideAdult: Boolean): String =
            "$BASE_URL/search/data".toHttpUrl()
                .newBuilder()
                .addQueryParameter("author", "")
                .addQueryParameter("text", query)
                .addQueryParameter("sort", "Best Match")
                .addQueryParameter("order", "Descending")
                .addQueryParameter("official", "Any")
                .addQueryParameter("anime", "Any")
                .addQueryParameter("adult", if (hideAdult) "False" else "Any")
                .addQueryParameter("display_mode", "Full Display")
                .apply {
                    if (hideAdult) {
                        ADULT_EXCLUDED_TAGS.forEach { addQueryParameter("excluded_tag", it) }
                    }
                }
                .build()
                .toString()

        // --- Parsing puro, testabile senza rete ---

        /**
         * Ogni risultato è un `<article>` con il link alla serie ripetuto più volte (copertina
         * desktop, copertina mobile, titolo): si uniscono per URL canonico prendendo il primo
         * titolo testuale e la prima copertina, invece di dipendere dalle classi Tailwind.
         */
        fun parseSearchResults(html: String, baseUrl: String): List<MangaSearchResult> {
            val document = Jsoup.parse(html, baseUrl)
            val titles = linkedMapOf<String, String?>()
            val covers = mutableMapOf<String, String>()
            val adult = mutableSetOf<String>()
            for (anchor in document.select("a[href]")) {
                val mangaUrl = canonicalSeriesUrl(anchor.absUrl("href")) ?: continue
                // "Tag(s): <span>Ecchi,</span> ..." nella card (display_mode=Full Display): rete di
                // sicurezza se il filtro lato server non venisse applicato.
                val card = searchResultCard(anchor) { canonicalSeriesUrl(it.absUrl("href")) }
                if (card.select("span").any { isAdultGenre(it.ownText()) }) {
                    adult += mangaUrl
                }
                val image = anchor.selectFirst("img")
                val title = firstNonBlankTrimmed(
                    anchor.selectFirst("div.truncate")?.text(),
                    anchor.takeIf { image == null }?.text(),
                    image?.attr("alt")?.removeSuffix(" cover"),
                )
                if (titles[mangaUrl] == null) {
                    titles[mangaUrl] = title
                }
                image?.let { firstNonBlankTrimmed(it.absUrl("src"), it.attr("src")) }
                    ?.let { covers.putIfAbsent(mangaUrl, it) }
            }
            return titles.mapNotNull { (mangaUrl, title) ->
                MangaSearchResult(
                    sourceId = MangaSourceIds.WEEB_CENTRAL,
                    title = title ?: return@mapNotNull null,
                    mangaUrl = mangaUrl,
                    coverUrl = covers[mangaUrl],
                    isAdult = mangaUrl in adult,
                )
            }
        }

        fun parseMangaDetails(
            seriesHtml: String,
            chapterListHtml: String,
            mangaUrl: String,
        ): MangaDetails {
            val canonical = canonicalSeriesUrl(mangaUrl)
                ?: throw IllegalArgumentException("URL manga Weeb Central non valido")
            val document: Document = Jsoup.parse(seriesHtml, canonical)
            val title = firstNonBlankTrimmed(
                document.selectFirst("h1")?.text(),
                document.selectFirst("""meta[property="og:title"]""")?.attr("content")
                    ?.substringBefore(" | Weeb Central"),
            ) ?: "manga"
            val cover = firstNonBlankTrimmed(
                document.selectFirst("""meta[property="og:image"]""")?.attr("content"),
            )
            return MangaDetails(
                sourceId = MangaSourceIds.WEEB_CENTRAL,
                title = title,
                coverUrl = cover,
                mangaUrl = canonical,
                chapters = parseChapterList(chapterListHtml, canonical),
                description = parseDescription(document, "p.whitespace-pre-wrap"),
                status = mangaStatusFromText(statusTextNearLabel(document, "Status")),
            )
        }

        fun parseChapterList(html: String, seriesUrl: String): List<ChapterEntry> {
            val document = Jsoup.parse(html, BASE_URL)
            val parsed = mutableListOf<ParsedChapter>()
            val seenIds = mutableSetOf<String>()
            for (anchor in document.select("""a[href*="/chapters/"]""")) {
                val chapterId = chapterIdFromUrl(anchor.absUrl("href")) ?: continue
                if (!seenIds.add(chapterId)) {
                    continue
                }
                val label = firstNonBlankTrimmed(
                    anchor.selectFirst("span.grow > span")?.text(),
                    anchor.ownText(),
                ) ?: continue
                val match = chapterLabelRegex.find(label) ?: continue
                val numberText = match.groupValues[2]
                val numberValue = DownloadStorage.parseChapterValueOrNull(numberText) ?: continue
                parsed += ParsedChapter(
                    chapterId = chapterId,
                    sitePrefix = match.groupValues[1].trim(),
                    numberText = numberText,
                    numberValue = numberValue,
                    publishedAtMillis = chapterDateFromIso(anchor.selectFirst("time")?.attr("datetime")),
                )
            }
            if (parsed.isEmpty()) {
                throw IllegalStateException("Nessun capitolo trovato sulla pagina manga")
            }
            // Il sito elenca dal più recente: si ordina per numero crescente. Se due voci
            // condividono il numero (es. "Chapter 1" e "Volume 1"), la prima tenuta resta il
            // capitolo "principale" e le altre ricevono un variantTag, altrimenti finirebbero
            // sullo stesso chapter_001.cbz.
            val usedNumbers = mutableSetOf<BigDecimal>()
            return parsed
                .sortedWith(compareBy<ParsedChapter> { it.numberValue }.thenBy { !it.isRegularChapter })
                .map { chapter ->
                    val isDuplicate = !usedNumbers.add(chapter.numberValue.stripTrailingZeros())
                    ChapterEntry(
                        numberText = chapter.numberText,
                        numberValue = chapter.numberValue,
                        url = syntheticChapterUrl(seriesUrl, chapter.chapterId),
                        slug = chapter.chapterId,
                        labelPrefix = chapter.appLabelPrefix,
                        variantTag = if (isDuplicate) {
                            chapter.sitePrefix.ifBlank { chapter.chapterId }
                        } else {
                            null
                        },
                        publishedAtMillis = chapter.publishedAtMillis,
                    )
                }
        }

        fun parsePageImageUrls(html: String): List<String> {
            val document = Jsoup.parse(html, BASE_URL)
            val images = document.select("section#chapter-images img")
                .ifEmpty { document.select("img[alt^=Page]") }
            val urls = images
                .mapNotNull { img -> firstNonBlankTrimmed(img.absUrl("src"), img.attr("src")) }
                .filter { it.startsWith("http", ignoreCase = true) && "/static/images/" !in it }
                .distinct()
            if (urls.isEmpty()) {
                throw IllegalStateException("Nessuna immagine trovata per il capitolo")
            }
            return urls
        }

        private data class ParsedChapter(
            val chapterId: String,
            val sitePrefix: String,
            val numberText: String,
            val numberValue: BigDecimal,
            val publishedAtMillis: Long?,
        ) {
            val isRegularChapter: Boolean
                get() = sitePrefix.isBlank() || sitePrefix.equals("Chapter", ignoreCase = true)

            /** Etichetta mostrata in app: "Capitolo" per i capitoli normali, quella del sito altrimenti. */
            val appLabelPrefix: String
                get() = when {
                    isRegularChapter -> "Capitolo"
                    sitePrefix.equals("Volume", ignoreCase = true) -> "Volume"
                    sitePrefix.equals("Episode", ignoreCase = true) -> "Episodio"
                    else -> sitePrefix
                }
        }
    }
}
