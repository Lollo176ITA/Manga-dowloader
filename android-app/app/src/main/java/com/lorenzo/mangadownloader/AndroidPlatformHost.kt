package com.lorenzo.mangadownloader

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import com.lorenzo.mangadownloader.app.AppSystemEffects
import com.lorenzo.mangadownloader.app.BackupDocument
import com.lorenzo.mangadownloader.app.UriBackupDocument
import com.lorenzo.mangadownloader.ui.BackupPickers
import com.lorenzo.mangadownloader.ui.PlatformHost
import com.lorenzo.mangadownloader.ui.SystemEffectsRequest

/** [PlatformHost] attorno alla [FragmentActivity] dell'app (permessi, SAF, intent, finestra). */
@SuppressLint("InlinedApi") // POST_NOTIFICATIONS è inlined e si chiede solo se il check API 33 lo richiede.
class AndroidPlatformHost(private val activity: FragmentActivity) : PlatformHost {

    override fun notificationsAllowed(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    @Composable
    override fun rememberNotificationsPermissionRequest(onResult: (granted: Boolean) -> Unit): () -> Unit {
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), onResult)
        return remember(launcher) { { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) } }
    }

    override fun openNotificationSettings() {
        runCatching {
            activity.startActivity(
                Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, activity.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    override fun openUrl(url: String): Boolean = try {
        activity.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    @Composable
    override fun rememberBackupPickers(
        onExport: (BackupDocument) -> Unit,
        onImport: (BackupDocument) -> Unit,
    ): BackupPickers {
        val createDocument = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json"),
        ) { uri -> uri?.let { onExport(UriBackupDocument(activity, it)) } }
        val openDocument = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri -> uri?.let { onImport(UriBackupDocument(activity, it)) } }
        return remember(createDocument, openDocument) {
            BackupPickers(
                pickExportTarget = { name -> createDocument.launch(name) },
                pickImportSource = { openDocument.launch(arrayOf("application/json", "*/*")) },
            )
        }
    }

    override fun consumeOpenLibraryRequest(): Boolean {
        val intent = activity.intent ?: return false
        if (intent.getStringExtra(DownloadWorker.EXTRA_OPEN_TAB) != DownloadWorker.OPEN_TAB_LIBRARY) return false
        intent.removeExtra(DownloadWorker.EXTRA_OPEN_TAB)
        return true
    }

    @Composable
    override fun SystemEffects(request: SystemEffectsRequest) {
        AppSystemEffects(
            activity = activity,
            allowLandscapeRotation = request.allowLandscapeRotation,
            biometricRequest = request.biometricRequest,
            readerOpen = request.readerOpen,
            readerFullscreen = request.readerFullscreen,
            keepReaderScreenOn = request.keepReaderScreenOn,
            onBiometricSucceeded = request.onBiometricSucceeded,
            onUsePinInstead = request.onUsePinInstead,
            onBiometricCancelled = request.onBiometricCancelled,
        )
    }
}
