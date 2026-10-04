package com.lorenzo.mangadownloader.domain.reading

import com.lorenzo.mangadownloader.domain.ITALIAN_MONTH_NAMES
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.daysUntil
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime

/**
 * Data di pubblicazione dei capitoli: parsing dei formati che le fonti espongono davvero e
 * formattazione per la UI. Tutto puro e testabile — nessuna rete, nessun Android.
 *
 * I nomi dei mesi sono **nostri** e non presi dal [Locale]: il testo mostrato non deve
 * cambiare con la lingua del telefono (l'app è in italiano) né con la versione dei dati CLDR
 * della JVM, che tra un aggiornamento e l'altro ha già cambiato le abbreviazioni italiane.
 *
 * Non tutte le fonti pubblicano la data: Mangapill e TCB Scans non la espongono affatto, e per
 * loro [ChapterEntry.publishedAtMillis] resta `null` — la UI in quel caso non mostra nulla.
 */

private val ITALIAN_SHORT_MONTHS = listOf(
    "gen", "feb", "mar", "apr", "mag", "giu",
    "lug", "ago", "set", "ott", "nov", "dic",
)

/** `03 Maggio 2022` → giorno, mese, anno. Tollerante su maiuscole e spazi multipli. */
private val italianDateRegex = Regex("""^(\d{1,2})\s+([\p{L}]+)\s+(\d{4})$""")

/** `2024-07-13`, eventualmente seguito da altro (orario senza fuso, ecc.). */
private val isoDatePrefixRegex = Regex("""^(\d{4})-(\d{2})-(\d{2})""")

/**
 * Istante ISO-8601 o data ISO semplice → epoch millis.
 *
 * Copre i tre casi visti sulle fonti: `2026-04-19T17:07:50.694Z` (Asura Scans, `published_at`),
 * `2018-04-10T16:00:04.000000Z` (Hasta Team, `published_on` — sei cifre di frazione) e
 * `2024-07-13` (DemonicScans, data secca). Una data senza orario vale **mezzanotte in [zone]**:
 * è il giorno che la fonte dichiara, va letto nel fuso di chi guarda.
 *
 * Testo vuoto o non riconosciuto → `null`: una data illeggibile non deve mai far saltare il
 * parsing dell'intero capitolo.
 */
fun chapterDateFromIso(raw: String?, zone: TimeZone = TimeZone.currentSystemDefault()): Long? {
    val text = raw?.trim()?.takeIf(String::isNotBlank) ?: return null
    runCatching { Instant.parse(text) }.getOrNull()?.let { return it.toEpochMilliseconds() }
    val match = isoDatePrefixRegex.find(text) ?: return null
    return runCatching {
        LocalDate(
            match.groupValues[1].toInt(),
            match.groupValues[2].toInt(),
            match.groupValues[3].toInt(),
        ).atStartOfDayIn(zone).toEpochMilliseconds()
    }.getOrNull()
}

/**
 * Data italiana per esteso (`03 Maggio 2022`, come la scrive MangaWorld in `i.chap-date`) →
 * epoch millis della mezzanotte in [zone]. Mese sconosciuto o formato diverso → `null`.
 */
fun chapterDateFromItalianDate(raw: String?, zone: TimeZone = TimeZone.currentSystemDefault()): Long? {
    val text = raw?.trim()?.replace(Regex("""\s+"""), " ")?.takeIf(String::isNotBlank) ?: return null
    val match = italianDateRegex.find(text) ?: return null
    val month = ITALIAN_MONTH_NAMES.indexOf(match.groupValues[2].lowercase())
    if (month < 0) return null
    return runCatching {
        LocalDate(match.groupValues[3].toInt(), month + 1, match.groupValues[1].toInt())
            .atStartOfDayIn(zone)
            .toEpochMilliseconds()
    }.getOrNull()
}

/**
 * Etichetta mostrata accanto al capitolo: relativa nell'ultima settimana (`Oggi`, `Ieri`,
 * `3 giorni fa`), assoluta e breve da lì in poi (`4 gen 2026`).
 *
 * Il conteggio è fatto sui **giorni di calendario** in [zone], non sulle ore: un capitolo
 * uscito ieri alle 23:30 dice "Ieri" anche se sono passate solo due ore. Una data nel futuro
 * (orologio della fonte sbilanciato, uscita programmata) diventa "Oggi": mai "-1 giorni fa".
 */
fun formatChapterDate(
    publishedAtMillis: Long,
    nowMillis: Long,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): String {
    val published = Instant.fromEpochMilliseconds(publishedAtMillis).toLocalDateTime(zone).date
    val today = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(zone).date
    val days = published.daysUntil(today).toLong()
    return when {
        days <= 0L -> "Oggi"
        days == 1L -> "Ieri"
        days < 7L -> "$days giorni fa"
        else -> "${published.day} ${ITALIAN_SHORT_MONTHS[published.month.number - 1]} ${published.year}"
    }
}
