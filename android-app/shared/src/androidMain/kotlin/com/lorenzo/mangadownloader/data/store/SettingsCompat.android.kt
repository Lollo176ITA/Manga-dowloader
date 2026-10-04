package com.lorenzo.mangadownloader.data.store

import com.lorenzo.mangadownloader.platform.AndroidPreferencesSettings
import com.russhwolf.settings.Settings

internal actual fun platformGetStringSet(settings: Settings, key: String): Set<String>? =
    (settings as? AndroidPreferencesSettings)?.preferences?.getStringSet(key, emptySet())?.toSet()

internal actual fun platformPutStringSet(settings: Settings, key: String, value: Set<String>): Boolean {
    val preferences = (settings as? AndroidPreferencesSettings)?.preferences ?: return false
    preferences.edit().putStringSet(key, value).apply()
    return true
}
