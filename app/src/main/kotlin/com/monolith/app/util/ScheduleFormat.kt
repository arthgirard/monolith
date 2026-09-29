package com.monolith.app.util

import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/**
 * Whether the device is on a 24-hour clock is a locale setting, so let java.time decide. Every
 * function here takes the app's locale (see appLocale), never the default: that one can stay on
 * the system language while Monolith is in another.
 */
fun formatScheduleTime(time: LocalTime, locale: Locale): String =
    time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))

/**
 * Day set as a short label: the common runs get a name ("Weekdays"), anything else lists the
 * abbreviated days in week order, starting on the locale's first day so a Sunday-first user
 * doesn't read "Sun" at the end of the row.
 */
fun formatScheduleDays(
    days: Set<DayOfWeek>,
    everyDayLabel: String,
    weekdaysLabel: String,
    weekendsLabel: String,
    locale: Locale,
): String = when (days) {
    ALL_DAYS -> everyDayLabel
    WEEKDAYS -> weekdaysLabel
    WEEKENDS -> weekendsLabel
    else -> weekOrder(locale).filter { it in days }
        .joinToString(" ") { it.getDisplayName(TextStyle.SHORT, locale) }
}

/** "Mon 22:00" -- the next fire, for the home screen's status card. */
fun formatNextFire(fire: ZonedDateTime, locale: Locale): String {
    val day = fire.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
    return "$day ${formatScheduleTime(fire.toLocalTime(), locale)}"
}

/** The seven days starting from the locale's first day of the week. */
fun weekOrder(locale: Locale): List<DayOfWeek> {
    val first = java.time.temporal.WeekFields.of(locale).firstDayOfWeek
    return (0..6).map { first.plus(it.toLong()) }
}

val ALL_DAYS: Set<DayOfWeek> = DayOfWeek.values().toSet()

val WEEKDAYS: Set<DayOfWeek> = setOf(
    DayOfWeek.MONDAY,
    DayOfWeek.TUESDAY,
    DayOfWeek.WEDNESDAY,
    DayOfWeek.THURSDAY,
    DayOfWeek.FRIDAY,
)

val WEEKENDS: Set<DayOfWeek> = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
