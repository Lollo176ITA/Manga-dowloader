package com.lorenzo.mangadownloader

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Modello del changelog mostrato in-app, ricavato dal `CHANGELOG.md` (bundlato negli assets,
 * copiato dalla root in fase di build). Parsing **puro** (testabile, niente Android): consuma
 * il markdown nel formato prodotto dall'agente `changelog-writer` — `## YYYY-MM-DD`, poi
 * `### Categoria`, poi righe `- voce`.
 */
data class ChangelogSection(val category: String, val entries: List<String>)

data class ChangelogDay(val isoDate: String, val sections: List<ChangelogSection>)

/** Categoria tecnica esclusa di default dalla vista utente (refactor, test, dettagli interni). */
const val CHANGELOG_INTERNAL_CATEGORY = "Interno"

private val DAY_HEADING = Regex("""^##\s+(\d{4}-\d{2}-\d{2})\s*$""")
private val CATEGORY_HEADING = Regex("""^###\s+(.+?)\s*$""")

/**
 * Trasforma il markdown del changelog in giorni → sezioni → voci, dal più recente al più
 * vecchio (l'ordine del file). Con [includeInternal] = false la categoria "Interno" viene
 * saltata (non interessa chi usa l'app). Giorni e sezioni senza voci visibili spariscono.
 */
fun parseChangelog(markdown: String, includeInternal: Boolean = false): List<ChangelogDay> {
    val days = mutableListOf<ChangelogDay>()
    var currentDate: String? = null
    var sections = mutableListOf<ChangelogSection>()
    var currentCategory: String? = null
    var entries = mutableListOf<String>()

    fun flushSection() {
        val category = currentCategory
        if (category != null && entries.isNotEmpty()) {
            sections.add(ChangelogSection(category, entries.toList()))
        }
        currentCategory = null
        entries = mutableListOf()
    }

    fun flushDay() {
        flushSection()
        val date = currentDate
        if (date != null && sections.isNotEmpty()) {
            days.add(ChangelogDay(date, sections.toList()))
        }
        currentDate = null
        sections = mutableListOf()
    }

    markdown.lineSequence().forEach { raw ->
        val line = raw.trimEnd()
        val day = DAY_HEADING.find(line)
        val category = CATEGORY_HEADING.find(line)
        when {
            day != null -> {
                flushDay()
                currentDate = day.groupValues[1]
            }
            category != null -> {
                flushSection()
                val name = category.groupValues[1].trim()
                currentCategory = if (!includeInternal && name.equals(CHANGELOG_INTERNAL_CATEGORY, ignoreCase = true)) {
                    null
                } else {
                    name
                }
            }
            // Voci solo dentro un giorno e una categoria valida (non saltata).
            line.startsWith("- ") && currentDate != null && currentCategory != null -> {
                entries.add(line.removePrefix("- ").trim())
            }
        }
    }
    flushDay()
    return days
}

private val CHANGELOG_DATE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ITALIAN)

/** Data ISO ("2026-06-10") → estesa italiana ("10 giugno 2026"); fallback alla stringa originale. */
fun formatChangelogDate(isoDate: String): String =
    runCatching { LocalDate.parse(isoDate).format(CHANGELOG_DATE_FORMATTER) }.getOrDefault(isoDate)
