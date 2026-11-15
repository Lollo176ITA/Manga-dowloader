package com.lorenzo.mangadownloader.ui.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.lorenzo.mangadownloader.showAutoDismissSnackbar
import com.lorenzo.mangadownloader.ui.components.NotificationPermissionRationaleDialog
import kotlinx.coroutines.launch

/**
 * I tre momenti in cui l'app chiede il permesso notifiche (mai "alla cieca" al primo avvio: si
 * impilava sul dialogo di benvenuto e portava a negazioni riflesse).
 */
internal class NotificationPermissions(
    /** Stato attuale del permesso, ricontrollato a ogni ritorno in primo piano. */
    val granted: Boolean,
    /** Fine del tutorial: chiede con una spiegazione, e solo se manca. */
    val askWithRationale: () -> Unit,
    /** Attivazione di "Notifiche preferiti": il toggle si accende solo a permesso concesso. */
    val requestForFavorites: () -> Unit,
    /** Avvio di un download: chiede se manca e, se negato, spiega la conseguenza. */
    val requestForDownloadIfMissing: () -> Unit,
)

@SuppressLint("InlinedApi") // POST_NOTIFICATIONS è inlined e ogni uso è protetto dal check API 33.
@Composable
internal fun rememberNotificationPermissions(
    appContext: Context,
    snackbarHostState: SnackbarHostState,
    onFavoriteNotificationsGranted: () -> Unit,
): NotificationPermissions {
    val scope = rememberCoroutineScope()

    // Il permesso è revocabile dalle impostazioni di sistema: va ricontrollato a ogni ritorno
    // in foreground, altrimenti l'avviso sotto "Notifiche preferiti" mentirebbe.
    var permissionTick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { permissionTick++ }
    val granted = remember(permissionTick) { !appContext.lacksNotificationPermission() }

    val rationaleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { }

    // Se negato il toggle resta spento e la snackbar porta alle impostazioni di sistema (al
    // secondo rifiuto Android non mostra più il prompt).
    val favoritesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { isGranted ->
        permissionTick++
        if (isGranted) {
            onFavoriteNotificationsGranted()
        } else {
            scope.launch {
                val result = snackbarHostState.showAutoDismissSnackbar(
                    message = "Le notifiche sono bloccate per l'app",
                    actionLabel = "Impostazioni",
                )
                if (result == SnackbarResult.ActionPerformed) {
                    runCatching {
                        appContext.startActivity(
                            Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(
                                    android.provider.Settings.EXTRA_APP_PACKAGE,
                                    appContext.packageName,
                                )
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
            }
        }
    }

    // Senza POST_NOTIFICATIONS (Android 13+) il worker non può promuoversi a foreground
    // service: i download lunghi vengono fermati dal sistema (~10 minuti) e ripresi solo a
    // singhiozzo sotto Doze. Il worker ri-controlla il permesso a ogni aggiornamento di
    // progresso, quindi concederlo a download già partito lo promuove comunque a foreground.
    val downloadLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { isGranted ->
        if (!isGranted) {
            scope.launch {
                snackbarHostState.showSnackbar(
                    "Senza notifiche i download lunghi possono essere interrotti dal sistema",
                )
            }
        }
    }

    var showRationale by rememberSaveable { mutableStateOf(false) }
    if (showRationale) {
        NotificationPermissionRationaleDialog(
            onAccept = {
                showRationale = false
                rationaleLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            },
            onDismiss = { showRationale = false },
        )
    }

    return NotificationPermissions(
        granted = granted,
        askWithRationale = {
            if (appContext.lacksNotificationPermission()) showRationale = true
        },
        requestForFavorites = {
            favoritesLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        },
        requestForDownloadIfMissing = {
            if (appContext.lacksNotificationPermission()) {
                downloadLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        },
    )
}

@SuppressLint("InlinedApi")
private fun Context.lacksNotificationPermission(): Boolean {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
}
