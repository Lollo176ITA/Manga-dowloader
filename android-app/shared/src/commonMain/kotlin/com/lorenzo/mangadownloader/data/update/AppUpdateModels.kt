package com.lorenzo.mangadownloader.data.update

enum class AppUpdateChannel {
    STABLE,
    PREVIEW,
}

data class AppUpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val repoOwner: String,
    val repoName: String,
    val apkAssetName: String,
    val releaseNotes: String? = null,
    val apkDownloadUrl: String? = null,
    val channel: AppUpdateChannel = AppUpdateChannel.STABLE,
    val releaseTag: String = buildReleaseTag(versionName, channel),
) {
    val apkUrl: String
        get() = apkDownloadUrl
            ?: "https://github.com/$repoOwner/$repoName/releases/download/$releaseTag/$apkAssetName"
}

fun buildReleaseTag(versionName: String, channel: AppUpdateChannel): String {
    return when (channel) {
        AppUpdateChannel.STABLE -> "android-v$versionName"
        AppUpdateChannel.PREVIEW -> "android-preview-v$versionName"
    }
}
