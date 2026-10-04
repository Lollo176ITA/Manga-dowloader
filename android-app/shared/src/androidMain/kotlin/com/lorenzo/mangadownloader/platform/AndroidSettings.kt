package com.lorenzo.mangadownloader.platform

import android.content.Context
import android.content.SharedPreferences
import com.russhwolf.settings.Settings
import com.russhwolf.settings.SharedPreferencesSettings

/**
 * [Settings] sopra le SharedPreferences di sempre: stesso file, stesse chiavi. Tiene il
 * riferimento a [preferences] per i tipi che [Settings] non conosce (gli insiemi di stringhe).
 */
class AndroidPreferencesSettings(
    val preferences: SharedPreferences,
) : Settings by SharedPreferencesSettings(preferences)

/** Le SharedPreferences [name] di sempre, viste come [Settings] multipiattaforma. */
fun Context.settings(name: String): Settings =
    AndroidPreferencesSettings(getSharedPreferences(name, Context.MODE_PRIVATE))
