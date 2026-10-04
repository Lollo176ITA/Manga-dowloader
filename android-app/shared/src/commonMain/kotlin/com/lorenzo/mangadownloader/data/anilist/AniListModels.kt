package com.lorenzo.mangadownloader.data.anilist

import com.lorenzo.mangadownloader.data.model.MangaPublicationStatus
import com.lorenzo.mangadownloader.data.sources.firstNonBlankTrimmed
import kotlinx.serialization.Serializable
import okio.IOException

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

/** L'utente AniList autenticato. Presente nello stato ⇔ account collegato. */
data class AniListViewer(
    val id: Int,
    val name: String,
    val scoreFormat: AniListScoreFormat,
)

/** Entry della lista utente come la riporta l'API (voto nel formato dell'account, 0 = nessuno). */
data class AniListListEntry(
    val status: AniListListStatus?,
    val progress: Int,
    val score: Double?,
)

/** Media AniList + (eventuale) entry dell'utente: serve a seedare il tracking al collegamento. */
data class AniListMediaEntry(
    val mediaId: Int,
    val totalChapters: Int?,
    val entry: AniListListEntry?,
)
