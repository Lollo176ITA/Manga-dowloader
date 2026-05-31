package com.lorenzo.mangadownloader

import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Un manga come lo descrive AniList: **solo metadati** (titolo, copertina, generi, voto, trama,
 * stato). AniList è un catalogo, non una fonte da cui scaricare: per leggere/scaricare il titolo
 * va ri-cercato sulle fonti reali ([MangaSource]) tramite [searchTitle]. Per questo [AniListManga]
 * vive fuori dal [MangaSourceRegistry].
 */
data class AniListManga(
    val id: Int,
    val titleRomaji: String?,
    val titleEnglish: String?,
    val coverUrl: String?,
    val genres: List<String>,
    val averageScore: Int?,
    val description: String?,
    val status: MangaPublicationStatus,
) {
    /**
     * Titolo da usare per cercare sulle fonti: prima l'inglese (più comune sui siti EN tipo
     * Mangapill), poi il romaji come fallback. `null` se entrambi mancano.
     */
    fun searchTitle(): String? = firstNonBlankTrimmed(titleEnglish, titleRomaji)

    /** Titolo da mostrare in UI, con lo stesso ordine di preferenza di [searchTitle]. */
    fun displayTitle(): String = searchTitle() ?: "Senza titolo"
}

/** Criterio di ordinamento AniList, una sezione della schermata Scopri per ogni valore. */
enum class AniListSort(val apiValue: String) {
    TRENDING("TRENDING_DESC"),
    POPULAR("POPULARITY_DESC"),
    TOP_RATED("SCORE_DESC"),
    NEWEST("START_DATE_DESC"),
}

/**
 * Generi AniList offerti come filtro nella schermata Scopri. Sottoinsieme curato dei generi
 * reali dell'API (in inglese, come li vuole il filtro `genre`).
 */
val ANILIST_GENRES: List<String> = listOf(
    "Action",
    "Adventure",
    "Comedy",
    "Drama",
    "Fantasy",
    "Horror",
    "Mystery",
    "Psychological",
    "Romance",
    "Sci-Fi",
    "Slice of Life",
    "Sports",
    "Supernatural",
    "Thriller",
    "Ecchi",
    "Mecha",
    "Music",
)

/**
 * Client minimale per l'API GraphQL pubblica di AniList (nessuna API key). Fa una sola query
 * parametrica: cambiando [AniListSort] e [genre] copre tutte le sezioni della schermata Scopri
 * (tendenze, più votati, novità, per genere). Sincrono: va chiamato da un thread IO.
 */
class AniListClient(
    private val httpClient: OkHttpClient,
) {
    fun fetchMedia(
        sort: AniListSort,
        genre: String? = null,
        page: Int = 1,
        perPage: Int = DEFAULT_PER_PAGE,
    ): List<AniListManga> {
        val variables = buildJsonObject {
            put("page", page)
            put("perPage", perPage)
            putJsonArray("sort") { add(sort.apiValue) }
            genre?.trim()?.takeIf(String::isNotBlank)?.let { put("genre", it) }
        }
        val payload = buildJsonObject {
            put("query", QUERY)
            put("variables", variables)
        }.toString()
        return parseMediaResponse(post(payload))
    }

    private fun post(jsonBody: String): String {
        val request = Request.Builder()
            .url(ENDPOINT)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} da AniList")
            }
            return response.body?.string() ?: throw IOException("Risposta vuota da AniList")
        }
    }

    companion object {
        private const val ENDPOINT = "https://graphql.anilist.co"
        private const val DEFAULT_PER_PAGE = 24
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val json = Json { ignoreUnknownKeys = true }

        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"

        // `sort` è [MediaSort] e `genre` String: una sola query serve tutte le sezioni.
        // isAdult:false tiene fuori i contenuti per adulti dalla vetrina (la Scopri è libera).
        private val QUERY = """
            query (${'$'}page: Int, ${'$'}perPage: Int, ${'$'}sort: [MediaSort], ${'$'}genre: String) {
              Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                media(type: MANGA, sort: ${'$'}sort, genre: ${'$'}genre, isAdult: false) {
                  id
                  title { romaji english }
                  coverImage { large }
                  genres
                  averageScore
                  description(asHtml: false)
                  status
                }
              }
            }
        """.trimIndent()

        /**
         * Estrae la lista di [AniListManga] dalla risposta JSON GraphQL. Tollerante: salta le
         * voci senza id o senza alcun titolo, ignora i campi mancanti e ritorna lista vuota se
         * `data` è assente (es. risposta di errore). Pulisce la trama dai residui HTML.
         */
        internal fun parseMediaResponse(jsonText: String): List<AniListManga> {
            val media = json.parseToJsonElement(jsonText).jsonObject["data"]
                ?.jsonObject?.get("Page")
                ?.jsonObject?.get("media")
                ?.jsonArray
                ?: return emptyList()

            return media.mapNotNull { element ->
                val obj = element.jsonObject
                val id = obj["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                val title = obj["title"]?.jsonObject
                val romaji = title?.get("romaji")?.jsonPrimitive?.contentOrNull?.trim()
                val english = title?.get("english")?.jsonPrimitive?.contentOrNull?.trim()
                if (romaji.isNullOrBlank() && english.isNullOrBlank()) {
                    return@mapNotNull null
                }
                AniListManga(
                    id = id,
                    titleRomaji = romaji?.takeIf(String::isNotBlank),
                    titleEnglish = english?.takeIf(String::isNotBlank),
                    coverUrl = obj["coverImage"]?.jsonObject?.get("large")
                        ?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank),
                    genres = obj["genres"]?.jsonArray
                        ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotBlank) }
                        ?: emptyList(),
                    averageScore = obj["averageScore"]?.jsonPrimitive?.intOrNull,
                    description = cleanDescription(obj["description"]?.jsonPrimitive?.contentOrNull),
                    status = mangaStatusFromText(obj["status"]?.jsonPrimitive?.contentOrNull),
                )
            }
        }

        /**
         * Ripulisce la trama AniList dai residui HTML (`<br>`, tag) e dalle entità più comuni,
         * così è leggibile in un semplice [Text]. `null`/vuoto resta `null`.
         */
        internal fun cleanDescription(raw: String?): String? {
            val text = raw?.trim()?.takeIf(String::isNotBlank) ?: return null
            return text
                .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
                .replace(Regex("<[^>]+>"), "")
                .replace("&quot;", "\"")
                .replace("&amp;", "&")
                .replace("&#039;", "'")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace(Regex("\n{3,}"), "\n\n")
                .trim()
                .takeIf(String::isNotBlank)
        }
    }
}
