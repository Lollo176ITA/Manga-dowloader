package com.lorenzo.mangadownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

        assertEquals(100_800_199, info.versionCode)
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
        assertEquals(100_800_099, info?.versionCode)
        assertEquals(AppUpdateChannel.STABLE, info?.channel)
        assertEquals("android-v1.8.0", info?.releaseTag)
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
        assertEquals(200_000_199, info?.versionCode)
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

    @Test
    fun parseLatestPreviewReleaseInfo_readsPreviewVersionAndAssetUrl() {
        val info = parseLatestPreviewReleaseInfo(
            raw = """
                [
                  {
                    "tag_name": "android-preview-v1.8.11-preview.1",
                    "draft": false,
                    "prerelease": true,
                    "assets": [
                      {
                        "name": "app-release.apk",
                        "browser_download_url": "https://example.com/app-release.apk"
                      }
                    ]
                  }
                ]
            """.trimIndent(),
            repoOwner = "Lollo176ITA",
            repoName = "Manga-dowloader",
            expectedAssetName = "app-release.apk",
        )

        assertNotNull(info)
        assertEquals("1.8.11-preview.1", info?.versionName)
        assertEquals(100_801_101, info?.versionCode)
        assertEquals(AppUpdateChannel.PREVIEW, info?.channel)
        assertEquals("android-preview-v1.8.11-preview.1", info?.releaseTag)
        assertEquals("https://example.com/app-release.apk", info?.apkUrl)
    }

    @Test
    fun parseLatestPreviewReleaseInfo_ignoresDraftStableAndUnsupportedTags() {
        val info = parseLatestPreviewReleaseInfo(
            raw = """
                [
                  {
                    "tag_name": "android-preview-v1.8.11-preview.2",
                    "draft": true,
                    "prerelease": true,
                    "assets": [
                      {
                        "name": "app-release.apk",
                        "browser_download_url": "https://example.com/draft.apk"
                      }
                    ]
                  },
                  {
                    "tag_name": "android-v1.8.11",
                    "draft": false,
                    "prerelease": false,
                    "assets": [
                      {
                        "name": "app-release.apk",
                        "browser_download_url": "https://example.com/stable.apk"
                      }
                    ]
                  },
                  {
                    "tag_name": "preview-2026-04-24",
                    "draft": false,
                    "prerelease": true,
                    "assets": [
                      {
                        "name": "app-release.apk",
                        "browser_download_url": "https://example.com/bad.apk"
                      }
                    ]
                  }
                ]
            """.trimIndent(),
            repoOwner = "Lollo176ITA",
            repoName = "Manga-dowloader",
            expectedAssetName = "app-release.apk",
        )

        assertNull(info)
    }

    @Test
    fun parseLatestPreviewReleaseInfo_picksHighestPreviewAndFirstApkFallback() {
        val info = parseLatestPreviewReleaseInfo(
            raw = """
                [
                  {
                    "tag_name": "android-preview-v1.8.11-preview.1",
                    "draft": false,
                    "prerelease": true,
                    "assets": [
                      {
                        "name": "app-release.apk",
                        "browser_download_url": "https://example.com/old.apk"
                      }
                    ]
                  },
                  {
                    "tag_name": "android-preview-v1.8.11-preview.3",
                    "draft": false,
                    "prerelease": true,
                    "assets": [
                      {
                        "name": "notes.txt",
                        "browser_download_url": "https://example.com/notes.txt"
                      },
                      {
                        "name": "manga-downloader-preview.apk",
                        "browser_download_url": "https://example.com/new.apk"
                      }
                    ]
                  }
                ]
            """.trimIndent(),
            repoOwner = "Lollo176ITA",
            repoName = "Manga-dowloader",
            expectedAssetName = "app-release.apk",
        )

        assertNotNull(info)
        assertEquals("1.8.11-preview.3", info?.versionName)
        assertEquals(100_801_103, info?.versionCode)
        assertEquals("manga-downloader-preview.apk", info?.apkAssetName)
        assertEquals("https://example.com/new.apk", info?.apkUrl)
    }

    @Test
    fun stableVersionCodeSortsAfterPreviewsForSameVersion() {
        val stable = parseLatestReleaseInfo(
            raw = """
                {
                  "tag_name": "android-v1.8.11",
                  "assets": [
                    {
                      "name": "app-release.apk",
                      "browser_download_url": "https://example.com/stable.apk"
                    }
                  ]
                }
            """.trimIndent(),
            repoOwner = "Lollo176ITA",
            repoName = "Manga-dowloader",
            expectedAssetName = "app-release.apk",
        )
        val preview = parseLatestPreviewReleaseInfo(
            raw = """
                [
                  {
                    "tag_name": "android-preview-v1.8.11-preview.98",
                    "draft": false,
                    "prerelease": true,
                    "assets": [
                      {
                        "name": "app-release.apk",
                        "browser_download_url": "https://example.com/preview.apk"
                      }
                    ]
                  }
                ]
            """.trimIndent(),
            repoOwner = "Lollo176ITA",
            repoName = "Manga-dowloader",
            expectedAssetName = "app-release.apk",
        )

        assertNotNull(stable)
        assertNotNull(preview)
        assertTrue(stable!!.versionCode > preview!!.versionCode)
    }
}
