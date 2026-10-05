package com.lorenzo.mangadownloader.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.lorenzo.mangadownloader.app.BackupDocument
import com.lorenzo.mangadownloader.app.IosDownloadServices
import com.lorenzo.mangadownloader.platform.systemFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import okio.Path.Companion.toPath

fun interface IosBooleanResult { fun complete(value: Boolean) }
fun interface IosFileResult { fun complete(path: String?) }
fun interface IosBiometricResult { fun complete(outcome: Int, message: String?) }

/** Implementato dall'host Swift: non espone funzioni Composable o coroutine a Swift. */
interface IosPlatformServices {
    val documentsDirectory: String
    val cacheDirectory: String
    val downloads: IosDownloadServices
    fun notificationsAllowed(): Boolean
    fun requestNotifications(result: IosBooleanResult)
    fun openNotificationSettings()
    fun openUrl(url: String): Boolean
    fun exportBackupFile(path: String, result: IosBooleanResult)
    fun pickBackupFile(result: IosFileResult)
    fun configureReader(allowLandscape: Boolean, fullscreen: Boolean, keepScreenOn: Boolean)
    fun biometricAvailable(): Boolean
    /** outcome: 1 successo, 0 usa PIN, -1 annullato/errore. */
    fun authenticate(result: IosBiometricResult)
    fun notify(identifier: String, title: String, body: String)
}

internal class IosPlatformHost(private val services: IosPlatformServices) : PlatformHost {
    private val notificationPermission = mutableStateOf(services.notificationsAllowed())
    fun updateNotificationPermission(granted: Boolean) { notificationPermission.value = granted }
    override fun notificationsAllowed() = notificationPermission.value
    override fun openNotificationSettings() = services.openNotificationSettings()
    override fun openUrl(url: String) = services.openUrl(url)
    override fun consumeOpenLibraryRequest() = false

    @Composable
    override fun rememberNotificationsPermissionRequest(onResult: (Boolean) -> Unit): () -> Unit {
        val currentResult = rememberUpdatedState(onResult)
        return remember { { services.requestNotifications { currentResult.value(it) } } }
    }

    @Composable
    override fun rememberBackupPickers(onExport: (BackupDocument) -> Unit, onImport: (BackupDocument) -> Unit): BackupPickers {
        val export = rememberUpdatedState(onExport)
        val import = rememberUpdatedState(onImport)
        return remember {
            BackupPickers(
                pickExportTarget = { name ->
                    val safeName = name.substringAfterLast('/').substringAfterLast('\\')
                    val target = services.cacheDirectory.toPath() / "backup-export" / safeName
                    export.value(IosBackupDocument(target.toString()) {
                        awaitIosBackupExport { result -> services.exportBackupFile(target.toString(), result) }
                    })
                },
                pickImportSource = {
                    services.pickBackupFile { path -> path?.let { import.value(IosBackupDocument(it)) } }
                },
            )
        }
    }

    @Composable
    override fun SystemEffects(request: SystemEffectsRequest) {
        SideEffect {
            services.configureReader(
                request.allowLandscapeRotation,
                request.readerOpen && request.readerFullscreen,
                request.readerOpen && request.keepReaderScreenOn,
            )
        }
        DisposableEffect(Unit) { onDispose { services.configureReader(false, false, false) } }
        val currentRequest = rememberUpdatedState(request)
        LaunchedEffect(request.biometricRequest?.requestId) {
            val prompt = request.biometricRequest ?: return@LaunchedEffect
            services.authenticate { outcome, message ->
                val callbacks = currentRequest.value
                when (outcome) {
                    1 -> callbacks.onBiometricSucceeded(prompt.requestId)
                    0 -> callbacks.onUsePinInstead(prompt.requestId)
                    else -> callbacks.onBiometricCancelled(prompt.requestId, message)
                }
            }
        }
    }
}

private class IosBackupDocument(private val path: String, private val afterWrite: (suspend () -> Unit)? = null) : BackupDocument {
    override suspend fun writeText(text: String) {
        withContext(Dispatchers.IO) {
            val target = path.toPath()
            target.parent?.let { systemFileSystem.createDirectories(it) }
            systemFileSystem.write(target) { writeUtf8(text) }
        }
        withContext(Dispatchers.Main) { afterWrite?.invoke() }
    }

    override suspend fun readText(): String = withContext(Dispatchers.IO) {
        systemFileSystem.read(path.toPath()) { readUtf8() }
    }
}

/** Il file temporaneo non equivale a un export: attendiamo la conferma del selettore. */
internal suspend fun awaitIosBackupExport(request: (IosBooleanResult) -> Unit) {
    val saved = suspendCancellableCoroutine<Boolean> { continuation ->
        request(IosBooleanResult { value ->
            if (continuation.isActive) continuation.resume(value)
        })
    }
    if (!saved) throw CancellationException("Backup export cancelled")
}
