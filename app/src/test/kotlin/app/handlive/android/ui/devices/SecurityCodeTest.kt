package app.handlive.android.ui.devices

import org.junit.Assert.assertEquals
import org.junit.Test

/** PAIR-02 field 10: 8 lowercase hex digits, shown after pairing and in the details as two groups of four. */
class SecurityCodeTest {
    @Test
    fun theCodeKeepsItsLowercaseDigitsInTwoGroups() {
        assertEquals("fc64 7e0b", SecurityCode.grouped("fc647e0b"))
        assertEquals("0000 00a1", SecurityCode.grouped("000000a1"))
    }
}
