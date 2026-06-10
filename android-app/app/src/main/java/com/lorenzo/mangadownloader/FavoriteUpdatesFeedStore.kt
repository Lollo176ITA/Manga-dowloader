package com.lorenzo.mangadownloader

import android.content.SharedPreferences
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Un evento "è uscito un nuovo capitolo di un preferito", registrato dal [FavoriteUpdatesWorker]
 * nello stesso punto in cui invia la notifica. Alimenta il feed in-app "Aggiornamenti".
 *
 * Ogni campo ha un default per la retro/avanti-compatibilità del JSON, come gli altri store
 * (vedi [FavoriteUpdatesStore]/[FavoriteDescriptionsStore]).
 */
@Serializable
data class FavoriteUpdateEvent(
    val identityKey: String = "",
    val title: String = "",
    val sourceId: String = "",
    val mangaUrl: String = "",
    val chapterLabel: String = "",
    val chapterNumber: String = "",
    val coverUrl: String? = null,
    val timestampMillis: Long = 0L,
    val seen: Boolean = false,
)

/** Quantità massima di eventi tenuti nel feed: tiene piccolo il blob JSON in SharedPreferences. */
const val MAX_FEED_EVENTS = 200

/**
 * Aggiunge un evento in testa al feed (più recente per primo). Funzione **pura** (testabile
 * senza Android/rete). De-duplica su `identityKey + chapterNumber` così una ri-rilevazione dello
 * stesso capitolo (es. dopo una scrittura andata storta) non crea righe doppie, e tronca a
 * [maxEvents] scartando i più vecchi.
 */
fun appendUpdateEvent(
    existing: List<FavoriteUpdateEvent>,
    event: FavoriteUpdateEvent,
    maxEvents: Int = MAX_FEED_EVENTS,
): List<FavoriteUpdateEvent> {
    val withoutDuplicate = existing.filterNot {
        it.identityKey == event.identityKey && it.chapterNumber == event.chapterNumber
    }
    return (listOf(event) + withoutDuplicate).take(maxEvents.coerceAtLeast(1))
}

/** Numero di eventi non ancora visti (per il badge). Pura. */
fun unseenCount(events: List<FavoriteUpdateEvent>): Int = events.count { !it.seen }

/** Marca tutti gli eventi come visti. Pura e idempotente. */
fun markAllSeen(events: List<FavoriteUpdateEvent>): List<FavoriteUpdateEvent> =
    events.map { if (it.seen) it else it.copy(seen = true) }

/** Un gruppo di eventi che condividono lo stesso giorno, con l'etichetta già pronta per la UI. */
data class FavoriteUpdateDay(
    val dayLabel: String,
    val events: List<FavoriteUpdateEvent>,
)

private val DAY_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ITALIAN)

/**
 * Raggruppa gli eventi per giorno (più recente per primo, e ordinati per data decrescente
 * dentro ogni giorno), etichettando "Oggi"/"Ieri"/data estesa. Pura: [zoneId] e [nowMillis]
 * sono iniettabili per rendere i test deterministici.
 */
fun groupEventsByDay(
    events: List<FavoriteUpdateEvent>,
    zoneId: ZoneId = ZoneId.systemDefault(),
    nowMillis: Long = System.currentTimeMillis(),
): List<FavoriteUpdateDay> {
    if (events.isEmpty()) return emptyList()
    val today = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
    val yesterday = today.minusDays(1)
    return events
        .sortedByDescending { it.timestampMillis }
        .groupBy { Instant.ofEpochMilli(it.timestampMillis).atZone(zoneId).toLocalDate() }
        .toList()
        .sortedByDescending { (date, _) -> date }
        .map { (date, dayEvents) ->
            val label = when (date) {
                today -> "Oggi"
                yesterday -> "Ieri"
                else -> date.format(DAY_FORMATTER)
            }
            FavoriteUpdateDay(dayLabel = label, events = dayEvents)
        }
}

/**
 * Persistenza del feed degli aggiornamenti (lista di [FavoriteUpdateEvent]) su [SharedPreferences].
 * Tollerante: JSON illeggibile → lista vuota. Volutamente separato da [FavoriteUpdatesStore]
 * (che tiene solo la baseline "ultimo visto" delle notifiche).
 */
class FavoriteUpdatesFeedStore(private val prefs: SharedPreferences) {

    private val json = Json { ignoreUnknownKeys = true }

    fun read(): List<FavoriteUpdateEvent> = synchronized(LOCK) { readLocked() }

    fun write(events: List<FavoriteUpdateEvent>) {
        synchronized(LOCK) { writeLocked(events) }
    }

    /**
     * Read-transform-write atomico del feed. Il lock è condiviso tra tutte le istanze
     * (ViewModel e [FavoriteUpdatesWorker] girano nello stesso processo): senza, il worker
     * — che tiene il feed in mano per tutto il giro di rete — sovrascriverebbe un
     * "segna tutto come visto" fatto dall'utente nel frattempo, facendo risorgere il badge.
     */
    fun update(
        transform: (List<FavoriteUpdateEvent>) -> List<FavoriteUpdateEvent>,
    ): List<FavoriteUpdateEvent> = synchronized(LOCK) {
        val updated = transform(readLocked())
        writeLocked(updated)
        updated
    }

    private fun readLocked(): List<FavoriteUpdateEvent> {
        val raw = prefs.getString(KEY_FAVORITE_UPDATES_FEED_JSON, null).orEmpty()
        if (raw.isBlank()) {
            return emptyList()
        }
        return try {
            json.decodeFromString<List<FavoriteUpdateEvent>>(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeLocked(events: List<FavoriteUpdateEvent>) {
        prefs.edit()
            .putString(KEY_FAVORITE_UPDATES_FEED_JSON, json.encodeToString(events))
            .apply()
    }

    private companion object {
        const val KEY_FAVORITE_UPDATES_FEED_JSON = "favorite_updates_feed_json"
        val LOCK = Any()
    }
}
