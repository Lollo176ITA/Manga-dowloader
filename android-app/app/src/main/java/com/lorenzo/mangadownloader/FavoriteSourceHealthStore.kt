package com.lorenzo.mangadownloader

import android.content.SharedPreferences
import kotlinx.serialization.Serializable

/**
 * Salute dell'approvvigionamento di una serie preferita: quante volte di fila il controllo
 * aggiornamenti non è riuscito a raggiungerla su **nessuna** fonte, e se il fallback l'ha
 * spostata su un mirror diverso da quello che avevi scelto.
 *
 * È l'unico stato che alimenta gli avvisi in lista: finché tutto funziona resta vuoto e la
 * card del preferito non mostra niente.
 */
@Serializable
data class FavoriteSourceHealth(
    val consecutiveFailures: Int = 0,
    /** Fonte promossa dall'ultimo fallback, se diversa da quella che l'utente aveva scelto. */
    val switchedToSourceId: String? = null,
    val switchedAtMillis: Long = 0L,
)

/**
 * Giri di controllo consecutivi andati a vuoto su tutte le fonti prima di dire all'utente che
 * la serie non è raggiungibile. Sotto questa soglia il silenzio è preferibile: un singolo giro
 * fallito è quasi sempre la rete del telefono, non il sito.
 */
const val UNREACHABLE_FAILURE_THRESHOLD = 3

/**
 * Esito positivo del giro: azzera i fallimenti e registra il cambio fonte solo se il mirror
 * che ha risposto non è quello da cui stavamo leggendo. Pura.
 */
fun recordSourceSuccess(
    current: FavoriteSourceHealth?,
    previousSourceId: String,
    usedSourceId: String,
    nowMillis: Long,
): FavoriteSourceHealth = when {
    usedSourceId == previousSourceId -> FavoriteSourceHealth()
    else -> FavoriteSourceHealth(
        consecutiveFailures = 0,
        switchedToSourceId = usedSourceId,
        switchedAtMillis = nowMillis,
    )
}

/** Esito negativo su tutte le fonti: incrementa il contatore, conserva il resto. Pura. */
fun recordSourceFailure(current: FavoriteSourceHealth?): FavoriteSourceHealth {
    val base = current ?: FavoriteSourceHealth()
    return base.copy(consecutiveFailures = base.consecutiveFailures + 1)
}

/** Tipo di avviso mostrato sulla card di un preferito. */
enum class FavoriteNoticeKind { SOURCE_SWITCHED, UNREACHABLE }

/** Avviso pronto per la UI (testo già in italiano), oppure `null` se non c'è niente da dire. */
data class FavoriteSourceNotice(
    val kind: FavoriteNoticeKind,
    val label: String,
)

/**
 * Traduce la salute in avviso: l'irraggiungibilità ha la precedenza sul cambio fonte (se non
 * la raggiungo più, sapere da dove la leggevo non aiuta). `null` = nessun badge. Pura.
 */
fun favoriteSourceNotice(health: FavoriteSourceHealth?): FavoriteSourceNotice? {
    if (health == null) return null
    if (health.consecutiveFailures >= UNREACHABLE_FAILURE_THRESHOLD) {
        return FavoriteSourceNotice(
            kind = FavoriteNoticeKind.UNREACHABLE,
            label = "Nessuna fonte raggiungibile",
        )
    }
    val switched = health.switchedToSourceId ?: return null
    return FavoriteSourceNotice(
        kind = FavoriteNoticeKind.SOURCE_SWITCHED,
        // Nome per esteso, non la sigla: l'etichetta è una frase ("Ora da VyManga"), e
        // "Ora da VY" non direbbe niente a chi legge.
        label = "Ora da ${MangaSourceCatalog.displayName(switched)}",
    )
}

/**
 * Persistenza della mappa `seriesKey -> `[FavoriteSourceHealth] su [SharedPreferences], con lo
 * stesso pattern tollerante degli altri store dei preferiti (JSON illeggibile → mappa vuota).
 */
class FavoriteSourceHealthStore(private val prefs: SharedPreferences) {

    fun read(): Map<String, FavoriteSourceHealth> =
        prefs.readJson(KEY_FAVORITE_SOURCE_HEALTH_JSON, emptyMap())

    fun write(health: Map<String, FavoriteSourceHealth>) {
        // Le voci "tutto a posto" non si salvano: la mappa resta piccola e assente = sano.
        prefs.writeJson(
            KEY_FAVORITE_SOURCE_HEALTH_JSON,
            health.filterValues { it.consecutiveFailures > 0 || it.switchedToSourceId != null },
        )
    }

    private companion object {
        const val KEY_FAVORITE_SOURCE_HEALTH_JSON = "favorite_source_health_json"
    }
}
