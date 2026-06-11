package com.lorenzo.mangadownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parsing del changelog per la schermata "Novità" (formato prodotto dall'agente changelog-writer). */
class ChangelogContentTest {

    private val sample = """
        # Changelog

        <!-- markdownlint-disable MD024 -->

        > Nota introduttiva da ignorare.

        ## 2026-06-10

        ### Aggiunto

        - Azione "Elimina capitoli letti".
        - Modalità di lettura "Manga".

        ### Corretto

        - I download falliti mostrano l'errore.

        ### Interno

        - Refactor che gli utenti non devono vedere.

        ## 2026-05-26

        ### Sicurezza

        - Verifica firma APK.
    """.trimIndent()

    @Test
    fun parsesDaysAndSectionsInOrder() {
        val days = parseChangelog(sample)

        assertEquals(listOf("2026-06-10", "2026-05-26"), days.map { it.isoDate })

        val first = days.first()
        assertEquals(listOf("Aggiunto", "Corretto"), first.sections.map { it.category })
        assertEquals(
            listOf("Azione \"Elimina capitoli letti\".", "Modalità di lettura \"Manga\"."),
            first.sections.first().entries,
        )
    }

    @Test
    fun skipsInternalCategoryByDefault() {
        val days = parseChangelog(sample)
        val categories = days.flatMap { it.sections }.map { it.category }
        assertFalse("La categoria 'Interno' non deve comparire", categories.contains("Interno"))
    }

    @Test
    fun includesInternalWhenRequested() {
        val days = parseChangelog(sample, includeInternal = true)
        val categories = days.flatMap { it.sections }.map { it.category }
        assertTrue(categories.contains("Interno"))
    }

    @Test
    fun emptyOrHeaderOnly_returnsNoDays() {
        assertTrue(parseChangelog("").isEmpty())
        assertTrue(parseChangelog("# Changelog\n\n> nota\n").isEmpty())
    }

    @Test
    fun formatsIsoDateInItalian() {
        assertEquals("10 giugno 2026", formatChangelogDate("2026-06-10"))
        // Data non valida → torna la stringa originale, senza crash.
        assertEquals("non-una-data", formatChangelogDate("non-una-data"))
    }
}
