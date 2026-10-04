package com.lorenzo.mangadownloader.data.store

import com.russhwolf.settings.Settings
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/*
 * Ponte tra le API di SharedPreferences usate dagli store e [Settings] multipiattaforma: i nomi
 * restano quelli di prima, così gli store cambiano solo il tipo e le chiavi/formati su disco non
 * cambiano. Su Android [Settings] avvolge le stesse SharedPreferences di sempre.
 */

/** Come `SharedPreferences.edit { }`: con [Settings] ogni scrittura è già immediata. */
inline fun Settings.edit(block: Settings.() -> Unit) = block()

/** Come `SharedPreferences.getString(key, null)`. */
fun Settings.getString(key: String, defaultValue: String?): String? = getStringOrNull(key) ?: defaultValue

/** Come `SharedPreferences.Editor.putString(key, value)`: `null` cancella la chiave. */
fun Settings.putNullableString(key: String, value: String?) {
    if (value == null) remove(key) else putString(key, value)
}

private val stringListSerializer = ListSerializer(String.serializer())

/**
 * Insieme di stringhe: nativo dove la piattaforma lo supporta (Android, stesso formato di prima),
 * altrimenti salvato come array JSON.
 */
fun Settings.getStringSet(key: String): Set<String> {
    platformGetStringSet(this, key)?.let { return it }
    val raw = getStringOrNull(key) ?: return emptySet()
    return runCatching { Json.decodeFromString(stringListSerializer, raw).toSet() }.getOrDefault(emptySet())
}

fun Settings.putStringSet(key: String, value: Set<String>) {
    if (platformPutStringSet(this, key, value)) return
    putString(key, Json.encodeToString(stringListSerializer, value.toList()))
}

/** L'insieme salvato in formato nativo, o `null` se [settings] non ne ha uno. */
internal expect fun platformGetStringSet(settings: Settings, key: String): Set<String>?

/** Scrive l'insieme in formato nativo; `false` se la piattaforma non lo supporta. */
internal expect fun platformPutStringSet(settings: Settings, key: String, value: Set<String>): Boolean
