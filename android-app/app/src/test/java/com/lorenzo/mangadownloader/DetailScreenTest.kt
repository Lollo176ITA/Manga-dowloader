package com.lorenzo.mangadownloader

import org.junit.Assert.assertEquals
import org.junit.Test

class DetailScreenTest {

    @Test
    fun detailSubtitle_showsLoadingStateWhileEnteringManga() {
        assertEquals(
            "Caricamento capitoli...",
            detailSubtitle(chapters = emptyList(), isLoading = true),
        )
    }
}
