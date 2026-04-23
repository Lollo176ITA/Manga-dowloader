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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

data class AppUpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val repoOwner: String,
    val repoName: String,
    val apkAssetName: String,
    val releaseNotes: String? = null,
    val apkDownloadUrl: String? = null,
) {
    val releaseTag: String
        get() = buildReleaseTag(versionName)

    val apkUrl: String
        get() = apkDownloadUrl
            ?: "https://github.com/$repoOwner/$repoName/releases/download/$releaseTag/$apkAssetName"
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
        val latestRelease = runCatching {
            fetchLatestReleaseInfo()
        }.getOrNull()

        val info = latestRelease ?: fetchUpdateConfigInfo()
        if (info.versionCode <= BuildConfig.VERSION_CODE) return@withContext null

        val commitMessage = runCatching { fetchCommitMessage(info) }.getOrNull()
        if (commitMessage != null) info.copy(releaseNotes = commitMessage) else info
    }

    private fun fetchCommitMessage(info: AppUpdateInfo): String? {
        val repoOwner = info.repoOwner.trim().ifBlank { return null }
        val repoName = info.repoName.trim().ifBlank { return null }
        val tag = info.releaseTag

        val request = Request.Builder()
            .url("https://api.github.com/repos/$repoOwner/$repoName/commits/$tag")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("Cache-Control", "no-cache")
            .build()

        val raw = httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body?.string() ?: return null
        }

        return parseCommitMessage(raw)
    }

    private fun fetchLatestReleaseInfo(): AppUpdateInfo {
        val repoOwner = BuildConfig.UPDATE_REPO_OWNER.trim()
        val repoName = BuildConfig.UPDATE_REPO_NAME.trim()
        val assetName = BuildConfig.UPDATE_APK_ASSET_NAME.trim()
        if (repoOwner.isBlank() || repoName.isBlank() || assetName.isBlank()) {
            throw IOException("Configurazione repository update incompleta")
        }

        val request = Request.Builder()
            .url("https://api.github.com/repos/$repoOwner/$repoName/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("Cache-Control", "no-cache")
            .build()

        val raw = httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Impossibile leggere la latest release: HTTP ${response.code}")
            }
            response.body?.string() ?: throw IOException("Latest release GitHub vuota")
        }

        return parseLatestReleaseInfo(
            raw = raw,
            repoOwner = repoOwner,
            repoName = repoName,
            expectedAssetName = assetName,
        ) ?: throw IOException("Latest release GitHub non valida")
    }

    private fun fetchUpdateConfigInfo(): AppUpdateInfo {
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

        return parseUpdateConfigInfo(raw)
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

internal fun parseUpdateConfigInfo(raw: String): AppUpdateInfo {
    val properties = Properties().apply { load(raw.byteInputStream()) }
    val versionName = properties.getProperty("versionName").orEmpty()
    return AppUpdateInfo(
        versionCode = properties.getProperty("versionCode")?.toIntOrNull()
            ?: versionCodeFromVersionName(versionName),
        versionName = versionName,
        repoOwner = properties.getProperty("repoOwner").orEmpty(),
        repoName = properties.getProperty("repoName").orEmpty(),
        apkAssetName = properties.getProperty("apkAssetName").orEmpty(),
    )
        .also { info ->
            if (
                info.versionName.isBlank() ||
                info.repoOwner.isBlank() ||
                info.repoName.isBlank() ||
                info.apkAssetName.isBlank()
            ) {
                throw IOException("Configurazione update incompleta")
            }
        }
}

internal fun parseLatestReleaseInfo(
    raw: String,
    repoOwner: String,
    repoName: String,
    expectedAssetName: String,
): AppUpdateInfo? {
    val root = Json.parseToJsonElement(raw).jsonObject
    val versionName = extractVersionNameFromRelease(
        tagName = root["tag_name"]?.jsonPrimitive?.contentOrNull,
        releaseName = root["name"]?.jsonPrimitive?.contentOrNull,
    ) ?: return null

    val assets = root["assets"]?.jsonArray.orEmpty()
    val selectedAsset = assets.firstOrNull { asset ->
        asset.jsonObject["name"]?.jsonPrimitive?.contentOrNull == expectedAssetName
    } ?: assets.firstOrNull { asset ->
        asset.jsonObject["name"]?.jsonPrimitive?.contentOrNull?.endsWith(".apk", ignoreCase = true) == true
    } ?: return null

    val apkAssetName = selectedAsset.jsonObject["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    val apkDownloadUrl = selectedAsset.jsonObject["browser_download_url"]
        ?.jsonPrimitive
        ?.contentOrNull
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: return null

    return AppUpdateInfo(
        versionCode = versionCodeFromVersionName(versionName),
        versionName = versionName,
        repoOwner = repoOwner,
        repoName = repoName,
        apkAssetName = apkAssetName,
        apkDownloadUrl = apkDownloadUrl,
    )
}

internal fun parseCommitMessage(raw: String): String? {
    val message = Json.parseToJsonElement(raw).jsonObject["commit"]
        ?.jsonObject
        ?.get("message")
        ?.jsonPrimitive
        ?.contentOrNull
        ?.trim()
    return message?.takeIf(String::isNotBlank)
}

private fun extractVersionNameFromRelease(
    tagName: String?,
    releaseName: String?,
): String? {
    val candidates = listOfNotNull(
        tagName?.trim()?.removePrefix("android-v"),
        releaseName?.trim()?.substringAfterLast(' '),
    )
    return candidates.firstOrNull(::isSupportedVersionName)
}

private fun isSupportedVersionName(value: String): Boolean {
    return value.matches(Regex("""\d+(?:\.\d+){0,2}"""))
}

private fun versionCodeFromVersionName(versionName: String): Int {
    val raw = versionName.trim()
    if (raw.isBlank()) {
        throw IOException("versionName remoto mancante")
    }

    val parts = raw.split('.')
    if (parts.size !in 1..3) {
        throw IOException("versionName remoto non valido: $raw")
    }

    val major = parts.getOrNull(0)?.toIntOrNull()
        ?: throw IOException("Major remoto non valido: $raw")
    val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
    val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0

    if (minor !in 0..999 || patch !in 0..999) {
        throw IOException("versionName remoto fuori range: $raw")
    }

    return (major * 1_000_000) + (minor * 1_000) + patch
}

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
