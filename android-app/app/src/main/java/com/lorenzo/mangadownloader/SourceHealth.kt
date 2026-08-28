package com.lorenzo.mangadownloader

import android.content.SharedPreferences
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.serialization.Serializable

/**
 * Salute di una **fonte** (il sito), distinta da [FavoriteSourceHealth] che riguarda una
 * singola serie preferita. Qui la domanda è una sola: questo sito sta rispondendo?
 *
 * Nasce da un caso reale: con l'origin di VyManga giù, Cloudflare rispondeva 522 dopo ~20
 * secondi. La ricerca aggregata aspettava *tutte* le fonti prima di mostrare qualcosa, quindi
 * ogni ricerca restava in caricamento venti secondi buoni per una fonte che non poteva
 * rispondere — e i risultati delle altre sette restavano in ostaggio.
 *
 * Il rimedio è un interruttore automatico: dopo [SOURCE_UNREACHABLE_THRESHOLD] fallimenti di
 * fila la fonte viene saltata per [SOURCE_COOLDOWN_MILLIS], poi riprovata da sola. Nessuno
 * spegne niente in modo permanente: un sito giù stamattina è quasi sempre su stasera, e una
 * fonte disattivata per sempre sarebbe un danno peggiore del rallentamento che evita.
 */
@Serializable
data class SourceReachability(
    /** Tentativi consecutivi andati a vuoto. Un successo lo riporta a zero. */
    val consecutiveFailures: Int = 0,
    /** Quando è iniziato il guasto: il primo fallimento della serie corrente. */
    val failingSinceMillis: Long = 0L,
    /** Ultimo fallimento: da qui si misura il cooldown. */
    val lastFailureAtMillis: Long = 0L,
    /** Ultima volta che la fonte ha risposto davvero. 0 = mai (in questa installazione). */
    val lastSuccessAtMillis: Long = 0L,
)

/**
 * Fallimenti di fila prima di saltare la fonte. Stessa soglia degli avvisi sui preferiti
 * ([UNREACHABLE_FAILURE_THRESHOLD]): sotto i tre tentativi il colpevole è quasi sempre la
 * rete del telefono, non il sito, e accusare il sito sbagliato è peggio che tacere.
 */
const val SOURCE_UNREACHABLE_THRESHOLD = 3

/** Per quanto si salta una fonte guasta prima di riprovarla. */
const val SOURCE_COOLDOWN_MILLIS = 10 * 60_000L

/**
 * Quanto tempo si concede a una singola fonte in una ricerca aggregata. Oltre, i suoi
 * risultati non interessano più: le altre fonti hanno già risposto e la barra di caricamento
 * non può restare appesa alla più lenta. Il valore è largo per una rete mobile lenta ma ben
 * sotto i timeout di OkHttp ([SharedHttpClient]), che da soli lascerebbero passare minuti.
 */
const val SOURCE_SEARCH_BUDGET_MILLIS = 12_000L

/**
 * Il tentativo è fallito perché **la fonte** non risponde? Solo questi guasti muovono
 * l'interruttore: un 404 (contenuto rimosso) o un parsing andato storto su una pagina strana
 * non dicono niente sulla salute del sito, e saltarlo per quello significherebbe togliere
 * all'utente una fonte perfettamente viva. Pura.
 */
fun isSourceOutage(exc: Throwable): Boolean {
    if (exc is SocketTimeoutException || exc is UnknownHostException || exc is SSLException) {
        return true
    }
    if (exc !is IOException) return false
    val status = HTTP_STATUS_IN_MESSAGE.find(exc.message.orEmpty())
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
    // Nessuno status nel messaggio = errore di trasporto (connessione rifiutata, reset).
    return status == null || status >= 500 || status == 429
}

/** Esito positivo: azzera il guasto e ricorda quando la fonte ha risposto. Pura. */
fun recordSourceProbeSuccess(now: Long): SourceReachability =
    SourceReachability(lastSuccessAtMillis = now)

/** Esito negativo: incrementa il contatore conservando l'inizio del guasto. Pura. */
fun recordSourceProbeFailure(current: SourceReachability?, now: Long): SourceReachability {
    val base = current ?: SourceReachability()
    return base.copy(
        consecutiveFailures = base.consecutiveFailures + 1,
        failingSinceMillis = if (base.consecutiveFailures == 0) now else base.failingSinceMillis,
        lastFailureAtMillis = now,
    )
}

/**
 * La fonte va saltata adesso? Solo se è oltre soglia **e** siamo ancora dentro il cooldown:
 * scaduto quello si concede una sonda, ed è così che la fonte rientra da sola senza che
 * nessuno debba riaccenderla. Pura.
 */
fun isSourceSkipped(health: SourceReachability?, now: Long): Boolean {
    if (health == null || health.consecutiveFailures < SOURCE_UNREACHABLE_THRESHOLD) return false
    return now - health.lastFailureAtMillis < SOURCE_COOLDOWN_MILLIS
}

/**
 * Le fonti da interrogare davvero in un giro di ricerca: [descriptors] meno quelle che
 * l'interruttore sta saltando. Se fossero tutte in cooldown si interrogano comunque tutte —
 * una tab Cerca che non cerca più niente sarebbe un guasto peggiore della lentezza che
 * l'interruttore evita, e in quel caso il colpevole è quasi sempre la rete del telefono.
 * Pura.
 */
fun sourcesToQuery(
    descriptors: List<MangaSourceDescriptor>,
    health: Map<String, SourceReachability>,
    now: Long,
): List<MangaSourceDescriptor> = descriptors
    .filterNot { isSourceSkipped(health[it.id], now) }
    .ifEmpty { descriptors }

/**
 * Da quando la fonte è considerata irraggiungibile, o `null` se sta rispondendo. A differenza
 * di [isSourceSkipped] questo non scade col cooldown: finché non risponde di nuovo, l'utente
 * deve continuare a vedere l'avviso nelle impostazioni. Pura.
 */
fun sourceUnreachableSince(health: SourceReachability?, now: Long): Long? {
    if (health == null || health.consecutiveFailures < SOURCE_UNREACHABLE_THRESHOLD) return null
    return health.failingSinceMillis
}

/**
 * L'avviso da mostrare sotto il nome della fonte in impostazioni, o `null` se va tutto bene.
 * Include da quanto non risponde: "non raggiungibile da un'ora" è un'informazione diversa da
 * "non raggiungibile da tre giorni" — la prima si aspetta, la seconda si segnala. Pura.
 */
fun sourceUnreachableLabel(health: SourceReachability?, now: Long): String? {
    sourceUnreachableSince(health, now) ?: return null
    val lastSuccess = health?.lastSuccessAtMillis ?: 0L
    if (lastSuccess <= 0L) return "Non raggiungibile"
    return "Non raggiungibile: ultima risposta ${elapsedLabel(now - lastSuccess)} fa"
}

/**
 * Gli avvisi da mostrare nella sezione "Fonti", per `sourceId`. Vuoto anche quando **tutte**
 * le fonti risultano irraggiungibili: quello non è il caso di otto siti caduti insieme, è il
 * telefono senza rete, e riempire di rosso le impostazioni manderebbe l'utente a spegnere
 * fonti che non hanno niente che non va. Pura.
 */
fun sourceUnreachableLabels(
    health: Map<String, SourceReachability>,
    now: Long,
    sourceIds: List<String> = MangaSourceCatalog.descriptors.map { it.id },
): Map<String, String> {
    val labels = sourceIds.mapNotNull { id ->
        sourceUnreachableLabel(health[id], now)?.let { id to it }
    }
    return if (labels.size == sourceIds.size) emptyMap() else labels.toMap()
}

/** "5 min" / "1 h" / "3 g": approssimazione volutamente grossolana, è un avviso non un log. */
private fun elapsedLabel(elapsedMillis: Long): String {
    val minutes = elapsedMillis / 60_000L
    return when {
        minutes < 60 -> "${minutes.coerceAtLeast(1)} min"
        minutes < 60 * 24 -> "${minutes / 60} h"
        else -> "${minutes / (60 * 24)} g"
    }
}

private val HTTP_STATUS_IN_MESSAGE = Regex("""HTTP (\d{3})""")

/**
 * Persistenza di `sourceId -> `[SourceReachability]. Sopravvive alla chiusura dell'app apposta:
 * l'interruttore deve valere anche per la ricerca fatta appena riaperta, altrimenti ogni
 * riavvio ricomincerebbe ad aspettare la fonte morta.
 */
class SourceHealthStore(private val prefs: SharedPreferences) {

    fun read(): Map<String, SourceReachability> =
        prefs.readJson(KEY_SOURCE_HEALTH_JSON, emptyMap())

    fun write(health: Map<String, SourceReachability>) {
        // Le fonti che non hanno mai dato problemi non si salvano: assente = tutto a posto.
        // Una fonte guarita invece resta, con la data in cui ha ripreso a rispondere: al
        // prossimo guasto è quella che permette di dire "ultima risposta due giorni fa".
        prefs.writeJson(
            KEY_SOURCE_HEALTH_JSON,
            health.filterValues { it.consecutiveFailures > 0 || it.lastSuccessAtMillis > 0L },
        )
    }

    private companion object {
        const val KEY_SOURCE_HEALTH_JSON = "source_reachability_json"
    }
}
