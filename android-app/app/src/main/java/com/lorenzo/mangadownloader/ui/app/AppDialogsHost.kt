package com.lorenzo.mangadownloader.ui.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import com.lorenzo.mangadownloader.app.MangaUiState
import com.lorenzo.mangadownloader.app.MangaViewModel
import com.lorenzo.mangadownloader.data.anilist.AniListScoreFormat
import com.lorenzo.mangadownloader.data.report.CrashReporter
import com.lorenzo.mangadownloader.data.report.FeedbackReporter
import com.lorenzo.mangadownloader.ui.anilist.AniListMatchDialog
import com.lorenzo.mangadownloader.ui.anilist.AniListTrackerDialog
import com.lorenzo.mangadownloader.ui.components.AvailableUpdateDialog
import com.lorenzo.mangadownloader.ui.components.ParentalPinEntryDialog
import com.lorenzo.mangadownloader.ui.components.ParentalPinSetupDialog
import kotlinx.coroutines.launch

/** I dialog comandati dallo stato del ViewModel: PIN, aggiornamento dell'app, AniList. */
@Composable
internal fun AppDialogsHost(
    state: MangaUiState,
    viewModel: MangaViewModel,
    snackbarHostState: SnackbarHostState,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    state.parentalPinSetupState?.let { setupState ->
        ParentalPinSetupDialog(
            state = setupState,
            onPinChange = { viewModel.parental.onPinSetupChange(pin = it) },
            onConfirmPinChange = { viewModel.parental.onPinSetupChange(confirmPin = it) },
            onDismiss = viewModel.parental::dismissPinSetup,
            onConfirm = viewModel.parental::confirmPinSetup,
        )
    }

    state.parentalPinEntryState?.let { pinEntryState ->
        ParentalPinEntryDialog(
            state = pinEntryState,
            onPinChange = viewModel.parental::onPinEntryChange,
            onDismiss = viewModel.parental::dismissPinEntry,
            onConfirm = viewModel.parental::confirmPinEntry,
        )
    }

    state.availableUpdate?.let { update ->
        AvailableUpdateDialog(
            update = update,
            isInstalling = state.isInstallingUpdate,
            onDismiss = viewModel::dismissAvailableUpdate,
            onConfirm = viewModel::installAvailableUpdate,
        )
    }

    state.aniList.match?.let { match ->
        AniListMatchDialog(
            match = match,
            onQueryChange = viewModel::onAniListMatchQueryChange,
            onSearch = viewModel::submitAniListMatchSearch,
            onSelect = viewModel::confirmAniListMatch,
            onDismiss = viewModel::dismissAniListMatch,
        )
    }

    state.aniList.trackerKey
        ?.let { key -> state.aniList.trackings[key] }
        ?.let { tracking ->
            AniListTrackerDialog(
                tracking = tracking,
                scoreFormat = state.aniList.viewer?.scoreFormat ?: AniListScoreFormat.POINT_10,
                isSaving = state.aniList.isSavingEntry,
                onSave = viewModel::saveAniListEntry,
                onUnlink = viewModel::unlinkAniListTracking,
                onOpenOnSite = {
                    try {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, tracking.siteUrl().toUri()),
                        )
                    } catch (_: ActivityNotFoundException) {
                        scope.launch { snackbarHostState.showSnackbar("Nessun browser disponibile") }
                    }
                },
                onDismiss = viewModel::dismissAniListTracker,
            )
        }
}

/**
 * Crash del run precedente: la segnalazione parte da sola in background, senza disturbare
 * l'utente con stack trace incomprensibili (scelta deliberata). Se l'invio fallisce il report
 * resta su disco e si ritenta al prossimo avvio.
 */
@Composable
internal fun PendingCrashReportEffect(appContext: Context) {
    var lastCrashReport by remember { mutableStateOf(CrashReporter.readLastCrash(appContext)) }
    LaunchedEffect(lastCrashReport) {
        val report = lastCrashReport ?: return@LaunchedEffect
        if (!FeedbackReporter.isConfigured()) {
            CrashReporter.clearLastCrash(appContext)
            lastCrashReport = null
            return@LaunchedEffect
        }
        val sent = FeedbackReporter.sendCrashReport(appContext, report)
        if (sent) {
            CrashReporter.clearLastCrash(appContext)
        }
        lastCrashReport = null
    }
}
