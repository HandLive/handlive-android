package app.handlive.android.feature.clipboard.engine

/**
 * QC3 card-number rule: text of at most 256 characters containing a run of 13–19 digits (single spaces or hyphens
 * allowed between groups) that passes the Luhn check. The `IS_SENSITIVE` extra is checked by the caller.
 */
object SensitiveContent {
    private const val MAX_TEXT = 256
    private const val MIN_DIGITS = 13
    private const val MAX_DIGITS = 19
    private const val LUHN_MODULO = 10
    private const val DOUBLED_OVERFLOW = 9
    private val RUN = Regex("(?<![0-9])[0-9](?:[ -]?[0-9]){12,}(?![0-9])")

    fun looksLikeCardNumber(text: String): Boolean {
        if (text.length > MAX_TEXT) return false
        return RUN.findAll(text).any { match ->
            val digits = match.value.filter(Char::isDigit)
            digits.length in MIN_DIGITS..MAX_DIGITS && luhn(digits)
        }
    }

    internal fun luhn(digits: String): Boolean {
        var sum = 0
        digits.reversed().forEachIndexed { index, char ->
            var value = char - '0'
            if (index % 2 == 1) {
                value *= 2
                if (value > DOUBLED_OVERFLOW) value -= DOUBLED_OVERFLOW
            }
            sum += value
        }
        return sum % LUHN_MODULO == 0
    }
}
