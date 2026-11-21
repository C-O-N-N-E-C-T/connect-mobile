package com.connect.mobile.ui.common

import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class ExpiryFormatterTest {
    private val expiry = Instant.parse("2026-11-22T10:00:00Z") // 13:00 in Istanbul (UTC+3)
    private val istanbul = ZoneId.of("Europe/Istanbul")

    @Test fun turkishLocaleUsesLocalTimeAndZone() {
        val text = ExpiryFormatter.format(expiry, istanbul, Locale.forLanguageTag("tr-TR"))
        assertTrue(text, text.contains("13:00"))
        assertTrue(text, text.contains("2026"))
        assertTrue(text, text.endsWith("(Europe/Istanbul)"))
    }

    @Test fun englishLocaleUsesLocalTimeAndZone() {
        val text = ExpiryFormatter.format(expiry, istanbul, Locale.US)
        assertTrue(text, text.contains("1:00"))
        assertTrue(text, text.contains("Nov"))
        assertTrue(text, text.endsWith("(Europe/Istanbul)"))
    }
}
