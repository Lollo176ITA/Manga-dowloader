package com.lorenzo.mangadownloader.data.anilist

import com.lorenzo.mangadownloader.BuildConfig
import com.lorenzo.mangadownloader.data.model.MangaPublicationStatus
import com.lorenzo.mangadownloader.data.model.mangaStatusFromText
import com.lorenzo.mangadownloader.data.sources.firstNonBlankTrimmed
import com.lorenzo.mangadownloader.domain.isAdultGenre
import java.io.IOException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.parser.Parser

/**
 * Un manga come lo descrive AniList: **solo metadati** (titolo, copertina, generi, voto, trama,
 * stato). AniList è un catalogo, non una fonte da cui scaricare: per leggere/scaricare il titolo
 * va ri-cercato sulle fonti reali ([MangaSource]) tramite [searchTitle]. Per questo [AniListManga]
 * vive fuori dal [MangaSourceRegistry].
 */
@Serializable
data class AniListManga(
    val id: Int,
    val titleRomaji: String?,
    val titleEnglish: String?,
    val titleNative: String? = null,
    /** Titoli alternativi noti ad AniList (spesso includono il titolo italiano). */
    val synonyms: List<String> = emptyList(),
    val coverUrl: String?,
    val genres: List<String>,
    val averageScore: Int?,
    val description: String?,
    val status: MangaPublicationStatus,
    /** Numero totale di capitoli secondo AniList; `null` se la serie è in corso o ignoto. */
    val chapters: Int? = null,
    /** Formato AniList grezzo (MANGA, ONE_SHOT, NOVEL…), mostrato nel matching del tracking. */
    val format: String? = null,
    /** Contenuto per adulti secondo AniList (in pratica: hentai). Vedi `isAdultContent`. */
    val isAdult: Boolean = false,
) {
    /**
     * Titolo da usare per cercare sulle fonti: prima l'inglese (più comune sui siti EN tipo
     * Mangapill), poi il romaji come fallback. `null` se entrambi mancano.
     */
    fun searchTitle(): String? = firstNonBlankTrimmed(titleEnglish, titleRomaji)

    /** Titolo da mostrare in UI, con lo stesso ordine di preferenza di [searchTitle]. */
    fun displayTitle(): String = searchTitle() ?: "Senza titolo"

    /**
     * I titoli **veri** della serie: come si chiama, nelle tre lingue che AniList tiene.
     * Vanno tenuti distinti dai [synonymTitles] perché valgono molto di più in un confronto:
     * un titolo principale identifica la serie, un sinonimo può essere qualsiasi cosa.
     */
    fun primaryTitles(): List<String> =
        listOfNotNull(titleEnglish, titleRomaji, titleNative)
            .map(String::trim)
            .filter(String::isNotBlank)

    /**
     * I titoli alternativi noti ad AniList. Utili — spesso contengono il titolo italiano — ma
     * inaffidabili come prova d'identità: sono compilati dalla community e su certe raccolte
     * contengono i titoli dei singoli capitoli. Un esempio reale: la raccolta hentai
     * `Gekka Bijin` (id 94792) ha "Pick Me Up" fra i suoi diciotto sinonimi, e la ricerca
     * AniList per quel titolo la mette PRIMA del webtoon che si chiama davvero così.
     */
    fun synonymTitles(): List<String> =
        synonyms.map(String::trim).filter(String::isNotBlank)

    /** Tutti i titoli noti, in ordine di preferenza, per il matching tra fonti. */
    fun allTitles(): List<String> = primaryTitles() + synonymTitles()
}

/**
 * Una raccomandazione della community AniList: "chi ha letto [seedMediaId] consiglia [manga]",
 * con [rating] = voti netti della community su quel suggerimento. Alimenta il blocco Home
 * "Consigliati per te" (vedi [aggregateRecommendations]).
 */
data class AniListRecommendation(
    val seedMediaId: Int,
    val rating: Int,
    val manga: AniListManga,
)

/** Criterio di ordinamento AniList, una sezione della schermata Scopri per ogni valore. */
enum class AniListSort(val apiValue: String) {
    TRENDING("TRENDING_DESC"),
    POPULAR("POPULARITY_DESC"),
    TOP_RATED("SCORE_DESC"),
    NEWEST("START_DATE_DESC"),
}

/**
 * Il token AniList non è (più) valido: chi chiama deve disconnettere l'account.
 *
 * [token] è il token con cui la richiesta era partita. Serve a distinguere il rifiuto della
 * sessione **corrente** da un 401 arrivato in ritardo da una richiesta partita con il token
 * PRECEDENTE: senza questa distinzione la risposta tardiva di una sessione scaduta scollega
 * un account appena ricollegato, e l'unico rimedio per l'utente è riavviare l'app.
 */
class AniListAuthException(message: String, val token: String? = null) : IOException(message)

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

    /**
     * Raccomandazioni della community per i media indicati, in un'unica query. Usata dal blocco
     * "Consigliati per te": i semi sono i titoli dell'utente (preferiti/letti) già risolti in id
     * AniList via [searchManga]. Le voci per adulti vengono scartate come nella vetrina Scopri.
     */
    fun fetchRecommendations(
        mediaIds: List<Int>,
        perSeed: Int = RECOMMENDATIONS_PER_SEED,
    ): List<AniListRecommendation> {
        if (mediaIds.isEmpty()) return emptyList()
        val variables = buildJsonObject {
            putJsonArray("ids") { mediaIds.forEach { add(it) } }
            put("perSeed", perSeed)
        }
        val payload = buildJsonObject {
            put("query", RECOMMENDATIONS_QUERY)
            put("variables", variables)
        }.toString()
        return parseRecommendationsResponse(post(payload))
    }

    /**
     * Quali tra [mediaIds] sono per adulti (flag `isAdult` o generi, come `isAdultContent`):
     * serve al recupero del registro adulti su preferiti e download già presenti. Gli id che
     * AniList non restituisce mancano dalla mappa.
     */
    fun fetchAdultFlags(mediaIds: List<Int>): Map<Int, Boolean> {
        if (mediaIds.isEmpty()) return emptyMap()
        val variables = buildJsonObject {
            putJsonArray("ids") { mediaIds.forEach { add(it) } }
            put("perPage", mediaIds.size)
        }
        val payload = buildJsonObject {
            put("query", ADULT_FLAGS_QUERY)
            put("variables", variables)
        }.toString()
        return parseAdultFlagsResponse(post(payload))
    }

    /**
     * I manga tra i **preferiti** ("favourites") dell'utente. Sono una cosa diversa dalla lista
     * di lettura: su AniList un titolo può stare in lista senza essere un preferito e viceversa.
     * Paginata di suo, qui la si scorre tutta: l'elenco è tipicamente di poche decine di voci.
     */
    fun fetchFavouriteManga(token: String, userId: Int): List<AniListManga> {
        val collected = mutableListOf<AniListManga>()
        var page = 1
        while (page <= MAX_FAVOURITE_PAGES) {
            val variables = buildJsonObject {
                put("userId", userId)
                put("page", page)
                put("perPage", FAVOURITES_PER_PAGE)
            }
            val payload = buildJsonObject {
                put("query", FAVOURITES_QUERY)
                put("variables", variables)
            }.toString()
            val (items, hasNextPage) = parseFavouriteMangaResponse(post(payload, token))
            collected += items
            if (!hasNextPage) break
            page++
        }
        return collected
    }

    /**
     * Inverte lo stato "preferito" di un manga. L'API espone solo un **toggle**: non esiste un
     * "aggiungi" idempotente, quindi chi chiama deve già sapere lo stato attuale (per questo la
     * riconciliazione parte sempre da [fetchFavouriteManga]). Ritorna `true` se dopo la chiamata
     * il manga risulta tra i preferiti.
     */
    fun toggleFavouriteManga(token: String, mediaId: Int): Boolean {
        val variables = buildJsonObject { put("mangaId", mediaId) }
        val payload = buildJsonObject {
            put("query", TOGGLE_FAVOURITE_MUTATION)
            put("variables", variables)
        }.toString()
        return parseToggleFavouriteResponse(post(payload, token), mediaId)
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
                throw AniListAuthException("Sessione AniList scaduta", token)
            }
            if (response.code == 429) {
                throw IOException("AniList sta limitando le richieste, riprova tra poco")
            }
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} da AniList")
            }
            return response.body.string()
        }
    }

    companion object {
        private const val ENDPOINT = "https://graphql.anilist.co"
        private const val DEFAULT_PER_PAGE = 24
        private const val MATCH_PER_PAGE = 10
        private const val RECOMMENDATIONS_PER_SEED = 8
        private const val FAVOURITES_PER_PAGE = 50

        /**
         * Tetto di sanità sullo scorrimento dei preferiti: 25 pagine da 50 sono 1250 titoli,
         * ben oltre qualsiasi lista reale. Evita un ciclo infinito se `hasNextPage` mentisse.
         */
        private const val MAX_FAVOURITE_PAGES = 25
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val json = Json {
            ignoreUnknownKeys = true
            // Un `null` su un campo con default (liste, flag) prende il default invece di fallire.
            coerceInputValues = true
        }

        // API ufficiale: meglio un client identificabile che un finto browser.
        private val USER_AGENT = "MangApp/${BuildConfig.VERSION_NAME} (Android)"

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

        /** Massimo di id per [fetchAdultFlags]: è il tetto di `perPage` di AniList. */
        const val ADULT_FLAGS_BATCH = 50

        private val ADULT_FLAGS_QUERY = """
            query (${'$'}ids: [Int], ${'$'}perPage: Int) {
              Page(page: 1, perPage: ${'$'}perPage) {
                media(id_in: ${'$'}ids, type: MANGA) {
                  id
                  isAdult
                  genres
                }
              }
            }
        """.trimIndent()

        private val SEARCH_QUERY = """
            query (${'$'}search: String, ${'$'}perPage: Int) {
              Page(page: 1, perPage: ${'$'}perPage) {
                media(search: ${'$'}search, type: MANGA) {
                  id
                  title { romaji english native }
                  synonyms
                  coverImage { large }
                  genres
                  averageScore
                  description(asHtml: false)
                  status
                  chapters
                  format
                  isAdult
                }
              }
            }
        """.trimIndent()

        // Raccomandazioni per più semi in un colpo solo. `isAdult` viene richiesto sul
        // suggerimento per poterlo filtrare in parsing (vetrina libera, come la Scopri).
        private val RECOMMENDATIONS_QUERY = """
            query (${'$'}ids: [Int], ${'$'}perSeed: Int) {
              Page(page: 1, perPage: 25) {
                media(id_in: ${'$'}ids, type: MANGA) {
                  id
                  recommendations(perPage: ${'$'}perSeed, sort: RATING_DESC) {
                    nodes {
                      rating
                      mediaRecommendation {
                        id
                        title { romaji english }
                        coverImage { large }
                        genres
                        averageScore
                        description(asHtml: false)
                        status
                        isAdult
                      }
                    }
                  }
                }
              }
            }
        """.trimIndent()

        // I preferiti dell'utente. `nodes` ha la stessa forma dei media della ricerca, così
        // il parsing è lo stesso: cambia solo il punto da cui si pesca l'array.
        private val FAVOURITES_QUERY = """
            query (${'$'}userId: Int, ${'$'}page: Int, ${'$'}perPage: Int) {
              User(id: ${'$'}userId) {
                favourites {
                  manga(page: ${'$'}page, perPage: ${'$'}perPage) {
                    pageInfo { hasNextPage }
                    nodes {
                      id
                      title { romaji english native }
                      synonyms
                      coverImage { large }
                      genres
                      averageScore
                      description(asHtml: false)
                      status
                      chapters
                      format
                      isAdult
                    }
                  }
                }
              }
            }
        """.trimIndent()

        // La mutation risponde con la prima pagina dei preferiti aggiornati: cercarci dentro
        // il mediaId dice se ora c'è o non c'è, senza una seconda richiesta.
        private val TOGGLE_FAVOURITE_MUTATION = """
            mutation (${'$'}mangaId: Int) {
              ToggleFavourite(mangaId: ${'$'}mangaId) {
                manga { nodes { id } }
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
        internal fun parseMediaResponse(jsonText: String): List<AniListManga> =
            decode<PageData>(jsonText)?.page?.media.orEmpty().mapNotNull { it?.toManga() }

        internal fun parseAdultFlagsResponse(jsonText: String): Map<Int, Boolean> =
            decode<PageData>(jsonText)?.page?.media.orEmpty().mapNotNull { media ->
                val id = media?.id ?: return@mapNotNull null
                id to (media.isAdult || media.genres.any(::isAdultGenre))
            }.toMap()

        /**
         * Estrae le raccomandazioni dalla [RECOMMENDATIONS_QUERY]. Tollerante come
         * [parseMediaResponse]; scarta le voci per adulti e i suggerimenti senza media.
         */
        internal fun parseRecommendationsResponse(jsonText: String): List<AniListRecommendation> =
            decode<PageData>(jsonText)?.page?.media.orEmpty().flatMap { seed ->
                val seedId = seed?.id ?: return@flatMap emptyList()
                seed.recommendations?.nodes.orEmpty().mapNotNull { node ->
                    val recommended = node?.mediaRecommendation ?: return@mapNotNull null
                    if (recommended.isAdult) return@mapNotNull null
                    AniListRecommendation(
                        seedMediaId = seedId,
                        rating = node.rating,
                        manga = recommended.toManga() ?: return@mapNotNull null,
                    )
                }
            }

        /**
         * Estrae una pagina di preferiti: i media e il flag "c'è un'altra pagina". Tollerante
         * come [parseMediaResponse]: una risposta senza `data` dà lista vuota e nessun seguito.
         */
        internal fun parseFavouriteMangaResponse(jsonText: String): Pair<List<AniListManga>, Boolean> {
            val manga = decode<UserData>(jsonText)?.user?.favourites?.manga
                ?: return emptyList<AniListManga>() to false
            return manga.nodes.mapNotNull { it?.toManga() } to (manga.pageInfo?.hasNextPage == true)
        }

        /**
         * `true` se dopo il toggle [mediaId] è tra i preferiti. La mutation restituisce solo la
         * prima pagina: un id non trovato lì dentro, con la lista piena, sarebbe un falso
         * "rimosso" — per questo chi chiama usa il valore solo come conferma, mai come verità
         * al posto di [fetchFavouriteManga].
         */
        internal fun parseToggleFavouriteResponse(jsonText: String, mediaId: Int): Boolean =
            decode<ToggleData>(jsonText)?.toggle?.manga?.nodes.orEmpty().any { it?.id == mediaId }

        /** Estrae il [AniListViewer] dalla risposta della [VIEWER_QUERY]; `null` se assente. */
        internal fun parseViewerResponse(jsonText: String): AniListViewer? {
            val viewer = decode<ViewerData>(jsonText)?.viewer ?: return null
            return AniListViewer(
                id = viewer.id ?: return null,
                name = viewer.name.trimmedOrNull() ?: return null,
                scoreFormat = aniListScoreFormatFromText(viewer.mediaListOptions?.scoreFormat),
            )
        }

        /** Estrae media + entry utente dalla [MEDIA_ENTRY_QUERY]; `null` se il media manca. */
        internal fun parseMediaEntryResponse(jsonText: String): AniListMediaEntry? {
            val media = decode<MediaEntryData>(jsonText)?.media ?: return null
            return AniListMediaEntry(
                mediaId = media.id ?: return null,
                totalChapters = media.chapters,
                entry = media.mediaListEntry?.toEntry(),
            )
        }

        /** Estrae l'entry salvata dalla risposta della [SAVE_ENTRY_MUTATION]. */
        internal fun parseSaveEntryResponse(jsonText: String): AniListListEntry? =
            decode<SaveEntryData>(jsonText)?.entry?.toEntry()

        /**
         * Il campo `data` della risposta GraphQL, `null` se manca (risposta di errore). Un JSON
         * malformato diventa [IOException], come gli altri errori di rete che i chiamanti gestiscono.
         */
        private inline fun <reified T> decode(jsonText: String): T? = try {
            json.decodeFromString<GraphQlResponse<T>>(jsonText).data
        } catch (e: SerializationException) {
            throw IOException("Risposta AniList non valida", e)
        }

        /**
         * Ripulisce la trama AniList dai residui HTML (`<br>`, tag) e dalle entità, così è
         * leggibile in un semplice [Text]. `null`/vuoto resta `null`.
         */
        internal fun cleanDescription(raw: String?): String? {
            val text = raw?.trim()?.takeIf(String::isNotBlank) ?: return null
            val withoutTags = text
                .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
                .replace(Regex("<[^>]+>"), "")
            // Una sola passata di decodifica: `&amp;lt;` resta il testo letterale `&lt;`.
            return Parser.unescapeEntities(withoutTags, false)
                .replace(Regex("\n{3,}"), "\n\n")
                .trim()
                .takeIf(String::isNotBlank)
        }
    }
}

// Forma delle risposte GraphQL: tutto opzionale, così un campo assente o `null` non fa
// fallire la decodifica (vedi `coerceInputValues`).

@Serializable
private class GraphQlResponse<T>(val data: T? = null)

@Serializable
private class PageData(@SerialName("Page") val page: MediaPage? = null)

@Serializable
private class MediaPage(val media: List<MediaDto?> = emptyList())

@Serializable
private class MediaDto(
    val id: Int? = null,
    val title: TitleDto? = null,
    val synonyms: List<String> = emptyList(),
    val coverImage: CoverDto? = null,
    val genres: List<String> = emptyList(),
    val averageScore: Int? = null,
    val description: String? = null,
    val status: String? = null,
    val chapters: Int? = null,
    val format: String? = null,
    val isAdult: Boolean = false,
    val recommendations: RecommendationConnection? = null,
) {
    /** `null` se manca l'id o qualsiasi titolo (romaji/inglese). */
    fun toManga(): AniListManga? {
        val id = id ?: return null
        val romaji = title?.romaji.trimmedOrNull()
        val english = title?.english.trimmedOrNull()
        if (romaji == null && english == null) return null
        return AniListManga(
            id = id,
            titleRomaji = romaji,
            titleEnglish = english,
            titleNative = title?.native.trimmedOrNull(),
            synonyms = synonyms.mapNotNull { it.trimmedOrNull() },
            coverUrl = coverImage?.large?.takeIf(String::isNotBlank),
            genres = genres.mapNotNull { it.trimmedOrNull() },
            averageScore = averageScore,
            description = AniListClient.cleanDescription(description),
            status = mangaStatusFromText(status),
            chapters = chapters,
            format = format?.takeIf(String::isNotBlank),
            isAdult = isAdult,
        )
    }
}

private fun String?.trimmedOrNull(): String? = this?.trim()?.takeIf(String::isNotBlank)

@Serializable
private class TitleDto(val romaji: String? = null, val english: String? = null, val native: String? = null)

@Serializable
private class CoverDto(val large: String? = null)

@Serializable
private class RecommendationConnection(val nodes: List<RecommendationNode?> = emptyList())

@Serializable
private class RecommendationNode(val rating: Int = 0, val mediaRecommendation: MediaDto? = null)

@Serializable
private class UserData(@SerialName("User") val user: UserDto? = null)

@Serializable
private class UserDto(val favourites: FavouritesDto? = null)

@Serializable
private class FavouritesDto(val manga: MediaConnection? = null)

@Serializable
private class MediaConnection(val pageInfo: PageInfo? = null, val nodes: List<MediaDto?> = emptyList())

@Serializable
private class PageInfo(val hasNextPage: Boolean = false)

@Serializable
private class ToggleData(@SerialName("ToggleFavourite") val toggle: ToggleDto? = null)

@Serializable
private class ToggleDto(val manga: MediaConnection? = null)

@Serializable
private class ViewerData(@SerialName("Viewer") val viewer: ViewerDto? = null)

@Serializable
private class ViewerDto(val id: Int? = null, val name: String? = null, val mediaListOptions: ListOptionsDto? = null)

@Serializable
private class ListOptionsDto(val scoreFormat: String? = null)

@Serializable
private class MediaEntryData(@SerialName("Media") val media: MediaEntryDto? = null)

@Serializable
private class MediaEntryDto(val id: Int? = null, val chapters: Int? = null, val mediaListEntry: ListEntryDto? = null)

@Serializable
private class SaveEntryData(@SerialName("SaveMediaListEntry") val entry: ListEntryDto? = null)

@Serializable
private class ListEntryDto(val status: String? = null, val progress: Int? = null, val score: Double? = null) {
    fun toEntry() = AniListListEntry(
        status = aniListStatusFromText(status),
        progress = progress ?: 0,
        // Su AniList 0 significa "nessun voto": non va riportato come voto reale.
        score = score?.takeIf { it > 0.0 },
    )
}
