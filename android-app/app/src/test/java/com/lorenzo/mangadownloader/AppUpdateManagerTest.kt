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
            """.trimIndent(),
        )

        assertEquals(1_008_001, info.versionCode)
        assertEquals("1.8.1", info.versionName)
        assertNull(info.releaseNotes)
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
    fun parseLatestReleaseInfo_readsVersionAndAssetUrl() {
        val info = parseLatestReleaseInfo(
            raw = """
                {
                  "tag_name": "android-v1.8.0",
                  "name": "Android 1.8.0",
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
        assertNull(info?.releaseNotes)
        assertEquals("app-release.apk", info?.apkAssetName)
        assertEquals(
            "https://github.com/Lollo176ITA/Manga-dowloader/releases/download/android-v1.8.0/app-release.apk",
            info?.apkUrl,
        )
    }

    @Test
    fun parseCommitMessage_returnsTrimmedCommitMessage() {
        val message = parseCommitMessage(
            """
                {
                  "sha": "abc123",
                  "commit": {
                    "message": "feat: add swipe navigation\n\n- Tap edges to turn page\n- Respect RTL reading"
                  }
                }
            """.trimIndent(),
        )

        assertEquals(
            "feat: add swipe navigation\n\n- Tap edges to turn page\n- Respect RTL reading",
            message,
        )
    }

    @Test
    fun parseCommitMessage_returnsNullWhenMessageIsMissing() {
        val message = parseCommitMessage(
            """
                {
                  "sha": "abc123",
                  "commit": {}
                }
            """.trimIndent(),
        )

        assertNull(message)
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
