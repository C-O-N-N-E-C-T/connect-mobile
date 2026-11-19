package com.connect.mobile.ui.common

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Grant expiry in the device's zone and locale, plus the zone ID so the time is never ambiguous. */
object ExpiryFormatter {
    fun format(instant: Instant, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(locale)
            .withZone(zone)
        return "${formatter.format(instant)} (${zone.id})"
    }
}
