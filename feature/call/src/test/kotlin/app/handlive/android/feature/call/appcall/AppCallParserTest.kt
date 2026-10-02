package app.handlive.android.feature.call.appcall

import app.handlive.android.feature.call.appcall.AppCallFixtures.CALL_TYPE_INCOMING
import app.handlive.android.feature.call.appcall.AppCallFixtures.CALL_TYPE_ONGOING
import app.handlive.android.feature.call.appcall.AppCallFixtures.CALL_TYPE_SCREENING
import app.handlive.android.feature.call.appcall.AppCallFixtures.TELEGRAM
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * CALL-05 detection: a call notification is `CallStyle` (`android.callType` 1 ringing, 2 ongoing, 3 screening and any
 * other type ignored); every other notification, category `call` or not, never starts a call and is not read further.
 */
class AppCallParserTest {
    @Test
    fun anIncomingCallStyleNotificationIsRinging() {
        assertEquals(AppCallShape.RINGING, AppCallParser.shape(AppCallFixtures.telegramRinging()))
    }

    @Test
    fun anOngoingCallStyleNotificationIsOngoing() {
        val ongoing =
            AppCallFixtures.notification(
                key = "k",
                packageName = TELEGRAM,
                callType = CALL_TYPE_ONGOING,
                ongoing = true,
                hangUp = FakeAppIntent("hangup"),
            )
        assertEquals(AppCallShape.ONGOING, AppCallParser.shape(ongoing))
    }

    @Test
    fun aScreeningOrUnknownCallTypeIsIgnored() {
        for (type in listOf(CALL_TYPE_SCREENING, 0, 4)) {
            val screening = AppCallFixtures.notification("k", TELEGRAM, callType = type)
            assertNull("call type $type", AppCallParser.shape(screening))
        }
    }

    @Test
    fun aNotificationWithoutCallStyleNeverCreatesAContext() {
        val ongoing = AppCallFixtures.notification("k", TELEGRAM, ongoing = true)
        val posted = AppCallFixtures.notification("k", TELEGRAM, ongoing = false)

        assertNull(AppCallParser.shape(ongoing))
        assertNull(AppCallParser.shape(posted))
    }

    @Test
    fun anOrdinaryNotificationIsNotACall() {
        assertNull(AppCallParser.shape(AppCallFixtures.telegramInCall()))
    }

    @Test
    fun theShapeNeverReadsTheCallerOrTheIntents() {
        var reads = 0
        val notifications =
            listOf(
                AppCallFixtures.telegramRinging { reads++ },
                AppCallFixtures.telegramInCall { reads++ },
                AppCallFixtures.notification("k", TELEGRAM, onCallerRead = { reads++ }, onIntentsRead = { reads++ }),
                AppCallFixtures.notification("k", TELEGRAM, ongoing = true, onIntentsRead = { reads++ }),
            )
        notifications.forEach(AppCallParser::shape)
        assertEquals(0, reads)
    }

    @Test
    fun theEndActionIsTheHangUpIntentElseTheOnlyAction() {
        val hangUp = FakeAppIntent("hangup")
        val action = FakeAppIntent("action")
        val other = FakeAppIntent("other")

        val both = AppCallFixtures.notification("k", TELEGRAM, hangUp = hangUp, actions = listOf(action))
        assertSame(hangUp, AppCallEndAction.select(both))

        val single = AppCallFixtures.notification("k", TELEGRAM, actions = listOf(action))
        assertSame(action, AppCallEndAction.select(single))

        val two = AppCallFixtures.notification("k", TELEGRAM, actions = listOf(action, other))
        assertNull("two actions: no title is matched", AppCallEndAction.select(two))

        assertNull(AppCallEndAction.select(AppCallFixtures.notification("k", TELEGRAM)))
        val unsendable = AppCallFixtures.notification("k", TELEGRAM, actions = listOf(null))
        assertNull("an action without an intent", AppCallEndAction.select(unsendable))
    }

    @Test
    fun theCallTypeConstantsAreThoseOfNotificationCallStyle() {
        assertEquals(CALL_TYPE_INCOMING, AppCallParser.CALL_TYPE_INCOMING)
        assertEquals(CALL_TYPE_ONGOING, AppCallParser.CALL_TYPE_ONGOING)
    }
}
