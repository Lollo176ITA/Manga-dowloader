package com.lorenzo.mangadownloader

import android.content.SharedPreferences

/**
 * Persistenza di [AppSettings] su [SharedPreferences]. Estratta da `MangaViewModel` per
 * isolare l'I/O delle impostazioni (lettura con default/coerzioni, scrittura) dal resto
 * della logica. Le chiavi non-impostazioni (posizione reader per-serie, ultimo check
 * aggiornamenti) restano nel ViewModel perché appartengono ad altri ambiti.
 */
class SettingsStore(private val prefs: SharedPreferences) {

    fun read(): AppSettings {
        return AppSettings(
            searchSourceId = MangaSourceCatalog.resolveSourceId(
                prefs.getString(KEY_SEARCH_SOURCE_ID, null),
            ),
            discoveryEnabled = prefs.getBoolean(KEY_DISCOVERY_ENABLED, false),
            autoDownloadEnabled = prefs.getBoolean(KEY_AUTO_DOWNLOAD_ENABLED, false),
            autoDownloadTriggerChapters = prefs
                .getInt(KEY_AUTO_DOWNLOAD_TRIGGER, 3)
                .coerceAtLeast(1),
            autoDownloadBatchSize = prefs
                .getInt(KEY_AUTO_DOWNLOAD_BATCH, 3)
                .coerceAtLeast(1),
            smartCleanupEnabled = prefs.getBoolean(KEY_SMART_CLEANUP_ENABLED, false),
            smartCleanupKeepPreviousChapters = prefs
                .getInt(KEY_SMART_CLEANUP_KEEP_PREVIOUS, 3)
                .coerceAtLeast(0),
            streamingReaderEnabled = prefs.getBoolean(KEY_STREAMING_READER_ENABLED, false),
            parentalControlEnabled = prefs.getBoolean(KEY_PARENTAL_CONTROL_ENABLED, false),
            parentalPinConfigured = prefs.getBoolean(KEY_PARENTAL_PIN_CONFIGURED, false),
            parentalBiometricEnabled = prefs.getBoolean(KEY_PARENTAL_BIOMETRIC_ENABLED, false),
            parentalPinSalt = prefs.getString(KEY_PARENTAL_PIN_SALT, null),
            parentalPinHash = prefs.getString(KEY_PARENTAL_PIN_HASH, null),
            labsEnabled = prefs.getBoolean(KEY_LABS_ENABLED, false),
            downloadDevUpdates = prefs.getBoolean(KEY_DOWNLOAD_DEV_UPDATES, false),
            highResImages = prefs.getBoolean(KEY_HIGH_RES_IMAGES, false),
            privacyBrightnessEnabled = prefs.getBoolean(KEY_PRIVACY_BRIGHTNESS_ENABLED, false),
            readerBrightness = prefs.getFloat(KEY_READER_BRIGHTNESS, 1f).coerceIn(0f, 1f),
            readingMode = runCatching {
                ReadingMode.valueOf(
                    prefs.getString(KEY_READING_MODE, ReadingMode.VERTICAL.name) ?: ReadingMode.VERTICAL.name,
                )
            }.getOrDefault(ReadingMode.VERTICAL),
            readerPageSpacingDp = prefs
                .getInt(KEY_READER_PAGE_SPACING, DEFAULT_READER_PAGE_SPACING_DP)
                .coerceIn(0, MAX_READER_PAGE_SPACING_DP),
            doubleTapZoomEnabled = prefs.getBoolean(KEY_DOUBLE_TAP_ZOOM, false),
            allowLandscapeRotation = prefs.getBoolean(KEY_ALLOW_LANDSCAPE_ROTATION, false),
            themeMode = runCatching {
                ThemeMode.valueOf(
                    prefs.getString(KEY_THEME_MODE, ThemeMode.AUTO.name) ?: ThemeMode.AUTO.name,
                )
            }.getOrDefault(ThemeMode.AUTO),
            useDynamicColor = prefs.getBoolean(KEY_USE_DYNAMIC_COLOR, false),
            tutorialCompleted = prefs.getBoolean(KEY_TUTORIAL_COMPLETED, false),
            favoriteNewChapterNotificationsEnabled = prefs.getBoolean(KEY_FAVORITE_NOTIFICATIONS, false),
            favoriteSort = runCatching {
                FavoriteSort.valueOf(
                    prefs.getString(KEY_FAVORITE_SORT, FavoriteSort.DATE_ADDED.name)
                        ?: FavoriteSort.DATE_ADDED.name,
                )
            }.getOrDefault(FavoriteSort.DATE_ADDED),
            aniListSyncEnabled = prefs.getBoolean(KEY_ANILIST_SYNC_ENABLED, true),
        )
    }

    fun persist(settings: AppSettings) {
        prefs.edit()
            .putString(KEY_SEARCH_SOURCE_ID, settings.searchSourceId)
            .putBoolean(KEY_DISCOVERY_ENABLED, settings.discoveryEnabled)
            .putBoolean(KEY_AUTO_DOWNLOAD_ENABLED, settings.autoDownloadEnabled)
            .putInt(KEY_AUTO_DOWNLOAD_TRIGGER, settings.autoDownloadTriggerChapters)
            .putInt(KEY_AUTO_DOWNLOAD_BATCH, settings.autoDownloadBatchSize)
            .putBoolean(KEY_SMART_CLEANUP_ENABLED, settings.smartCleanupEnabled)
            .putInt(KEY_SMART_CLEANUP_KEEP_PREVIOUS, settings.smartCleanupKeepPreviousChapters)
            .putBoolean(KEY_STREAMING_READER_ENABLED, settings.streamingReaderEnabled)
            .putBoolean(KEY_PARENTAL_CONTROL_ENABLED, settings.parentalControlEnabled)
            .putBoolean(KEY_PARENTAL_PIN_CONFIGURED, settings.parentalPinConfigured)
            .putBoolean(KEY_PARENTAL_BIOMETRIC_ENABLED, settings.parentalBiometricEnabled)
            .putString(KEY_PARENTAL_PIN_SALT, settings.parentalPinSalt)
            .putString(KEY_PARENTAL_PIN_HASH, settings.parentalPinHash)
            .putBoolean(KEY_LABS_ENABLED, settings.labsEnabled)
            .putBoolean(KEY_DOWNLOAD_DEV_UPDATES, settings.downloadDevUpdates)
            .putBoolean(KEY_HIGH_RES_IMAGES, settings.highResImages)
            .putBoolean(KEY_PRIVACY_BRIGHTNESS_ENABLED, settings.privacyBrightnessEnabled)
            .putFloat(KEY_READER_BRIGHTNESS, settings.readerBrightness.coerceIn(0f, 1f))
            .putString(KEY_READING_MODE, settings.readingMode.name)
            .putInt(KEY_READER_PAGE_SPACING, settings.readerPageSpacingDp)
            .putBoolean(KEY_DOUBLE_TAP_ZOOM, settings.doubleTapZoomEnabled)
            .putBoolean(KEY_ALLOW_LANDSCAPE_ROTATION, settings.allowLandscapeRotation)
            .putString(KEY_THEME_MODE, settings.themeMode.name)
            .putBoolean(KEY_USE_DYNAMIC_COLOR, settings.useDynamicColor)
            .putBoolean(KEY_TUTORIAL_COMPLETED, settings.tutorialCompleted)
            .putBoolean(KEY_FAVORITE_NOTIFICATIONS, settings.favoriteNewChapterNotificationsEnabled)
            .putString(KEY_FAVORITE_SORT, settings.favoriteSort.name)
            .putBoolean(KEY_ANILIST_SYNC_ENABLED, settings.aniListSyncEnabled)
            .apply()
    }

    companion object {
        const val PREFS_NAME = "manga_downloader_prefs"
        const val KEY_SEARCH_SOURCE_ID = "search_source_id"
        const val KEY_DISCOVERY_ENABLED = "discovery_enabled"
        const val KEY_AUTO_DOWNLOAD_ENABLED = "auto_download_enabled"
        const val KEY_AUTO_DOWNLOAD_TRIGGER = "auto_download_trigger"
        const val KEY_AUTO_DOWNLOAD_BATCH = "auto_download_batch"
        const val KEY_SMART_CLEANUP_ENABLED = "smart_cleanup_enabled"
        const val KEY_SMART_CLEANUP_KEEP_PREVIOUS = "smart_cleanup_keep_previous"
        const val KEY_STREAMING_READER_ENABLED = "streaming_reader_enabled"
        const val KEY_PARENTAL_CONTROL_ENABLED = "parental_control_enabled"
        const val KEY_PARENTAL_PIN_CONFIGURED = "parental_pin_configured"
        const val KEY_PARENTAL_BIOMETRIC_ENABLED = "parental_biometric_enabled"
        const val KEY_PARENTAL_PIN_SALT = "parental_pin_salt"
        const val KEY_PARENTAL_PIN_HASH = "parental_pin_hash"
        const val KEY_LABS_ENABLED = "labs_enabled"
        const val KEY_DOWNLOAD_DEV_UPDATES = "download_dev_updates"
        const val KEY_HIGH_RES_IMAGES = "high_res_images"
        const val KEY_PRIVACY_BRIGHTNESS_ENABLED = "privacy_brightness_enabled"
        const val KEY_READER_BRIGHTNESS = "reader_brightness"
        const val KEY_READING_MODE = "reading_mode"
        const val KEY_READER_PAGE_SPACING = "reader_page_spacing_dp"
        const val KEY_DOUBLE_TAP_ZOOM = "double_tap_zoom"
        const val KEY_ALLOW_LANDSCAPE_ROTATION = "allow_landscape_rotation"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_USE_DYNAMIC_COLOR = "use_dynamic_color"
        const val KEY_TUTORIAL_COMPLETED = "tutorial_completed"
        const val KEY_FAVORITE_NOTIFICATIONS = "favorite_new_chapter_notifications"
        const val KEY_FAVORITE_SORT = "favorite_sort"
        const val KEY_ANILIST_SYNC_ENABLED = "anilist_sync_enabled"
    }
}
