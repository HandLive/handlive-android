package app.handlive.android.feature.clipboard.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** QC3 card-number rule: 13–19 digits with single spaces or hyphens, Luhn-valid, in text of at most 256 characters. */
class SensitiveContentTest {
    @Test
    fun luhnValidCardNumbersAreSensitiveInAnyGrouping() {
        assertTrue(SensitiveContent.looksLikeCardNumber("4111111111111111"))
        assertTrue(SensitiveContent.looksLikeCardNumber("4111 1111 1111 1111"))
        assertTrue(SensitiveContent.looksLikeCardNumber("Thẻ: 5500-0000-0000-0004, hết hạn 12/28"))
        // 13 and 19 digits, the bounds of the rule.
        assertTrue(SensitiveContent.looksLikeCardNumber("4222222222222"))
        assertTrue(SensitiveContent.looksLikeCardNumber("6011 0000 0000 0000 001"))
    }

    @Test
    fun otherNumbersAreNotSensitive() {
        assertFalse(SensitiveContent.looksLikeCardNumber("4111 1111 1111 1112"))
        assertFalse(SensitiveContent.looksLikeCardNumber("Order number: HL-240917-0042"))
        assertFalse(SensitiveContent.looksLikeCardNumber("0912 345 678"))
        // 12 and 20 digits are outside 13–19 even when Luhn-valid.
        assertFalse(SensitiveContent.looksLikeCardNumber("411111111117"))
        assertFalse(SensitiveContent.looksLikeCardNumber("41111111111111111115"))
        // Two separators in a row break the run.
        assertFalse(SensitiveContent.looksLikeCardNumber("4111  1111  1111  1111"))
    }

    @Test
    fun longTextIsNeverChecked() {
        val text = "4111111111111111 " + "a".repeat(250)
        assertFalse(SensitiveContent.looksLikeCardNumber(text))
        assertTrue(SensitiveContent.looksLikeCardNumber(text.take(256)))
    }
}
