package app.handlive.android.ui.devices

import android.icu.text.RelativeDateTimeFormatter
import android.icu.util.ULocale
import java.util.Locale

/**
 * "2 minutes ago" / "2 phút trước" through ICU in the app's language (Writing, "Numbers, dates, times"): never built
 * by hand, and not `DateUtils`, which follows the system language instead of the per-app one.
 */
object RelativeTime {
    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR

    fun format(
        then: Long,
        now: Long,
        locale: Locale,
    ): String {
        val formatter = RelativeDateTimeFormatter.getInstance(ULocale.forLocale(locale))
        val elapsed = (now - then).coerceAtLeast(0)
        return when {
            elapsed < MINUTE -> {
                formatter.format(
                    RelativeDateTimeFormatter.Direction.PLAIN,
                    RelativeDateTimeFormatter.AbsoluteUnit.NOW,
                )
            }

            elapsed < HOUR -> {
                past(formatter, elapsed / MINUTE, RelativeDateTimeFormatter.RelativeUnit.MINUTES)
            }

            elapsed < DAY -> {
                past(formatter, elapsed / HOUR, RelativeDateTimeFormatter.RelativeUnit.HOURS)
            }

            else -> {
                past(formatter, elapsed / DAY, RelativeDateTimeFormatter.RelativeUnit.DAYS)
            }
        }
    }

    private fun past(
        formatter: RelativeDateTimeFormatter,
        amount: Long,
        unit: RelativeDateTimeFormatter.RelativeUnit,
    ): String = formatter.format(amount.toDouble(), RelativeDateTimeFormatter.Direction.LAST, unit)
}
