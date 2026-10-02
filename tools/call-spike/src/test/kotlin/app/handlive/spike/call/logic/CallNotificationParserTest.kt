package app.handlive.spike.call.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallNotificationParserTest {
    private fun facts(
        packageName: String = "org.telegram.messenger",
        category: String? = null,
        callType: Int? = null,
        answer: Boolean = false,
        decline: Boolean = false,
        hangUp: Boolean = false,
        actions: List<String> = emptyList(),
        ongoing: Boolean = false,
        channel: String? = "incoming_calls40",
    ) = NotificationFacts(
        packageName = packageName,
        key = "0|$packageName|203|null|10148",
        category = category,
        channelId = channel,
        callType = callType,
        hasCallPerson = callType != null,
        hasAnswerIntent = answer,
        hasDeclineIntent = decline,
        hasHangUpIntent = hangUp,
        actionTitles = actions,
        ongoing = ongoing,
    )

    @Test
    fun `incoming CallStyle is a ringing call that can be answered and declined`() {
        val call = CallNotificationParser.parse(facts(category = "call", callType = 1, answer = true, decline = true))

        assertNotNull(call)
        assertEquals(CallPhase.RINGING, call!!.phase)
        assertTrue(call.callStyle)
        assertTrue(call.canAnswer)
        assertTrue(call.canDecline)
        assertFalse(call.canHangUp)
    }

    @Test
    fun `ongoing CallStyle is an ongoing call that can be hung up`() {
        val call = CallNotificationParser.parse(facts(category = "call", callType = 2, hangUp = true, ongoing = true))

        assertEquals(CallPhase.ONGOING, call!!.phase)
        assertTrue(call.canHangUp)
    }

    @Test
    fun `screening CallStyle maps to screening`() {
        assertEquals(CallPhase.SCREENING, CallNotificationParser.parse(facts(callType = 3))!!.phase)
    }

    @Test
    fun `call category without CallStyle is ongoing when it is an ongoing notification`() {
        val call = CallNotificationParser.parse(facts(category = "call", ongoing = true, actions = listOf("Kết thúc")))

        assertEquals(CallPhase.ONGOING, call!!.phase)
        assertFalse(call.callStyle)
        assertEquals(listOf("Kết thúc"), call.actionTitles)
    }

    @Test
    fun `call category without CallStyle and not ongoing is unknown`() {
        assertEquals(CallPhase.UNKNOWN, CallNotificationParser.parse(facts(category = "call"))!!.phase)
    }

    @Test
    fun `ordinary notification is not a call`() {
        assertNull(CallNotificationParser.parse(facts(category = "msg", channel = "Other3")))
    }

    @Test
    fun `key is reported only as a short hash`() {
        val call = CallNotificationParser.parse(facts(callType = 1))!!

        assertEquals(8, call.keyHash.length)
        assertFalse(call.keyHash.contains("telegram"))
    }

    @Test
    fun `watched apps are recognised`() {
        assertTrue(CallNotificationParser.isWatchedApp("org.telegram.messenger"))
        assertTrue(CallNotificationParser.isWatchedApp("com.zing.zalo"))
        assertFalse(CallNotificationParser.isWatchedApp("com.google.android.gm"))
    }
}
