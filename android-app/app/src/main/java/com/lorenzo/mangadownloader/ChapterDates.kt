package com.lorenzo.mangadownloader

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale

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
private val ITALIAN_MONTHS = listOf(
    "gennaio", "febbraio", "marzo", "aprile", "maggio", "giugno",
    "luglio", "agosto", "settembre", "ottobre", "novembre", "dicembre",
)

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
fun chapterDateFromIso(raw: String?, zone: ZoneId = ZoneId.systemDefault()): Long? {
    val text = raw?.trim()?.takeIf(String::isNotBlank) ?: return null
    runCatching { Instant.parse(text) }.getOrNull()?.let { return it.toEpochMilli() }
    val match = isoDatePrefixRegex.find(text) ?: return null
    return runCatching {
        LocalDate.of(
            match.groupValues[1].toInt(),
            match.groupValues[2].toInt(),
            match.groupValues[3].toInt(),
        ).atStartOfDay(zone).toInstant().toEpochMilli()
    }.getOrNull()
}

/**
 * Data italiana per esteso (`03 Maggio 2022`, come la scrive MangaWorld in `i.chap-date`) →
 * epoch millis della mezzanotte in [zone]. Mese sconosciuto o formato diverso → `null`.
 */
fun chapterDateFromItalianDate(raw: String?, zone: ZoneId = ZoneId.systemDefault()): Long? {
    val text = raw?.trim()?.replace(Regex("""\s+"""), " ")?.takeIf(String::isNotBlank) ?: return null
    val match = italianDateRegex.find(text) ?: return null
    val month = ITALIAN_MONTHS.indexOf(match.groupValues[2].lowercase(Locale.ITALIAN))
    if (month < 0) return null
    return runCatching {
        LocalDate.of(match.groupValues[3].toInt(), month + 1, match.groupValues[1].toInt())
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
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
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val published = Instant.ofEpochMilli(publishedAtMillis).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(published, today)
    return when {
        days <= 0L -> "Oggi"
        days == 1L -> "Ieri"
        days < 7L -> "$days giorni fa"
        else -> "${published.dayOfMonth} ${ITALIAN_SHORT_MONTHS[published.monthValue - 1]} ${published.year}"
    }
}
