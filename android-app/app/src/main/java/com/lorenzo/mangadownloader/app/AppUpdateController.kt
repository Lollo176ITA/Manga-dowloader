package com.lorenzo.mangadownloader.app

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.lorenzo.mangadownloader.data.update.AppUpdateInstaller
import com.lorenzo.mangadownloader.data.update.AppUpdateRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Aggiornamenti dell'app estratti da [MangaViewModel]: controllo del feed (stabile o preview),
 * download dell'APK e passaggio all'installer di sistema. Condivide il [MutableStateFlow] del
 * ViewModel.
 */
class AppUpdateController(
    private val state: MutableStateFlow<MangaUiState>,
    private val scope: CoroutineScope,
    private val context: Context,
    private val prefs: SharedPreferences,
    private val appUpdateRepository: AppUpdateRepository,
) {
    private var updateJob: Job? = null

    private inline fun updateState(transform: MangaUiState.() -> MangaUiState) {
        state.update(transform)
    }

    fun checkForUpdate(force: Boolean = false) {
        if (updateJob?.isActive == true) {
            return
        }
        if (!force && state.value.availableUpdate != null) {
            return
        }

        val includePreview = state.value.settings.downloadDevUpdates
        // Stable channel: throttle cold-start checks to once per day to avoid
        // hammering GitHub on every app open. Preview channel and explicit
        // user-triggered checks always run, since previews ship more often and
        // users hitting "controlla aggiornamenti" expect immediate feedback.
        val shouldRecordStableCheck = !force && !includePreview
        if (shouldRecordStableCheck) {
            val lastCheck = prefs.getLong(KEY_LAST_UPDATE_CHECK_AT, 0L)
            if (lastCheck > 0L &&
                System.currentTimeMillis() - lastCheck < UPDATE_CHECK_COOLDOWN_MS
            ) {
                return
            }
        }

        updateState { copy(isCheckingUpdate = true) }
        updateJob = scope.launch {
            var stableCheckCompleted = false
            try {
                val update = appUpdateRepository.checkForUpdate(
                    includePreview = includePreview,
                )
                stableCheckCompleted = true
                updateState {
                    copy(
                        availableUpdate = update,
                        isCheckingUpdate = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (exc: Exception) {
                updateState {
                    if (force) {
                        copy(
                            isCheckingUpdate = false,
                            errorMessage = exc.message ?: "Errore controllo aggiornamenti",
                        )
                    } else {
                        copy(isCheckingUpdate = false)
                    }
                }
            } finally {
                if (shouldRecordStableCheck && stableCheckCompleted) {
                    prefs.edit {
                        putLong(KEY_LAST_UPDATE_CHECK_AT, System.currentTimeMillis())
                    }
                }
            }
        }
    }

    fun dismiss() {
        updateState { copy(availableUpdate = null) }
    }

    fun install() {
        val update = state.value.availableUpdate ?: return
        updateJob?.cancel()
        updateState { copy(isInstallingUpdate = true, errorMessage = null) }
        updateJob = scope.launch {
            try {
                if (!AppUpdateInstaller.canInstallPackages(context)) {
                    AppUpdateInstaller.openInstallPermissionSettings(context)
                    updateState {
                        copy(
                            isInstallingUpdate = false,
                            errorMessage = "Abilita l'installazione da questa app e riprova",
                        )
                    }
                    return@launch
                }

                val apkFile = appUpdateRepository.downloadUpdateApk(update)
                AppUpdateInstaller.installApk(context, apkFile)
                updateState {
                    copy(
                        isInstallingUpdate = false,
                        availableUpdate = null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (exc: Exception) {
                updateState {
                    copy(
                        isInstallingUpdate = false,
                        errorMessage = exc.message ?: "Errore installazione aggiornamento",
                    )
                }
            }
        }
    }

    private companion object {
        const val KEY_LAST_UPDATE_CHECK_AT = "last_update_check_at_ms"
        const val UPDATE_CHECK_COOLDOWN_MS = 24L * 60L * 60L * 1000L
    }
}
