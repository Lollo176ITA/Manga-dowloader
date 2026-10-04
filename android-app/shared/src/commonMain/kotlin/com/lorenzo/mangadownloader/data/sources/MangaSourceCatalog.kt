package com.lorenzo.mangadownloader.data.sources

import com.lorenzo.mangadownloader.data.model.identityKey
import okio.IOException

/** Lingua dei contenuti di una fonte: è il criterio con cui l'utente sceglie dove cercare. */
enum class MangaSourceLanguage(val displayName: String) {
    ITA("Italiano"),
    ENG("English"),
}

data class MangaSourceDescriptor(
    val id: String,
    val displayName: String,
    val shortName: String,
    val language: MangaSourceLanguage,
)

/**
 * Ambito della ricerca nella tab Cerca. L'utente ragiona per lingua ("lo voglio in italiano
 * o in inglese?"), non per server: lo scope [SOURCE] (fonte singola) non è più selezionabile
 * dalla UI e i valori persistiti da versioni precedenti vengono riportati alla lingua della
 * fonte in lettura (prefs e backup).
 */
enum class SearchScope(val language: MangaSourceLanguage?) {
    /** Aggregata su tutte le fonti (chip "Tutte" o ponte AniList della tab Scopri). */
    ALL(null),

    /** Aggregata sulle sole fonti italiane. */
    ITA(MangaSourceLanguage.ITA),

    /** Aggregata sulle sole fonti inglesi. */
    ENG(MangaSourceLanguage.ENG),

    /** Valore legacy, mantenuto soltanto per deserializzare preferenze e backup precedenti. */
    SOURCE(null),
    ;

    companion object {
        fun forLanguage(language: MangaSourceLanguage): SearchScope = when (language) {
            MangaSourceLanguage.ITA -> ITA
            MangaSourceLanguage.ENG -> ENG
        }
    }
}

/**
 * Spazio su disco insufficiente per completare un download. Volutamente **non** è una
 * [java.io.IOException]: il `DownloadWorker` ritenta gli `IOException` (blip di rete), ma
 * un disco pieno deve fermarsi subito con un messaggio per l'utente, non ciclare in retry.
 */
class InsufficientStorageException(message: String) : RuntimeException(message)

object MangaSourceIds {
    const val MANGAPILL = "mangapill"
    const val HASTA_TEAM = "hasta_team"
    const val MANGA_WORLD = "manga_world"
    const val VYMANGA = "vymanga"
    const val ASURA_SCANS = "asura_scans"
    const val DEMONIC_SCANS = "demonic_scans"
    const val TCB_SCANS = "tcb_scans"
    const val WEEB_CENTRAL = "weeb_central"
    const val DEFAULT = MANGAPILL
}

object MangaSourceCatalog {
    val descriptors = listOf(
        MangaSourceDescriptor(MangaSourceIds.MANGAPILL, "Mangapill", "MP", MangaSourceLanguage.ENG),
        MangaSourceDescriptor(MangaSourceIds.HASTA_TEAM, "Hasta Team", "HT", MangaSourceLanguage.ITA),
        MangaSourceDescriptor(MangaSourceIds.MANGA_WORLD, "MangaWorld", "MW", MangaSourceLanguage.ITA),
        MangaSourceDescriptor(MangaSourceIds.VYMANGA, "VyManga", "VY", MangaSourceLanguage.ENG),
        MangaSourceDescriptor(MangaSourceIds.ASURA_SCANS, "Asura Scans", "AS", MangaSourceLanguage.ENG),
        MangaSourceDescriptor(MangaSourceIds.DEMONIC_SCANS, "DemonicScans", "DS", MangaSourceLanguage.ENG),
        MangaSourceDescriptor(MangaSourceIds.TCB_SCANS, "TCB Scans", "TC", MangaSourceLanguage.ENG),
        MangaSourceDescriptor(MangaSourceIds.WEEB_CENTRAL, "Weeb Central", "WC", MangaSourceLanguage.ENG),
    )

    /**
     * Fonti senza un segnale "adulti" nella risposta di ricerca: niente generi né flag, e
     * nessun filtro lato server. Col filtro per adulti attivo la ricerca non le interroga,
     * perché non si potrebbe garantire il risultato (DemonicScans espone i generi solo nella
     * pagina serie: servirebbe una richiesta in più per ogni risultato).
     */
    val sourcesWithoutAdultSignal: Set<String> = setOf(MangaSourceIds.DEMONIC_SCANS)

    /**
     * Fonti da interrogare nella ricerca aggregata: [descriptorsForScope] più, col filtro per
     * adulti attivo, l'esclusione di [sourcesWithoutAdultSignal]. L'esclusione si applica
     * **dopo** il ripiego "mai zero fonti" di [descriptorsForScope], che altrimenti potrebbe
     * rimetterle in gioco.
     */
    fun descriptorsForSearch(
        scope: SearchScope,
        disabledSourceIds: Set<String>,
        hideAdultContent: Boolean,
    ): List<MangaSourceDescriptor> {
        val candidates = descriptorsForScope(scope, disabledSourceIds)
        if (!hideAdultContent) return candidates
        return candidates.filterNot { it.id in sourcesWithoutAdultSignal }
    }

    /** Fonti interrogate dalla ricerca aggregata per [scope]: tutte, o solo quelle della lingua. */
    fun descriptorsForScope(scope: SearchScope): List<MangaSourceDescriptor> {
        require(scope != SearchScope.SOURCE) { "SOURCE deve essere convertito durante la migrazione" }
        val language = scope.language ?: return descriptors
        return descriptors.filter { it.language == language }
    }

    /**
     * Come [descriptorsForScope], escludendo le fonti disabilitate dall'utente. Se la lingua
     * dello scope non ha più fonti attive (scope salvato da prima che l'utente le spegnesse),
     * ripiega su **tutte le fonti attive**, non su quelle disattivate della lingua: una fonte
     * spenta in impostazioni non va mai interrogata. Se l'utente spegnesse tutto, l'elenco
     * completo resta l'ultima rete di sicurezza: la ricerca non deve interrogare zero fonti.
     */
    fun descriptorsForScope(
        scope: SearchScope,
        disabledSourceIds: Set<String>,
    ): List<MangaSourceDescriptor> {
        val enabled = descriptors.filterNot { it.id in disabledSourceIds }.ifEmpty { descriptors }
        val language = scope.language ?: return enabled
        return enabled.filter { it.language == language }.ifEmpty { enabled }
    }

    /**
     * Lingue che hanno almeno una fonte attiva, nell'ordine di [MangaSourceLanguage]. La tab
     * Cerca mostra una chip per ciascuna: filtrare per una lingua senza fonti attive non
     * cambierebbe nulla. Con una sola lingua rimasta la riga di chip sparisce del tutto,
     * perché "Tutte" e quell'unica lingua interrogherebbero le stesse fonti.
     */
    fun languagesWithEnabledSources(disabledSourceIds: Set<String>): List<MangaSourceLanguage> {
        val enabled = descriptors.filterNot { it.id in disabledSourceIds }.ifEmpty { descriptors }
        return MangaSourceLanguage.entries.filter { language ->
            enabled.any { it.language == language }
        }
    }

    /** Lingua della fonte [sourceId] (con fallback sulla fonte di default se sconosciuta). */
    fun languageOf(sourceId: String): MangaSourceLanguage {
        val resolved = resolveSourceId(sourceId)
        return descriptors.first { it.id == resolved }.language
    }

    /**
     * Combina i risultati per-fonte della ricerca aggregata alternandoli round-robin (il 1°
     * di ogni fonte, poi i 2°, ...): le fonti si mescolano invece di accodarsi a blocchi, e
     * l'ordine interno di ciascuna — la rilevanza calcolata dal suo server, che conosce anche
     * i titoli alternativi (es. "demon slayer" → "Kimetsu no Yaiba") — resta intatto.
     * Riordinare lato client per somiglianza col testo la distruggerebbe.
     */
    fun <T> interleaveBySource(resultsPerSource: List<List<T>>): List<T> {
        val combined = ArrayList<T>(resultsPerSource.sumOf { it.size })
        var index = 0
        do {
            var added = false
            for (results in resultsPerSource) {
                results.getOrNull(index)?.let {
                    combined.add(it)
                    added = true
                }
            }
            index++
        } while (added)
        return combined
    }

    fun resolveSourceId(
        sourceId: String?,
        url: String? = null,
    ): String {
        val normalizedSourceId = sourceId
            ?.trim()
            ?.takeIf { candidate -> descriptors.any { it.id == candidate } }
        if (normalizedSourceId != null) {
            return normalizedSourceId
        }
        return sourceIdForUrl(url) ?: MangaSourceIds.DEFAULT
    }

    fun sourceIdForUrl(url: String?): String? {
        val normalizedUrl = url?.trim().orEmpty()
        if (normalizedUrl.isBlank()) {
            return null
        }
        return when {
            MangapillSource.handlesUrl(normalizedUrl) -> MangaSourceIds.MANGAPILL
            HastaTeamSource.handlesUrl(normalizedUrl) -> MangaSourceIds.HASTA_TEAM
            MangaWorldSource.handlesUrl(normalizedUrl) -> MangaSourceIds.MANGA_WORLD
            VyMangaSource.handlesUrl(normalizedUrl) -> MangaSourceIds.VYMANGA
            AsuraScansSource.handlesUrl(normalizedUrl) -> MangaSourceIds.ASURA_SCANS
            DemonicScansSource.handlesUrl(normalizedUrl) -> MangaSourceIds.DEMONIC_SCANS
            TcbScansSource.handlesUrl(normalizedUrl) -> MangaSourceIds.TCB_SCANS
            WeebCentralSource.handlesUrl(normalizedUrl) -> MangaSourceIds.WEEB_CENTRAL
            else -> null
        }
    }

    fun displayName(sourceId: String): String {
        val resolved = resolveSourceId(sourceId)
        return descriptors.firstOrNull { it.id == resolved }?.displayName ?: descriptors.first().displayName
    }

    fun shortDisplayName(sourceId: String): String {
        val resolved = resolveSourceId(sourceId)
        return descriptors.firstOrNull { it.id == resolved }?.shortName ?: descriptors.first().shortName
    }

    fun identityKey(
        sourceId: String,
        mangaUrl: String,
    ): String {
        val resolvedSourceId = resolveSourceId(sourceId, mangaUrl)
        val normalizedUrl = normalizeSeriesUrl(resolvedSourceId, mangaUrl) ?: mangaUrl.trim()
        return "$resolvedSourceId::$normalizedUrl"
    }

    fun identityKeyOrNull(
        sourceId: String?,
        mangaUrl: String?,
        title: String? = null,
    ): String? {
        val normalizedUrl = mangaUrl?.trim()?.takeIf(String::isNotBlank)
        if (normalizedUrl != null) {
            return identityKey(resolveSourceId(sourceId, normalizedUrl), normalizedUrl)
        }
        val normalizedTitle = title?.trim()?.lowercase()?.takeIf(String::isNotBlank) ?: return null
        val resolvedSourceId = resolveSourceId(sourceId)
        return "$resolvedSourceId::title:$normalizedTitle"
    }

    fun normalizeSeriesUrl(
        sourceId: String,
        url: String,
    ): String? {
        val normalizedUrl = url.trim()
        if (normalizedUrl.isBlank()) {
            return null
        }
        return when (resolveSourceId(sourceId, normalizedUrl)) {
            MangaSourceIds.MANGAPILL -> MangapillSource.canonicalSeriesUrl(normalizedUrl)
            MangaSourceIds.HASTA_TEAM -> HastaTeamSource.canonicalSeriesUrl(normalizedUrl)
            MangaSourceIds.MANGA_WORLD -> MangaWorldSource.canonicalSeriesUrl(normalizedUrl)
            MangaSourceIds.VYMANGA -> VyMangaSource.canonicalSeriesUrl(normalizedUrl)
            MangaSourceIds.ASURA_SCANS -> AsuraScansSource.canonicalSeriesUrl(normalizedUrl)
            MangaSourceIds.DEMONIC_SCANS -> DemonicScansSource.canonicalSeriesUrl(normalizedUrl)
            MangaSourceIds.TCB_SCANS -> TcbScansSource.canonicalSeriesUrl(normalizedUrl)
            MangaSourceIds.WEEB_CENTRAL -> WeebCentralSource.canonicalSeriesUrl(normalizedUrl)
            else -> normalizedUrl
        } ?: normalizedUrl
    }

}
