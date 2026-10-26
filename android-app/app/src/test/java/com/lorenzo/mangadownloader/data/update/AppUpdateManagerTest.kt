package com.lorenzo.mangadownloader.data.update

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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
    fun parseUpdateFeedInfo_readsStableVersionUrlAndNotes() {
        val info = parseFeed(
            """{"versionName": "1.16.7", "apkUrl": "https://example.com/app.apk", "notes": ["Prima novità", " ", "Seconda"]}""",
            AppUpdateChannel.STABLE,
        )

        assertEquals("1.16.7", info.versionName)
        assertEquals(101_600_799, info.versionCode)
        assertEquals("https://example.com/app.apk", info.apkUrl)
        assertEquals("Prima novità\nSeconda", info.releaseNotes)
        assertEquals("android-v1.16.7", info.releaseTag)
    }

    @Test
    fun parseUpdateFeedInfo_noNotesMeansNullReleaseNotes() {
        val info = parseFeed(
            """{"versionName": "1.16.7", "apkUrl": "https://example.com/app.apk", "notes": []}""",
            AppUpdateChannel.STABLE,
        )

        assertNull(info.releaseNotes)
    }

    @Test
    fun parseUpdateFeedInfo_rejectsWrongChannelVersionOrInsecureUrl() {
        assertThrows(IOException::class.java) {
            parseFeed("""{"versionName": "1.16.7-preview.2", "apkUrl": "https://e.com/a.apk"}""", AppUpdateChannel.STABLE)
        }
        assertThrows(IOException::class.java) {
            parseFeed("""{"versionName": "1.16.7", "apkUrl": "https://e.com/a.apk"}""", AppUpdateChannel.PREVIEW)
        }
        assertThrows(IOException::class.java) {
            parseFeed("""{"versionName": "1.16.7", "apkUrl": "http://e.com/a.apk"}""", AppUpdateChannel.STABLE)
        }
    }

    @Test
    fun stableVersionCodeSortsAfterPreviewsForSameVersion() {
        val stable = parseFeed(
            """{"versionName": "1.8.11", "apkUrl": "https://example.com/stable.apk"}""",
            AppUpdateChannel.STABLE,
        )
        val preview = parseFeed(
            """{"versionName": "1.8.11-preview.98", "apkUrl": "https://example.com/preview.apk"}""",
            AppUpdateChannel.PREVIEW,
        )

        assertEquals("android-preview-v1.8.11-preview.98", preview.releaseTag)
        assertTrue(stable.versionCode > preview.versionCode)
    }

    @Test
    fun updateFeedUrl_pointsToTheFixedReleaseAsset() {
        assertEquals(
            "https://github.com/Lollo176ITA/Manga-dowloader/releases/download/update-feed/update-preview.json",
            updateFeedUrl("Lollo176ITA", "Manga-dowloader", AppUpdateChannel.PREVIEW),
        )
    }

    private fun parseFeed(raw: String, channel: AppUpdateChannel) = parseUpdateFeedInfo(
        raw = raw,
        repoOwner = "Lollo176ITA",
        repoName = "Manga-dowloader",
        apkAssetName = "app-release.apk",
        channel = channel,
    )

    @Test
    fun signaturesTrusted_trueWhenCertificatesOverlap() {
        assertTrue(signaturesTrusted(installed = setOf("aa", "bb"), downloaded = setOf("bb")))
    }

    @Test
    fun signaturesTrusted_falseWhenCertificatesDiffer() {
        assertFalse(signaturesTrusted(installed = setOf("aa"), downloaded = setOf("cc")))
    }

    @Test
    fun signaturesTrusted_failsClosedOnEmptySignatures() {
        // Firme illeggibili da una delle due parti ⇒ installazione rifiutata.
        assertFalse(signaturesTrusted(installed = emptySet(), downloaded = setOf("aa")))
        assertFalse(signaturesTrusted(installed = setOf("aa"), downloaded = emptySet()))
        assertFalse(signaturesTrusted(installed = emptySet(), downloaded = emptySet()))
    }
}
