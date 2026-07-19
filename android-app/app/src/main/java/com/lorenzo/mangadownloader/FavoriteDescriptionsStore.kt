package com.lorenzo.mangadownloader

import android.content.SharedPreferences

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

    fun read(): Map<String, String> =
        prefs.readJson(KEY_FAVORITE_DESCRIPTIONS_JSON, emptyMap())

    fun write(descriptions: Map<String, String>) {
        prefs.writeJson(KEY_FAVORITE_DESCRIPTIONS_JSON, descriptions)
    }

    private companion object {
        const val KEY_FAVORITE_DESCRIPTIONS_JSON = "favorite_descriptions_json"
    }
}
