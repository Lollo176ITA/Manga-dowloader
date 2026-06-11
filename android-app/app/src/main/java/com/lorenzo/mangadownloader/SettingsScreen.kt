package com.lorenzo.mangadownloader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(
    settings: AppSettings,
    isBiometricAvailable: Boolean,
    isParentalAuthInProgress: Boolean,
    aniListViewerName: String?,
    isAniListConnecting: Boolean,
    padding: PaddingValues,
    onSelectThemeMode: (ThemeMode) -> Unit,
    onToggleDynamicColor: (Boolean) -> Unit,
    onToggleDiscovery: (Boolean) -> Unit,
    onConnectAniList: () -> Unit,
    onDisconnectAniList: () -> Unit,
    onToggleAniListSync: (Boolean) -> Unit,
    onToggleAutoDownload: (Boolean) -> Unit,
    onTriggerChange: (Int) -> Unit,
    onBatchChange: (Int) -> Unit,
    onToggleSmartCleanup: (Boolean) -> Unit,
    onSmartCleanupKeepChange: (Int) -> Unit,
    onToggleStreamingReader: (Boolean) -> Unit,
    onSelectReadingMode: (ReadingMode) -> Unit,
    onSelectReaderPageSpacing: (Int) -> Unit,
    onToggleDoubleTapZoom: (Boolean) -> Unit,
    onToggleParentalControl: (Boolean) -> Unit,
    onRequestChangeParentalPin: () -> Unit,
    onToggleParentalBiometric: (Boolean) -> Unit,
    onToggleLabs: (Boolean) -> Unit,
    onToggleDownloadDevUpdates: (Boolean) -> Unit,
    onToggleHighResImages: (Boolean) -> Unit,
    onTogglePrivacyBrightness: (Boolean) -> Unit,
    onToggleAllowLandscapeRotation: (Boolean) -> Unit,
    onToggleFavoriteNotifications: (Boolean) -> Unit,
    onOpenStorageManager: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenReportProblem: () -> Unit,
    appVersion: String,
    onOpenChangelog: () -> Unit,
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        SettingsSection(title = "Aspetto", icon = Icons.Default.Palette) {
            ThemeModeContent(
                currentMode = settings.themeMode,
                onSelectMode = onSelectThemeMode,
                useDynamicColor = settings.useDynamicColor,
                onToggleDynamicColor = onToggleDynamicColor,
            )
            SettingsDivider()
            ReadingModeContent(
                currentMode = settings.readingMode,
                onSelectMode = onSelectReadingMode,
            )
            SettingsDivider()
            ReaderPageSpacingContent(
                currentDp = settings.readerPageSpacingDp,
                onSelect = onSelectReaderPageSpacing,
            )
            SettingsDivider()
            DoubleTapZoomContent(
                enabled = settings.doubleTapZoomEnabled,
                onToggle = onToggleDoubleTapZoom,
            )
        }

        SettingsSection(title = "Download e lettura", icon = Icons.Default.Download) {
            StreamingReaderContent(
                enabled = settings.streamingReaderEnabled,
                onToggle = onToggleStreamingReader,
            )
            SettingsDivider()
            AutoDownloadContent(
                enabled = settings.autoDownloadEnabled,
                triggerChapters = settings.autoDownloadTriggerChapters,
                batchSize = settings.autoDownloadBatchSize,
                onToggle = onToggleAutoDownload,
                onTriggerChange = onTriggerChange,
                onBatchChange = onBatchChange,
            )
            SettingsDivider()
            SmartCleanupContent(
                enabled = settings.smartCleanupEnabled,
                keepPrevious = settings.smartCleanupKeepPreviousChapters,
                onToggle = onToggleSmartCleanup,
                onKeepChange = onSmartCleanupKeepChange,
            )
            SettingsDivider()
            StorageManagerContent(onOpenStorageManager = onOpenStorageManager)
        }

        SettingsSection(title = "App", icon = Icons.Default.Settings) {
            DiscoveryContent(
                enabled = settings.discoveryEnabled,
                onToggle = onToggleDiscovery,
            )
            SettingsDivider()
            AniListAccountContent(
                viewerName = aniListViewerName,
                isConnecting = isAniListConnecting,
                syncEnabled = settings.aniListSyncEnabled,
                onConnect = onConnectAniList,
                onDisconnect = onDisconnectAniList,
                onToggleSync = onToggleAniListSync,
            )
            SettingsDivider()
            FavoriteNotificationsContent(
                enabled = settings.favoriteNewChapterNotificationsEnabled,
                onToggle = onToggleFavoriteNotifications,
            )
            SettingsDivider()
            ParentalControlContent(
                parentalControlEnabled = settings.parentalControlEnabled,
                biometricEnabled = settings.parentalBiometricEnabled,
                isBiometricAvailable = isBiometricAvailable,
                isAuthInProgress = isParentalAuthInProgress,
                onToggleParental = onToggleParentalControl,
                onRequestChangePin = onRequestChangeParentalPin,
                onToggleBiometric = onToggleParentalBiometric,
            )
        }

        SettingsSection(title = "Backup", icon = Icons.Default.Backup) {
            BackupContent(onOpenBackup = onOpenBackup)
        }

        SettingsSection(title = "Labs (sperimentale)", icon = Icons.Default.Science) {
            LabsContent(
                labsEnabled = settings.labsEnabled,
                downloadDevUpdates = settings.downloadDevUpdates,
                highResImages = settings.highResImages,
                privacyBrightnessEnabled = settings.privacyBrightnessEnabled,
                allowLandscapeRotation = settings.allowLandscapeRotation,
                onToggleLabs = onToggleLabs,
                onToggleDownloadDevUpdates = onToggleDownloadDevUpdates,
                onToggleHighResImages = onToggleHighResImages,
                onTogglePrivacyBrightness = onTogglePrivacyBrightness,
                onToggleAllowLandscapeRotation = onToggleAllowLandscapeRotation,
            )
        }

        SettingsSection(title = "Informazioni", icon = Icons.Default.Info) {
            InfoContent(
                appVersion = appVersion,
                onOpenChangelog = onOpenChangelog,
            )
            SettingsDivider()
            ReportProblemContent(onOpenReportProblem = onOpenReportProblem)
        }
    }
}
