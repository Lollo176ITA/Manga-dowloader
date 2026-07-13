package com.lorenzo.mangadownloader

import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Persistenza di [AppSettings] su [SharedPreferences]. Estratta da `MangaViewModel` per
 * isolare l'I/O delle impostazioni (lettura con default/coerzioni, scrittura) dal resto
 * della logica. Le chiavi non-impostazioni (posizione reader per-serie, ultimo check
 * aggiornamenti) restano nel ViewModel perché appartengono ad altri ambiti.
 */
class SettingsStore(private val prefs: SharedPreferences) {

    fun read(): AppSettings {
        val localSettings = AppSettings(
            parentalPinConfigured = prefs.getBoolean(KEY_PARENTAL_PIN_CONFIGURED, false),
            parentalPinSalt = prefs.getString(KEY_PARENTAL_PIN_SALT, null),
            parentalPinHash = prefs.getString(KEY_PARENTAL_PIN_HASH, null),
            tutorialCompleted = prefs.getBoolean(KEY_TUTORIAL_COMPLETED, false),
            aniListSyncEnabled = prefs.getBoolean(KEY_ANILIST_SYNC_ENABLED, true),
        )
        val storedJson = prefs.getString(KEY_SETTINGS_JSON, null)
        val portableSettings = storedJson
            ?.takeIf(String::isNotBlank)
            ?.let(::decodeSettingsBackup)
            ?: readLegacySettings().also { legacy ->
                // Prima lettura dopo l'aggiornamento (o JSON locale corrotto): importa le
                // vecchie chiavi e riscrive un payload valido. Le vecchie chiavi restano per
                // consentire un ulteriore recupero se il JSON venisse danneggiato.
                prefs.edit { putString(KEY_SETTINGS_JSON, encodeSettingsBackup(legacy)) }
            }
        return portableSettings.applyTo(localSettings)
    }

    /**
     * Importa le chiavi usate fino alla migrazione al payload JSON. Non applica coercizioni:
     * passare sempre il risultato a [SettingsBackup.applyTo], unica normalizzazione condivisa.
     */
    private fun readLegacySettings(): SettingsBackup = SettingsBackup(
        searchScope = prefs.getString(KEY_SEARCH_SCOPE, SearchScope.ITA.name) ?: SearchScope.ITA.name,
        searchSourceId = prefs.getString(KEY_SEARCH_SOURCE_ID, MangaSourceIds.DEFAULT)
            ?: MangaSourceIds.DEFAULT,
        autoDownloadEnabled = prefs.getBoolean(KEY_AUTO_DOWNLOAD_ENABLED, false),
        autoDownloadTriggerChapters = prefs.getInt(KEY_AUTO_DOWNLOAD_TRIGGER, 3),
        autoDownloadBatchSize = prefs.getInt(KEY_AUTO_DOWNLOAD_BATCH, 3),
        smartCleanupEnabled = prefs.getBoolean(KEY_SMART_CLEANUP_ENABLED, false),
        smartCleanupKeepPreviousChapters = prefs.getInt(KEY_SMART_CLEANUP_KEEP_PREVIOUS, 3),
        streamingReaderEnabled = prefs.getBoolean(KEY_STREAMING_READER_ENABLED, false),
        parentalControlEnabled = prefs.getBoolean(KEY_PARENTAL_CONTROL_ENABLED, false),
        parentalBiometricEnabled = prefs.getBoolean(KEY_PARENTAL_BIOMETRIC_ENABLED, false),
        labsEnabled = prefs.getBoolean(KEY_LABS_ENABLED, false),
        downloadDevUpdates = prefs.getBoolean(KEY_DOWNLOAD_DEV_UPDATES, false),
        highResImages = prefs.getBoolean(KEY_HIGH_RES_IMAGES, false),
        privacyBrightnessEnabled = prefs.getBoolean(KEY_PRIVACY_BRIGHTNESS_ENABLED, false),
        readerBrightness = prefs.getFloat(KEY_READER_BRIGHTNESS, 1f),
        readingMode = prefs.getString(KEY_READING_MODE, ReadingMode.VERTICAL.name)
            ?: ReadingMode.VERTICAL.name,
        readerPageSpacingDp = prefs.getInt(KEY_READER_PAGE_SPACING, DEFAULT_READER_PAGE_SPACING_DP),
        doubleTapZoomEnabled = prefs.getBoolean(KEY_DOUBLE_TAP_ZOOM, false),
        keepScreenOnEnabled = prefs.getBoolean(KEY_KEEP_SCREEN_ON, true),
        allowLandscapeRotation = prefs.getBoolean(KEY_ALLOW_LANDSCAPE_ROTATION, false),
        themeMode = prefs.getString(KEY_THEME_MODE, ThemeMode.AUTO.name) ?: ThemeMode.AUTO.name,
        useDynamicColor = prefs.getBoolean(KEY_USE_DYNAMIC_COLOR, false),
        favoriteNewChapterNotificationsEnabled = prefs.getBoolean(KEY_FAVORITE_NOTIFICATIONS, false),
        favoriteSort = prefs.getString(KEY_FAVORITE_SORT, FavoriteSort.DATE_ADDED.name)
            ?: FavoriteSort.DATE_ADDED.name,
        librarySort = prefs.getString(KEY_LIBRARY_SORT, LibrarySort.TITLE_ASC.name)
            ?: LibrarySort.TITLE_ASC.name,
        homeBlockOrder = readLegacyHomeBlocks(KEY_HOME_BLOCK_ORDER).map { it.name },
        hiddenHomeBlocks = readLegacyHomeBlocks(KEY_HOME_HIDDEN_BLOCKS).map { it.name },
    )

    fun persist(settings: AppSettings) {
        prefs.edit {
            putString(KEY_SETTINGS_JSON, encodeSettingsBackup(settings.toBackup()))
            putBoolean(KEY_PARENTAL_PIN_CONFIGURED, settings.parentalPinConfigured)
            putString(KEY_PARENTAL_PIN_SALT, settings.parentalPinSalt)
            putString(KEY_PARENTAL_PIN_HASH, settings.parentalPinHash)
            putBoolean(KEY_TUTORIAL_COMPLETED, settings.tutorialCompleted)
            putBoolean(KEY_ANILIST_SYNC_ENABLED, settings.aniListSyncEnabled)
        }
    }

    /** Decodifica tollerante: JSON invalido → lista vuota; nomi ignoti scartati. */
    private fun readLegacyHomeBlocks(key: String): List<HomeBlock> =
        prefs.readJson<List<String>>(key, emptyList())
            .mapNotNull { name -> runCatching { HomeBlock.valueOf(name) }.getOrNull() }

    companion object {
        const val PREFS_NAME = "manga_downloader_prefs"
        const val KEY_SETTINGS_JSON = "settings_json_v1"
        const val KEY_SEARCH_SCOPE = "search_scope"
        const val KEY_SEARCH_SOURCE_ID = "search_source_id"
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
        const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        const val KEY_ALLOW_LANDSCAPE_ROTATION = "allow_landscape_rotation"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_USE_DYNAMIC_COLOR = "use_dynamic_color"
        const val KEY_TUTORIAL_COMPLETED = "tutorial_completed"
        const val KEY_FAVORITE_NOTIFICATIONS = "favorite_new_chapter_notifications"
        const val KEY_FAVORITE_SORT = "favorite_sort"
        const val KEY_LIBRARY_SORT = "library_sort"
        const val KEY_ANILIST_SYNC_ENABLED = "anilist_sync_enabled"
        const val KEY_HOME_BLOCK_ORDER = "home_block_order"
        const val KEY_HOME_HIDDEN_BLOCKS = "home_hidden_blocks"
    }
}
