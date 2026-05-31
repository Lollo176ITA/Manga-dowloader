package com.lorenzo.mangadownloader

import android.content.SharedPreferences
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persistenza su disco delle trame (descrizioni) dei manga **preferiti**, per `identityKey`.
 * Così il pulsante info dei preferiti è pronto e istantaneo anche dopo il riavvio dell'app,
 * senza ri-scaricare la pagina — è solo testo, quindi il costo è trascurabile.
 *
 * Scritto sia in foreground (quando apri un preferito) sia dal `FavoriteUpdatesWorker` (che
 * scarica comunque i dettagli dei preferiti). Volutamente **separato** da [FavoriteUpdatesStore]
 * per non interferire con la baseline delle notifiche. Tollerante: JSON illeggibile → vuoto.
 */
class FavoriteDescriptionsStore(private val prefs: SharedPreferences) {

    private val json = Json { ignoreUnknownKeys = true }

    fun read(): Map<String, String> {
        val raw = prefs.getString(KEY_FAVORITE_DESCRIPTIONS_JSON, null).orEmpty()
        if (raw.isBlank()) {
            return emptyMap()
        }
        return try {
            json.decodeFromString<Map<String, String>>(raw)
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun write(descriptions: Map<String, String>) {
        prefs.edit()
            .putString(KEY_FAVORITE_DESCRIPTIONS_JSON, json.encodeToString(descriptions))
            .apply()
    }

    private companion object {
        const val KEY_FAVORITE_DESCRIPTIONS_JSON = "favorite_descriptions_json"
    }
}
