package com.lorenzo.mangadownloader

import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
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
    /** Numero totale di capitoli secondo AniList; `null` se la serie è in corso o ignoto. */
    val chapters: Int? = null,
    /** Formato AniList grezzo (MANGA, ONE_SHOT, NOVEL…), mostrato nel matching del tracking. */
    val format: String? = null,
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

/** Il token AniList non è (più) valido: chi chiama deve disconnettere l'account. */
class AniListAuthException(message: String) : IOException(message)

/**
 * Client minimale per l'API GraphQL di AniList. Le sezioni della schermata Scopri usano la sola
 * query pubblica parametrica [fetchMedia] (nessuna API key). Le operazioni di **tracking**
 * ([fetchViewer], [searchManga], [fetchMediaEntry], [saveListEntry]) richiedono il token OAuth
 * dell'utente (vedi [AniListAuth]). Sincrono: va chiamato da un thread IO.
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

    /** L'utente autenticato (id, nome, formato voto). Valida di fatto il token appena ottenuto. */
    fun fetchViewer(token: String): AniListViewer {
        val payload = buildJsonObject {
            put("query", VIEWER_QUERY)
        }.toString()
        return parseViewerResponse(post(payload, token))
            ?: throw IOException("Profilo AniList non disponibile")
    }

    /**
     * Ricerca per titolo usata dal matching del tracking. Nessun filtro `isAdult`: qui l'utente
     * sta collegando un titolo che già legge, non sfogliando una vetrina.
     */
    fun searchManga(query: String, perPage: Int = MATCH_PER_PAGE): List<AniListManga> {
        val variables = buildJsonObject {
            put("search", query)
            put("perPage", perPage)
        }
        val payload = buildJsonObject {
            put("query", SEARCH_QUERY)
            put("variables", variables)
        }.toString()
        return parseMediaResponse(post(payload))
    }

    /** Capitoli totali + entry dell'utente (se esiste) per un media, per seedare il tracking. */
    fun fetchMediaEntry(mediaId: Int, token: String): AniListMediaEntry {
        val variables = buildJsonObject { put("mediaId", mediaId) }
        val payload = buildJsonObject {
            put("query", MEDIA_ENTRY_QUERY)
            put("variables", variables)
        }.toString()
        return parseMediaEntryResponse(post(payload, token))
            ?: throw IOException("Media AniList $mediaId non trovato")
    }

    /**
     * Crea o aggiorna l'entry di lista dell'utente. I parametri `null` non vengono inviati e
     * AniList conserva il valore esistente. [score] è nel formato voto dell'account
     * (vedi [AniListScoreFormat]). Ritorna l'entry come salvata dal server.
     */
    fun saveListEntry(
        token: String,
        mediaId: Int,
        status: AniListListStatus? = null,
        progress: Int? = null,
        score: Double? = null,
    ): AniListListEntry {
        val variables = buildJsonObject {
            put("mediaId", mediaId)
            status?.let { put("status", it.name) }
            progress?.let { put("progress", it) }
            score?.let { put("score", it) }
        }
        val payload = buildJsonObject {
            put("query", SAVE_ENTRY_MUTATION)
            put("variables", variables)
        }.toString()
        return parseSaveEntryResponse(post(payload, token))
            ?: throw IOException("Risposta inattesa da AniList al salvataggio")
    }

    private fun post(jsonBody: String, token: String? = null): String {
        val request = Request.Builder()
            .url(ENDPOINT)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .apply { token?.let { header("Authorization", "Bearer $it") } }
            .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) {
                throw AniListAuthException("Sessione AniList scaduta")
            }
            if (response.code == 429) {
                throw IOException("AniList sta limitando le richieste, riprova tra poco")
            }
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} da AniList")
            }
            return response.body?.string() ?: throw IOException("Risposta vuota da AniList")
        }
    }

    companion object {
        private const val ENDPOINT = "https://graphql.anilist.co"
        private const val DEFAULT_PER_PAGE = 24
        private const val MATCH_PER_PAGE = 10
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

        private val SEARCH_QUERY = """
            query (${'$'}search: String, ${'$'}perPage: Int) {
              Page(page: 1, perPage: ${'$'}perPage) {
                media(search: ${'$'}search, type: MANGA) {
                  id
                  title { romaji english }
                  coverImage { large }
                  genres
                  averageScore
                  description(asHtml: false)
                  status
                  chapters
                  format
                }
              }
            }
        """.trimIndent()

        private val VIEWER_QUERY = """
            query {
              Viewer {
                id
                name
                mediaListOptions { scoreFormat }
              }
            }
        """.trimIndent()

        private val MEDIA_ENTRY_QUERY = """
            query (${'$'}mediaId: Int) {
              Media(id: ${'$'}mediaId, type: MANGA) {
                id
                chapters
                mediaListEntry { status progress score }
              }
            }
        """.trimIndent()

        private val SAVE_ENTRY_MUTATION = """
            mutation (${'$'}mediaId: Int, ${'$'}status: MediaListStatus, ${'$'}progress: Int, ${'$'}score: Float) {
              SaveMediaListEntry(mediaId: ${'$'}mediaId, status: ${'$'}status, progress: ${'$'}progress, score: ${'$'}score) {
                status
                progress
                score
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
                    chapters = obj["chapters"]?.jsonPrimitive?.intOrNull,
                    format = obj["format"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank),
                )
            }
        }

        /** Estrae il [AniListViewer] dalla risposta della [VIEWER_QUERY]; `null` se assente. */
        internal fun parseViewerResponse(jsonText: String): AniListViewer? {
            val viewer = json.parseToJsonElement(jsonText).jsonObject["data"]
                ?.jsonObject?.get("Viewer")
                ?.takeIf { it !is JsonNull }
                ?.jsonObject
                ?: return null
            val id = viewer["id"]?.jsonPrimitive?.intOrNull ?: return null
            val name = viewer["name"]?.jsonPrimitive?.contentOrNull?.trim()
                ?.takeIf(String::isNotBlank) ?: return null
            val scoreFormat = viewer["mediaListOptions"]?.jsonObject
                ?.get("scoreFormat")?.jsonPrimitive?.contentOrNull
            return AniListViewer(
                id = id,
                name = name,
                scoreFormat = aniListScoreFormatFromText(scoreFormat),
            )
        }

        /** Estrae media + entry utente dalla [MEDIA_ENTRY_QUERY]; `null` se il media manca. */
        internal fun parseMediaEntryResponse(jsonText: String): AniListMediaEntry? {
            val media = json.parseToJsonElement(jsonText).jsonObject["data"]
                ?.jsonObject?.get("Media")
                ?.takeIf { it !is JsonNull }
                ?.jsonObject
                ?: return null
            val mediaId = media["id"]?.jsonPrimitive?.intOrNull ?: return null
            return AniListMediaEntry(
                mediaId = mediaId,
                totalChapters = media["chapters"]?.jsonPrimitive?.intOrNull,
                entry = media["mediaListEntry"]
                    ?.takeIf { it !is JsonNull }
                    ?.jsonObject
                    ?.let(::parseListEntry),
            )
        }

        /** Estrae l'entry salvata dalla risposta della [SAVE_ENTRY_MUTATION]. */
        internal fun parseSaveEntryResponse(jsonText: String): AniListListEntry? {
            return json.parseToJsonElement(jsonText).jsonObject["data"]
                ?.jsonObject?.get("SaveMediaListEntry")
                ?.takeIf { it !is JsonNull }
                ?.jsonObject
                ?.let(::parseListEntry)
        }

        private fun parseListEntry(obj: JsonObject): AniListListEntry {
            return AniListListEntry(
                status = aniListStatusFromText(obj["status"]?.jsonPrimitive?.contentOrNull),
                progress = obj["progress"]?.jsonPrimitive?.intOrNull ?: 0,
                score = obj["score"]?.jsonPrimitive?.doubleOrNull?.takeIf { it > 0.0 },
            )
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
