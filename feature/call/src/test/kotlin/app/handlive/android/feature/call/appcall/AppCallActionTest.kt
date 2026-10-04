package app.handlive.android.feature.call.appcall

import app.handlive.android.core.protocol.ErrorCode
import app.handlive.android.core.protocol.ack.Ack
import app.handlive.android.core.protocol.call.AppCallEndReason
import app.handlive.android.core.protocol.call.AppCallState
import app.handlive.android.core.protocol.call.CallPhase
import app.handlive.android.core.transport.capability.Feature
import app.handlive.android.feature.call.CallConstants
import app.handlive.android.feature.call.appcall.AppCallFixtures.TELEGRAM
import app.handlive.android.feature.call.context.PhoneState
import app.handlive.android.feature.call.testing.CallHarness
import app.handlive.android.feature.connection.capability.AndroidPermissions
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `call_event/action` for an app call (CALL-05 API 2): routed by `call_id` to the app's own intents, with the errors
 * in their order — `FEATURE_DISABLED`, `BAD_REQUEST`, `CALL_HFP_REQUIRED`, `CALL_ROUTE_FAILED`,
 * `CALL_APP_ACTION_UNAVAILABLE`, `INTERNAL` — while telephony calls keep their handling.
 */
class AppCallActionTest {
    private val sent = mutableListOf<String>()
    private val answer = FakeAppIntent("answer", sent)
    private val decline = FakeAppIntent("decline", sent)
    private val end = FakeAppIntent("end", sent)
    private val unknownId = "0192f3f0-6a1b-7c2d-8e3f-4a5b6c7d8e90"
    private val window = CallConstants.APP_CALL_LINK_WINDOW_MILLIS

    /** When the end of the link window is decided: the window and the grace for posts still on their way. */
    private val windowEnd = window + CallConstants.APP_CALL_LINK_GRACE_MILLIS

    private fun CallHarness.ringing(): String {
        appPost(AppCallFixtures.telegramRinging(answer = answer, decline = decline))
        return mac.appCalls().last().callId
    }

    private fun CallHarness.ongoing(): String {
        val callId = ringing()
        appRemove(RINGING_KEY)
        advance(300)
        appPost(AppCallFixtures.telegramInCall(actions = listOf(end)))
        return callId
    }

    private fun CallHarness.withMac() = also { connect(mac.withAppCalls()) }

    @Test
    fun answerInDirectModeSendsTheAppsAnswerIntent() =
        runTest {
            val h = CallHarness(this).withMac()
            val callId = h.ringing()

            val ack = h.action(h.mac, callId, "answer")

            assertTrue(ack.ok)
            assertEquals(JsonObject(emptyMap()), ack.data)
            assertEquals(listOf("answer"), sent)
            assertEquals("the answer starts the app's screen: with the option", listOf(true), answer.backgroundStarts)
            assertTrue(h.tap.posted.isEmpty())
        }

    @Test
    fun aCallAndroidDoesNotVouchForIsAnsweredThroughThePhoneEvenWithTheExemption() =
        runTest {
            val h = CallHarness(this).withMac()
            h.appPost(AppCallFixtures.telegramRinging(answer = answer, decline = decline, vouched = false))
            val callId =
                h.mac
                    .appCalls()
                    .last()
                    .callId

            assertTrue(h.action(h.mac, callId, "answer").ok)

            assertTrue("HandLive lends its exemption to no forged call", sent.isEmpty())
            assertSame(
                answer,
                h.tap.posted
                    .single()
                    .answer,
            )
        }

    @Test
    fun answerWithAudioPhoneIsTheSameAsWithoutAudio() =
        runTest {
            val h = CallHarness(this).withMac()
            val callId = h.ringing()

            assertTrue(h.action(h.mac, callId, "answer", "phone").ok)
            assertEquals(listOf("answer"), sent)
        }

    @Test
    fun answerInTapModePostsTheTapToAnswerNotificationAndSendsNothing() =
        runTest {
            val h = CallHarness(this).withMac()
            h.exemption.held = false
            val callId = h.ringing()

            val ack = h.action(h.mac, callId, "answer")

            assertTrue(ack.ok)
            assertTrue("the user taps, HandLive does not send", sent.isEmpty())
            val posted = h.tap.posted.single()
            assertEquals(callId, posted.callId)
            assertEquals("Telegram", posted.label)
            assertSame("its content intent is the app's answer intent", answer, posted.answer)
        }

    @Test
    fun theTapNotificationShowsTheCallerAndAnAnswerThatNothingFollowsEndsAsUnknown() =
        runTest {
            val h = CallHarness(this).withMac()
            h.exemption.held = false
            val callId = h.ringing()

            h.action(h.mac, callId, "answer")
            assertEquals(
                AppCallFixtures.CALLER,
                h.tap.posted
                    .single()
                    .caller,
            )
            h.appRemove(RINGING_KEY)
            h.advance(windowEnd)

            assertEquals(
                AppCallEndReason.UNKNOWN,
                h.mac
                    .appCalls()
                    .last()
                    .endReason,
            )
        }

    @Test
    fun theTapNotificationGoesWhenTheCallLeavesRinging() =
        runTest {
            val h = CallHarness(this).withMac()
            h.exemption.held = false
            val callId = h.ringing()
            h.action(h.mac, callId, "answer")
            assertTrue(h.tap.cancelled.isEmpty())

            h.appRemove(RINGING_KEY)
            assertEquals("the app's notification is gone: nothing to tap", listOf(callId), h.tap.cancelled)
            h.advance(200)
            h.appPost(AppCallFixtures.telegramInCall(actions = listOf(end)))

            assertEquals(
                AppCallState.ONGOING,
                h.mac
                    .appCalls()
                    .last()
                    .state,
            )
            assertTrue(callId in h.tap.cancelled)
        }

    @Test
    fun aTapNotificationThatCannotBeShownIsUnavailable() =
        runTest {
            val h = CallHarness(this).withMac()
            h.exemption.held = false
            h.tap.canShow = false
            val callId = h.ringing()

            assertError(ErrorCode.CALL_APP_ACTION_UNAVAILABLE, h.action(h.mac, callId, "answer"))
        }

    @Test
    fun rejectSendsTheDeclineIntentAndTheCallEndsAsDeclinedAtOnce() =
        runTest {
            val h = CallHarness(this).withMac()
            val callId = h.ringing()

            assertTrue(h.action(h.mac, callId, "reject").ok)
            assertEquals(listOf("decline"), sent)
            assertEquals("a decline lends no background start", listOf(false), decline.backgroundStarts)
            h.advance(12)
            h.appRemove(RINGING_KEY)

            val ended = h.mac.appCalls().last()
            assertEquals(AppCallState.ENDED, ended.state)
            assertEquals(AppCallEndReason.DECLINED, ended.endReason)
        }

    @Test
    fun endSendsTheEndActionOfTheInCallNotification() =
        runTest {
            val h = CallHarness(this).withMac()
            val callId = h.ongoing()

            assertTrue(h.action(h.mac, callId, "end").ok)
            assertEquals(listOf("end"), sent)
            assertEquals("an end lends no background start", listOf(false), end.backgroundStarts)
            h.advance(20)
            h.appRemove(IN_CALL_KEY)

            assertEquals(
                AppCallEndReason.ENDED,
                h.mac
                    .appCalls()
                    .last()
                    .endReason,
            )
        }

    @Test
    fun anAnswerWithoutAnInCallNotificationEndsAsUnknown() =
        runTest {
            val h = CallHarness(this).withMac()
            val callId = h.ringing()
            h.action(h.mac, callId, "answer")

            h.appRemove(RINGING_KEY)
            h.advance(windowEnd)

            assertEquals(
                AppCallEndReason.UNKNOWN,
                h.mac
                    .appCalls()
                    .last()
                    .endReason,
            )
        }

    @Test
    fun answerWithAudioMacIsARouteFailureAndSendsNothing() =
        runTest {
            val h = CallHarness(this).withMac()
            val callId = h.ringing()

            assertError(ErrorCode.CALL_ROUTE_FAILED, h.action(h.mac, callId, "answer", "mac"))
            assertTrue(sent.isEmpty())
            assertTrue(h.tap.posted.isEmpty())
        }

    @Test
    fun anActionTheNotificationDoesNotOfferIsUnavailable() =
        runTest {
            val h = CallHarness(this).withMac()
            val callId = h.ringing()

            assertError(ErrorCode.CALL_APP_ACTION_UNAVAILABLE, h.action(h.mac, callId, "end"))

            h.appPost(AppCallFixtures.telegramRinging(answer = null, decline = null))
            assertError(ErrorCode.CALL_APP_ACTION_UNAVAILABLE, h.action(h.mac, callId, "answer"))
            assertError(ErrorCode.CALL_APP_ACTION_UNAVAILABLE, h.action(h.mac, callId, "reject"))
            assertTrue(sent.isEmpty())
        }

    @Test
    fun aRingingNotificationThatIsGoneOffersNothingEvenInsideTheLinkWindow() =
        runTest {
            val h = CallHarness(this).withMac()
            val callId = h.ringing()
            h.appRemove(RINGING_KEY)

            assertError(ErrorCode.CALL_APP_ACTION_UNAVAILABLE, h.action(h.mac, callId, "answer"))
            assertError(ErrorCode.CALL_APP_ACTION_UNAVAILABLE, h.action(h.mac, callId, "reject"))
            assertTrue(sent.isEmpty())
        }

    @Test
    fun answerOnAnOngoingCallAndEndWithoutAnEndActionAreUnavailable() =
        runTest {
            val h = CallHarness(this).withMac()
            val callId = h.ringing()
            h.appRemove(RINGING_KEY)
            h.advance(100)
            h.appPost(AppCallFixtures.telegramInCall(actions = listOf(end, FakeAppIntent("mute", sent))))

            assertError(ErrorCode.CALL_APP_ACTION_UNAVAILABLE, h.action(h.mac, callId, "end"))
            assertError(ErrorCode.CALL_APP_ACTION_UNAVAILABLE, h.action(h.mac, callId, "answer"))
            assertTrue(sent.isEmpty())
        }

    @Test
    fun anIntentTheAppCanceledIsUnavailableAndLeavesNoDeclineFlag() =
        runTest {
            val h = CallHarness(this).withMac()
            val callId = h.ringing()
            decline.canceled = true

            assertError(ErrorCode.CALL_APP_ACTION_UNAVAILABLE, h.action(h.mac, callId, "reject"))
            h.appRemove(RINGING_KEY)
            h.advance(windowEnd)

            assertEquals(
                AppCallEndReason.MISSED,
                h.mac
                    .appCalls()
                    .last()
                    .endReason,
            )
        }

    @Test
    fun anIntentThatThrowsIsAnInternalErrorAndTheNextActionStillWorks() =
        runTest {
            val h = CallHarness(this).withMac()
            val boom = AppIntent { _ -> throw IllegalStateException("the app crashed") }
            h.appPost(AppCallFixtures.telegramRinging(answer = boom, decline = decline))
            val callId =
                h.mac
                    .appCalls()
                    .last()
                    .callId

            assertError(ErrorCode.INTERNAL, h.action(h.mac, callId, "answer"))
            assertTrue(h.action(h.mac, callId, "reject").ok)
        }

    @Test
    fun holdMuteAndDtmfNeedHfpWhateverTheCall() =
        runTest {
            val h = CallHarness(this).withMac()
            val callId = h.ringing()

            for (action in listOf("hold", "unhold", "dtmf", "mute")) {
                val ack = h.action(h.mac, callId, action)
                assertError(ErrorCode.CALL_HFP_REQUIRED, ack)
                assertEquals(JsonPrimitive(action), ack.error?.details?.get("action"))
            }
            assertTrue(sent.isEmpty())
        }

    @Test
    fun malformedActionsOfAnAppCallAreBadRequests() =
        runTest {
            val h = CallHarness(this).withMac()
            val callId = h.ringing()

            for (data in listOf(
                """{"call_id":"$callId"}""",
                """{"call_id":"$callId","action":"transfer"}""",
                """{"call_id":"$callId","action":"answer","audio":"speaker"}""",
            )) {
                val id = h.request(h.mac, "action", data)
                assertError(ErrorCode.BAD_REQUEST, h.mac.acks().last { it.re == id })
            }
            assertTrue(sent.isEmpty())
        }

    @Test
    fun noTelephonyPermissionIsNeededForAnAppCall() =
        runTest {
            val h = CallHarness(this).withMac()
            val callId = h.ringing()
            h.access.granted.clear()

            assertTrue(h.action(h.mac, callId, "reject").ok)
            assertTrue(h.permissionsAsked.isEmpty())
            assertTrue(h.telecom.calls.isEmpty())
        }

    @Test
    fun aSessionWithoutAppCallsIsRefusedWithFeatureDisabledAndNothingIsSent() =
        runTest {
            val h = CallHarness(this).withMac()
            h.connect(h.iphone)
            val callId = h.ringing()

            for (action in listOf("answer", "reject", "end")) {
                assertError(ErrorCode.FEATURE_DISABLED, h.action(h.iphone, callId, action))
            }
            h.mac.effective.value = setOf(Feature.CALL)
            assertError(ErrorCode.FEATURE_DISABLED, h.action(h.mac, callId, "reject"))
            assertTrue(sent.isEmpty())
        }

    @Test
    fun withAppCallsOffOnThePhoneEverySessionIsRefused() =
        runTest {
            val h = CallHarness(this).withMac()
            val callId = h.ringing()
            h.appAccess.enabled = false

            assertError(ErrorCode.FEATURE_DISABLED, h.action(h.mac, callId, "reject"))
            assertTrue(sent.isEmpty())
        }

    @Test
    fun anUnknownCallIdGoesThroughTheTelephonyChecks() =
        runTest {
            val h = CallHarness(this).withMac()
            h.ringing()

            assertError(ErrorCode.CALL_NOT_FOUND, h.action(h.mac, unknownId, "answer"))
            assertError(ErrorCode.CALL_NOT_FOUND, h.action(h.mac, unknownId, "end"))
            assertTrue(sent.isEmpty())
            assertTrue(h.telecom.calls.isEmpty())
        }

    @Test
    fun anAppCallThatEndedIsNotFound() =
        runTest {
            val h = CallHarness(this).withMac()
            val callId = h.ongoing()
            h.appRemove(IN_CALL_KEY)

            assertError(ErrorCode.CALL_NOT_FOUND, h.action(h.mac, callId, "end"))
        }

    @Test
    fun aSessionWithAppCallsButNotTelephonyGetsNotFoundInsteadOfFeatureDisabled() =
        runTest {
            val h = CallHarness(this)
            h.connect(h.mac)
            h.mac.effective.value = setOf(Feature.APP_CALLS)

            assertError(ErrorCode.CALL_NOT_FOUND, h.action(h.mac, unknownId, "end"))
            assertError(ErrorCode.CALL_HFP_REQUIRED, h.action(h.mac, unknownId, "hold"))
            val malformed = h.request(h.mac, "action", "{}")
            assertError(ErrorCode.BAD_REQUEST, h.mac.acks().last { it.re == malformed })
            assertTrue(h.telecom.calls.isEmpty())
        }

    @Test
    fun aSessionWithNeitherGetsFeatureDisabledForAnUnknownCallId() =
        runTest {
            val h = CallHarness(this)
            h.connect(h.mac)
            h.mac.effective.value = emptySet()

            assertError(ErrorCode.FEATURE_DISABLED, h.action(h.mac, unknownId, "end"))
        }

    @Test
    fun aTelephonyCallAndAnAppCallAreControlledEachByItsOwnCallId() =
        runTest {
            val h = CallHarness(this).withMac()
            val appId = h.ringing()
            h.ring()
            val phoneId =
                h.mac
                    .states()
                    .last()
                    .callId

            assertTrue(h.action(h.mac, phoneId, "answer").ok)
            assertEquals(listOf("accept"), h.telecom.calls)
            assertTrue("the app call is untouched", sent.isEmpty())

            assertTrue(h.action(h.mac, appId, "reject").ok)
            assertEquals(listOf("accept"), h.telecom.calls)
            assertEquals(listOf("decline"), sent)
            h.phone(PhoneState.OFFHOOK)
            assertEquals(
                CallPhase.OFFHOOK,
                h.mac
                    .states()
                    .last()
                    .state,
            )
        }

    @Test
    fun aRepeatedRequestIdSendsTheIntentOnce() =
        runTest {
            val h = CallHarness(this).withMac()
            val callId = h.ringing()
            val data = """{"call_id":"$callId","action":"reject"}"""

            val id = h.request(h.mac, "action", data)
            h.request(h.mac, "action", data, id = id)

            assertEquals(listOf("decline"), sent)
            assertEquals(2, h.mac.acks().count { it.re == id })
        }

    @Test
    fun theLabelAndTheCallerNeverAppearInAnAck() =
        runTest {
            val h = CallHarness(this).withMac()
            val callId = h.ringing()

            val acks = listOf(h.action(h.mac, callId, "end"), h.action(h.mac, callId, "answer", "mac"))

            assertTrue(acks.none { it.toString().contains("Nguy") || it.toString().contains(TELEGRAM) })
            assertNull(acks.first().error?.details)
        }

    private fun assertError(
        code: ErrorCode,
        ack: Ack,
    ) {
        assertEquals(false, ack.ok)
        assertEquals(code.name, ack.error?.code)
    }

    private companion object {
        const val RINGING_KEY = "0|org.telegram.messenger|203|null|10148"
        const val IN_CALL_KEY = "0|org.telegram.messenger|202|null|10148"
    }
}
