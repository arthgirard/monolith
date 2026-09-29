package com.monolith.app.util

import android.content.Context
import android.icu.text.RelativeDateTimeFormatter
import android.icu.util.ULocale
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAccessor
import java.util.Locale

/**
 * The language Monolith's strings resolve in: the in-app choice, else the system's. Formatting
 * takes this rather than [Locale.getDefault], which can stay on the system language while the
 * app is in another, and dates would then read in a different language from the sentence around
 * them.
 */
fun Context.appLocale(): Locale = resources.configuration.locales[0]

/** [Context.appLocale] for composables, following a language change without a restart. */
@Composable
@ReadOnlyComposable
fun appLocale(): Locale = LocalConfiguration.current.locales[0]

/**
 * [temporal] laid out the way [locale] writes the fields in [skeleton] ("MMMd", "yMMMM"...): the
 * order, punctuation and names all come from the locale, never from an English pattern.
 */
fun formatSkeleton(temporal: TemporalAccessor, skeleton: String, locale: Locale): String =
    DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(temporal)

/** A date and time, on the phone's 12- or 24-hour clock. */
fun formatDateTime(context: Context, millis: Long): String {
    val skeleton = if (DateFormat.is24HourFormat(context)) "yMMMdHHmm" else "yMMMdhmma"
    return formatSkeleton(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()), skeleton, context.appLocale())
}

/**
 * "5 minutes ago", at minute resolution like DateUtils.getRelativeTimeSpanString, which can't be
 * given a locale. A week or more out shows the date instead.
 */
fun formatRelativeTime(thenMillis: Long, nowMillis: Long, locale: Locale): String {
    val formatter = RelativeDateTimeFormatter.getInstance(ULocale.forLocale(locale))
    val delta = nowMillis - thenMillis
    val direction = if (delta >= 0) RelativeDateTimeFormatter.Direction.LAST else RelativeDateTimeFormatter.Direction.NEXT
    val minutes = Math.abs(delta) / 60_000
    return when {
        minutes < 1 -> formatter.format(RelativeDateTimeFormatter.Direction.PLAIN, RelativeDateTimeFormatter.AbsoluteUnit.NOW)
        minutes < 60 -> formatter.format(minutes.toDouble(), direction, RelativeDateTimeFormatter.RelativeUnit.MINUTES)
        minutes < 24 * 60 -> formatter.format((minutes / 60).toDouble(), direction, RelativeDateTimeFormatter.RelativeUnit.HOURS)
        minutes < 7 * 24 * 60 -> formatter.format((minutes / (24 * 60)).toDouble(), direction, RelativeDateTimeFormatter.RelativeUnit.DAYS)
        else -> {
            val date = Instant.ofEpochMilli(thenMillis).atZone(ZoneId.systemDefault()).toLocalDate()
            formatSkeleton(date, if (date.year == LocalDate.now().year) "MMMd" else "yMMMd", locale)
        }
    }
}
