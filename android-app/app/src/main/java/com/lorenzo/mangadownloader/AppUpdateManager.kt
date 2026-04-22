package com.lorenzo.mangadownloader

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.util.Properties
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

data class AppUpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val repoOwner: String,
    val repoName: String,
    val apkAssetName: String,
) {
    val releaseTag: String
        get() = buildReleaseTag(versionName)

    val apkUrl: String
        get() = "https://github.com/$repoOwner/$repoName/releases/download/$releaseTag/$apkAssetName"
}

class AppUpdateRepository(
    private val context: Context,
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    suspend fun checkForUpdate(): AppUpdateInfo? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(BuildConfig.UPDATE_CONFIG_URL)
            .header("Cache-Control", "no-cache")
            .build()

        val raw = httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Impossibile controllare gli aggiornamenti: HTTP ${response.code}")
            }
            response.body?.string() ?: throw IOException("Configurazione update vuota")
        }

        val properties = Properties().apply {
            load(raw.byteInputStream())
        }
        val info = AppUpdateInfo(
            versionCode = properties.getProperty("versionCode")?.toIntOrNull()
                ?: throw IOException("versionCode remoto non valido"),
            versionName = properties.getProperty("versionName").orEmpty(),
            repoOwner = properties.getProperty("repoOwner").orEmpty(),
            repoName = properties.getProperty("repoName").orEmpty(),
            apkAssetName = properties.getProperty("apkAssetName").orEmpty(),
        )

        if (info.versionName.isBlank() ||
            info.repoOwner.isBlank() ||
            info.repoName.isBlank() ||
            info.apkAssetName.isBlank()
        ) {
            throw IOException("Configurazione update incompleta")
        }

        if (info.versionCode > BuildConfig.VERSION_CODE) info else null
    }

    suspend fun downloadUpdateApk(info: AppUpdateInfo): File = withContext(Dispatchers.IO) {
        val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val targetFile = File(updatesDir, "manga-downloader-${info.versionName}.apk")
        if (targetFile.isFile && targetFile.length() > 0L) {
            return@withContext targetFile
        }

        val tempFile = File(updatesDir, "${targetFile.name}.part")
        if (tempFile.exists()) {
            tempFile.delete()
        }

        val request = Request.Builder()
            .url(info.apkUrl)
            .header("Accept", "application/vnd.android.package-archive,application/octet-stream,*/*")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Impossibile scaricare l'update: HTTP ${response.code}")
            }
            val body = response.body ?: throw IOException("APK update vuoto")
            tempFile.outputStream().buffered().use { output ->
                body.byteStream().use { input -> input.copyTo(output) }
            }
        }

        if (!tempFile.renameTo(targetFile)) {
            tempFile.delete()
            throw IOException("Impossibile finalizzare l'APK scaricato")
        }
        targetFile
    }
}

private fun buildReleaseTag(versionName: String): String = "android-v$versionName"

object AppUpdateInstaller {
    fun canInstallPackages(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()
    }

    fun openInstallPermissionSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun installApk(context: Context, apkFile: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            apkFile,
        )
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        try {
            context.startActivity(intent)
        } catch (exc: ActivityNotFoundException) {
            throw IOException("Nessuna app disponibile per installare l'APK", exc)
        }
    }
}
