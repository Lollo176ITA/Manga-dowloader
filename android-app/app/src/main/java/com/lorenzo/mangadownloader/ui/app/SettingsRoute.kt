package com.lorenzo.mangadownloader.ui.app

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import com.lorenzo.mangadownloader.BuildConfig
import com.lorenzo.mangadownloader.app.MangaUiState
import com.lorenzo.mangadownloader.app.MangaViewModel
import com.lorenzo.mangadownloader.data.anilist.AniListAuth
import com.lorenzo.mangadownloader.ui.settings.SettingsScreen
import kotlinx.coroutines.launch

/** La schermata Impostazioni collegata al ViewModel. */
@Composable
internal fun SettingsRoute(
    state: MangaUiState,
    viewModel: MangaViewModel,
    padding: PaddingValues,
    snackbarHostState: SnackbarHostState,
    notifications: NotificationPermissions,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    SettingsScreen(
        settings = state.settings,
        sourceHealth = state.sourceHealth,
        isBiometricAvailable = state.isBiometricAvailable,
        isParentalAuthInProgress = state.isParentalAuthInProgress,
        notificationsPermissionGranted = notifications.granted,
        aniListViewerName = state.aniList.viewer?.name,
        isAniListConnecting = state.aniList.isConnecting,
        padding = padding,
        onSelectThemeMode = viewModel::setThemeMode,
        onSelectCardDensity = viewModel::setCardDensity,
        onSelectAnimationLevel = viewModel::setAnimationLevel,
        onToggleDynamicColor = viewModel::setUseDynamicColor,
        onRestartTutorial = viewModel.tutorial::restart,
        onConnectAniList = {
            scope.launch {
                if (!AniListAuth.isConfigured()) {
                    snackbarHostState.showSnackbar(
                        "Collegamento AniList non configurato in questa build",
                    )
                    return@launch
                }
                try {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, viewModel.beginAniListLogin().toUri()),
                    )
                } catch (_: ActivityNotFoundException) {
                    snackbarHostState.showSnackbar("Nessun browser disponibile")
                }
            }
        },
        onDisconnectAniList = viewModel::disconnectAniList,
        onToggleAniListSync = viewModel::setAniListSyncEnabled,
        onToggleAniListFavoritesSync = viewModel::setAniListFavoritesSyncEnabled,
        onToggleAutoDownload = viewModel::setAutoDownloadEnabled,
        onTriggerChange = viewModel::setAutoDownloadTriggerChapters,
        onBatchChange = viewModel::setAutoDownloadBatchSize,
        onToggleSmartCleanup = viewModel::setSmartCleanupEnabled,
        onSmartCleanupKeepChange = viewModel::setSmartCleanupKeepPreviousChapters,
        onSelectReadingMode = viewModel::setReadingMode,
        onSelectSpreadPageMode = viewModel::setSpreadPageMode,
        onSelectReaderPageSpacing = viewModel::setReaderPageSpacing,
        onToggleDoubleTapZoom = viewModel::setDoubleTapZoomEnabled,
        onToggleKeepScreenOn = viewModel::setKeepScreenOnEnabled,
        onSetSourceEnabled = viewModel::setSourceEnabled,
        onToggleShowHomeTab = viewModel::setShowHomeTab,
        onToggleParentalControl = viewModel.parental::setEnabled,
        onRequestChangeParentalPin = viewModel.parental::requestChangePin,
        onToggleParentalBiometric = viewModel.parental::setBiometricEnabled,
        onToggleHideAdultContent = viewModel::setHideAdultContent,
        onToggleLabs = viewModel::setLabsEnabled,
        onToggleDownloadDevUpdates = viewModel::setDownloadDevUpdates,
        onTogglePrivacyBrightness = viewModel::setPrivacyBrightnessEnabled,
        onToggleAllowLandscapeRotation = viewModel::setAllowLandscapeRotation,
        onToggleFavoriteNotifications = { enabled ->
            if (enabled && !notifications.granted) {
                // L'attivazione vera avviene nel callback del launcher,
                // solo a permesso concesso: niente switch ON "a vuoto".
                notifications.requestForFavorites()
            } else {
                viewModel.setFavoriteNotificationsEnabled(enabled)
            }
        },
        onOpenStorageManager = viewModel::openStorageManager,
        onOpenBackup = viewModel::openBackup,
        onOpenReportProblem = viewModel::openFeedback,
        appVersion = BuildConfig.VERSION_NAME,
        onOpenChangelog = viewModel::openChangelog,
    )
}
