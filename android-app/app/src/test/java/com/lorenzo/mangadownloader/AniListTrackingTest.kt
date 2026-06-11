package com.lorenzo.mangadownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AniListTrackingTest {

    @Test
    fun extractAccessToken_readsTokenFromRedirectFragment() {
        assertEquals(
            "abc123",
            AniListAuth.extractAccessToken("access_token=abc123&token_type=Bearer&expires_in=31536000"),
        )
        // Token in posizione diversa nel fragment.
        assertEquals(
            "xyz",
            AniListAuth.extractAccessToken("token_type=Bearer&access_token=xyz"),
        )
    }

    @Test
    fun extractAccessToken_nullOnMissingOrDeniedRedirect() {
        assertNull(AniListAuth.extractAccessToken(null))
        assertNull(AniListAuth.extractAccessToken("   "))
        assertNull(AniListAuth.extractAccessToken("error=access_denied"))
        assertNull(AniListAuth.extractAccessToken("access_token="))
    }

    @Test
    fun aniListStatusFromText_mapsKnownValuesAndRejectsUnknown() {
        assertEquals(AniListListStatus.CURRENT, aniListStatusFromText("CURRENT"))
        assertEquals(AniListListStatus.PLANNING, aniListStatusFromText(" planning "))
        assertNull(aniListStatusFromText(null))
        assertNull(aniListStatusFromText("WATCHING"))
    }

    @Test
    fun aniListScoreFormatFromText_defaultsToPoint10() {
        assertEquals(AniListScoreFormat.POINT_100, aniListScoreFormatFromText("POINT_100"))
        assertEquals(AniListScoreFormat.POINT_10, aniListScoreFormatFromText(null))
        assertEquals(AniListScoreFormat.POINT_10, aniListScoreFormatFromText("qualcosa"))
    }

    @Test
    fun scoreFormat_displayValueRespectsDecimals() {
        assertEquals("8.5", AniListScoreFormat.POINT_10_DECIMAL.displayValue(8.5))
        assertEquals("85", AniListScoreFormat.POINT_100.displayValue(85.0))
        assertEquals("4", AniListScoreFormat.POINT_5.displayValue(4.0))
    }
}
