package com.lorenzo.mangadownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AppUpdateManagerTest {

    @Test
    fun parseUpdateConfigInfo_readsExplicitVersionCodeForLegacyClients() {
        val info = parseUpdateConfigInfo(
            """
                versionCode=1008001
                versionName=1.8.1
                repoOwner=Lollo176ITA
                repoName=Manga-dowloader
                apkAssetName=app-release.apk
                releaseNotes=Compatibility fix
            """.trimIndent(),
        )

        assertEquals(1_008_001, info.versionCode)
        assertEquals("1.8.1", info.versionName)
        assertEquals("Compatibility fix", info.releaseNotes)
    }

    @Test
    fun parseUpdateConfigInfo_fallsBackToDerivedVersionCode() {
        val info = parseUpdateConfigInfo(
            """
                versionName=1.8.1
                repoOwner=Lollo176ITA
                repoName=Manga-dowloader
                apkAssetName=app-release.apk
            """.trimIndent(),
        )

        assertEquals(1_008_001, info.versionCode)
        assertEquals("1.8.1", info.versionName)
    }

    @Test
    fun parseLatestReleaseInfo_readsVersionNotesAndAssetUrl() {
        val info = parseLatestReleaseInfo(
            raw = """
                {
                  "tag_name": "android-v1.8.0",
                  "name": "Android 1.8.0",
                  "body": "Bugfix update",
                  "assets": [
                    {
                      "name": "app-release.apk",
                      "browser_download_url": "https://github.com/Lollo176ITA/Manga-dowloader/releases/download/android-v1.8.0/app-release.apk"
                    }
                  ]
                }
            """.trimIndent(),
            repoOwner = "Lollo176ITA",
            repoName = "Manga-dowloader",
            expectedAssetName = "app-release.apk",
        )

        assertNotNull(info)
        assertEquals("1.8.0", info?.versionName)
        assertEquals(1_008_000, info?.versionCode)
        assertEquals("Bugfix update", info?.releaseNotes)
        assertEquals("app-release.apk", info?.apkAssetName)
        assertEquals(
            "https://github.com/Lollo176ITA/Manga-dowloader/releases/download/android-v1.8.0/app-release.apk",
            info?.apkUrl,
        )
    }

    @Test
    fun parseLatestReleaseInfo_fallsBackToFirstApkAsset() {
        val info = parseLatestReleaseInfo(
            raw = """
                {
                  "tag_name": "android-v2.0.1",
                  "assets": [
                    {
                      "name": "notes.txt",
                      "browser_download_url": "https://example.com/notes.txt"
                    },
                    {
                      "name": "manga-downloader.apk",
                      "browser_download_url": "https://example.com/manga-downloader.apk"
                    }
                  ]
                }
            """.trimIndent(),
            repoOwner = "Lollo176ITA",
            repoName = "Manga-dowloader",
            expectedAssetName = "app-release.apk",
        )

        assertNotNull(info)
        assertEquals("2.0.1", info?.versionName)
        assertEquals("manga-downloader.apk", info?.apkAssetName)
        assertEquals("https://example.com/manga-downloader.apk", info?.apkUrl)
    }

    @Test
    fun parseLatestReleaseInfo_returnsNullWhenTagIsNotSupported() {
        val info = parseLatestReleaseInfo(
            raw = """
                {
                  "tag_name": "release-2026-04-23",
                  "assets": [
                    {
                      "name": "app-release.apk",
                      "browser_download_url": "https://example.com/app-release.apk"
                    }
                  ]
                }
            """.trimIndent(),
            repoOwner = "Lollo176ITA",
            repoName = "Manga-dowloader",
            expectedAssetName = "app-release.apk",
        )

        assertNull(info)
    }
}
