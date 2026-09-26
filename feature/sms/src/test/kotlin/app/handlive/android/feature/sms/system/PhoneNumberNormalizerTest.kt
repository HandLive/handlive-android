package app.handlive.android.feature.sms.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Android's libphonenumber through `PhoneNumberUtils` (5.1.5 addresses, SMS-04 API 1 logic 2). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhoneNumberNormalizerTest {
    private val numbers = PhoneNumberNormalizer()

    @Test
    fun validNumbersBecomeE164InTheSimsCountry() {
        assertEquals("+84912345678", numbers.recipient("0912345678", "VN"))
        assertEquals("+84912345678", numbers.recipient("091 234 5678", "VN"))
        assertEquals("+84912345678", numbers.recipient("+84 912 345 678", "US"))
        assertEquals("+16502530000", numbers.recipient("(650) 253-0000", "US"))
        assertEquals("+84912345678", numbers.e164OrSelf("0912345678", "VN"))
    }

    @Test
    fun shortCodesStayAndEverythingElseIsInvalid() {
        assertEquals("8198", numbers.recipient("8198", "VN"))
        assertEquals("12345678", numbers.recipient("12345678", "VN"))
        assertNull(numbers.recipient("12", "VN"))
        assertNull(numbers.recipient("VIETTEL", "VN"))
        assertNull(numbers.recipient("091234567890123", "VN"))
        assertNull(numbers.recipient("+84 12", "VN"))
        assertEquals("sender names are kept as they are", "VIETTEL", numbers.e164OrSelf("VIETTEL", "VN"))
        assertEquals("1234", numbers.e164OrSelf("1234", "VN"))
    }
}
