package com.lorenzo.mangadownloader

import android.content.SharedPreferences
import androidx.core.content.edit
import java.math.BigDecimal
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Stato "ultimo visto" di un manga preferito, per decidere se è uscito un nuovo capitolo.
 * [latestChapterNumber] è il numero capitolo massimo già visto (BigDecimal in formato piano);
 * [status] è lo stato di pubblicazione dell'ultimo controllo (per saltare i conclusi).
 */
@Serializable
data class FavoriteSeenState(
    val latestChapterNumber: String,
    val status: String = MangaPublicationStatus.UNKNOWN.name,
)

/** Esito del confronto tra lo stato visto e i dettagli appena scaricati di un preferito. */
data class FavoriteUpdateResult(
    /** Etichetta del capitolo da notificare, oppure null se non c'è nulla di nuovo (o è la baseline). */
    val newChapterLabel: String?,
    /** Nuovo stato da persistere. */
    val newState: FavoriteSeenState,
)

/**
 * Decide se notificare un nuovo capitolo. Funzione **pura** (testabile senza Android/rete).
 * - Prima volta ([seen] null): registra la baseline **senza** notificare (evita di avvisare
 *   per capitoli già usciti al momento dell'attivazione).
 * - [latestNumber] maggiore del numero visto: notifica [latestLabel].
 * - Altrimenti: nessuna notifica. Lo stato (numero + [status]) viene comunque aggiornato.
 */
fun computeFavoriteUpdate(
    seen: FavoriteSeenState?,
    latestNumber: BigDecimal,
    latestLabel: String,
    status: MangaPublicationStatus,
): FavoriteUpdateResult {
    val newState = FavoriteSeenState(
        latestChapterNumber = latestNumber.stripTrailingZeros().toPlainString(),
        status = status.name,
    )
    if (seen == null) {
        return FavoriteUpdateResult(newChapterLabel = null, newState = newState)
    }
    val previous = seen.latestChapterNumber.toBigDecimalOrNull()
    val isNew = previous == null || latestNumber > previous
    return FavoriteUpdateResult(
        newChapterLabel = if (isNew) latestLabel else null,
        newState = newState,
    )
}

/**
 * Un preferito va ricontrollato online a meno che non lo si sappia già concluso o abbandonato:
 * in quei casi non usciranno nuovi capitoli, quindi si risparmia rete/batteria.
 */
fun shouldPollFavorite(seen: FavoriteSeenState?): Boolean {
    val status = seen?.status
    return status != MangaPublicationStatus.COMPLETED.name &&
        status != MangaPublicationStatus.DROPPED.name
}

/**
 * Persistenza della mappa `identityKey -> `[FavoriteSeenState] su [SharedPreferences].
 * Tollerante: JSON illeggibile → mappa vuota (la baseline si ricostruisce al giro successivo).
 */
class FavoriteUpdatesStore(private val prefs: SharedPreferences) {

    private val json = Json { ignoreUnknownKeys = true }

    fun read(): Map<String, FavoriteSeenState> {
        val raw = prefs.getString(KEY_FAVORITE_UPDATES_JSON, null).orEmpty()
        if (raw.isBlank()) {
            return emptyMap()
        }
        return try {
            json.decodeFromString<Map<String, FavoriteSeenState>>(raw)
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun write(state: Map<String, FavoriteSeenState>) {
        prefs.edit {
            putString(KEY_FAVORITE_UPDATES_JSON, json.encodeToString(state))
        }
    }

    private companion object {
        const val KEY_FAVORITE_UPDATES_JSON = "favorite_updates_seen_json"
    }
}
