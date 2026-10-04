package com.lorenzo.mangadownloader.domain

import java.util.Locale
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class NumberFormatsJvmParityTest {

    @Test
    fun formatFixedMatchesJavaFormatter() {
        val rnd = Random(5)
        val values = listOf(0.0, -0.0, 0.05, 0.15, 0.25, 0.95, 1.005, 8.5, 9.95, 10.0, -0.04, -2.45, 123.45, 1e-7, 99.99) +
            List(3000) { (rnd.nextInt(-10_000, 10_000) / 100.0) } +
            List(3000) { rnd.nextDouble(-1000.0, 1000.0) }
        for (value in values) {
            for (decimals in 0..2) {
                assertEquals(
                    String.format(Locale.US, "%.${decimals}f", value),
                    formatFixed(value, decimals),
                    "value $value decimals $decimals",
                )
            }
        }
    }
}
