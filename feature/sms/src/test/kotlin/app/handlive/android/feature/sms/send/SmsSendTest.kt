package app.handlive.android.feature.sms.send

import app.handlive.android.core.protocol.ErrorCode
import app.handlive.android.core.protocol.envelope.MessageType
import app.handlive.android.core.protocol.sms.SmsSendAckData
import app.handlive.android.core.protocol.sms.SmsSendStatus
import app.handlive.android.core.protocol.sms.SmsStatusData
import app.handlive.android.feature.connection.capability.SimCard
import app.handlive.android.feature.sms.testing.SmsHarness
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** SMS-04 on the phone: `sms/send` checks (API 1), the radio (API 3), `sms/status` (API 2), de-duplication. */
class SmsSendTest {
    private fun TestScope.harness() = SmsHarness(this).also { it.connect(it.mac, it.iphone) }

    private suspend fun SmsHarness.send(
        body: String = "Ok, 3h mình có mặt",
        addresses: String = "[\"0900000123\"]",
        subId: Int? = null,
        localId: String = newLocalId(),
        id: String? = null,
    ): String {
        val sim = subId?.let { ",\"sub_id\":$it" }.orEmpty()
        val data = """{"local_id":"$localId","thread_id":42,"addresses":$addresses,"body":"$body"$sim}"""
        return if (id == null) request(mac, "send", data) else request(mac, "send", data, id)
    }

    @Test
    fun anAcceptedMessageIsAckedThenSentThroughTheChosenSimAndReportsSending() =
        runTest {
            val h = harness()
            val localId = h.newLocalId()
            val id = h.send(localId = localId, subId = 2)

            val ack = h.mac.acks().single()
            assertEquals(id, ack.re)
            assertEquals(SmsSendAckData(true, 1), SmsHarness.decode(SmsSendAckData.serializer(), SmsHarness.data(ack)))
            val radio = h.radio.sent.single()
            assertEquals("+84900000123", radio.destination)
            assertEquals(2, radio.subId)
            assertEquals(localId, radio.localId)
            // API 1 logic 1: the ack leaves before the radio reports anything.
            val order = h.mac.sent.map { it.type }
            assertEquals(listOf(MessageType.ACK, MessageType.SMS), order)
            assertEquals(listOf(SmsStatusData(localId, null, SmsSendStatus.SENDING)), h.mac.statuses())
            assertTrue("sms/status goes to the sender only", h.iphone.sent.isEmpty())
        }

    @Test
    fun everyPartSentThenEveryPartDeliveredMovesToSentThenDelivered() =
        runTest {
            val h = harness()
            val localId = h.newLocalId()
            h.send(body = "a".repeat(400), localId = localId)
            assertEquals(
                3,
                SmsHarness.decode(SmsSendAckData.serializer(), SmsHarness.data(h.mac.acks().single())).parts,
            )

            h.module.onSent(localId, 0, SentResult.OK)
            h.module.onSent(localId, 1, SentResult.OK)
            h.run()
            assertEquals(listOf(SmsSendStatus.SENDING), h.mac.statuses().map { it.status })
            h.module.onSent(localId, 2, SentResult.OK)
            h.module.onDelivered(localId, 0, DeliveryReport.DELIVERED)
            h.module.onDelivered(localId, 1, DeliveryReport.PENDING)
            h.module.onDelivered(localId, 1, DeliveryReport.DELIVERED)
            h.run()
            assertEquals(listOf(SmsSendStatus.SENDING, SmsSendStatus.SENT), h.mac.statuses().map { it.status })
            h.module.onDelivered(localId, 2, DeliveryReport.DELIVERED)
            h.run()
            assertEquals(
                listOf(SmsSendStatus.SENDING, SmsSendStatus.SENT, SmsSendStatus.DELIVERED),
                h.mac.statuses().map { it.status },
            )
        }

    @Test
    fun theFirstFailingPartDecidesAndLaterResultsAreIgnored() =
        runTest {
            val h = harness()
            val localId = h.newLocalId()
            h.send(body = "a".repeat(400), localId = localId)
            h.module.onSent(localId, 0, SentResult.OK)
            h.module.onSent(localId, 1, SentResult.ERROR_NO_SERVICE)
            h.module.onSent(localId, 2, SentResult.OK)
            h.module.onSent(localId, 1, SentResult.ERROR_RADIO_OFF)
            h.module.onDelivered(localId, 0, DeliveryReport.DELIVERED)
            h.run()
            assertEquals(
                listOf(
                    SmsStatusData(localId, null, SmsSendStatus.SENDING),
                    SmsStatusData(localId, null, SmsSendStatus.FAILED, ErrorCode.SMS_NO_SERVICE.name),
                ),
                h.mac.statuses(),
            )
        }

    @Test
    fun theSentResultCodesMapToTheSmsErrors() {
        assertEquals(null, SentResult.errorOf(SentResult.OK))
        assertEquals(ErrorCode.SMS_NO_SERVICE, SentResult.errorOf(SentResult.ERROR_NO_SERVICE))
        assertEquals(ErrorCode.SMS_RADIO_OFF, SentResult.errorOf(SentResult.ERROR_RADIO_OFF))
        assertEquals(ErrorCode.SMS_LIMIT_EXCEEDED, SentResult.errorOf(SentResult.ERROR_LIMIT_EXCEEDED))
        assertEquals(ErrorCode.SMS_GENERIC_FAILURE, SentResult.errorOf(SentResult.ERROR_GENERIC_FAILURE))
        assertEquals(ErrorCode.SMS_GENERIC_FAILURE, SentResult.errorOf(SentResult.ERROR_NULL_PDU))
        assertEquals(ErrorCode.SMS_GENERIC_FAILURE, SentResult.errorOf(UNKNOWN_RESULT))
    }

    @Test
    fun aFailedDeliveryReportAfterSentDoesNotLowerTheStatus() =
        runTest {
            val h = harness()
            val localId = h.newLocalId()
            h.send(localId = localId)
            h.module.onSent(localId, 0, SentResult.OK)
            h.module.onDelivered(localId, 0, DeliveryReport.FAILED)
            h.module.onSent(localId, 0, SentResult.ERROR_GENERIC_FAILURE)
            h.module.onSent(localId, 0, SentResult.OK)
            h.run()
            assertEquals(listOf(SmsSendStatus.SENDING, SmsSendStatus.SENT), h.mac.statuses().map { it.status })
        }

    @Test
    fun aRadioThatThrowsFailsTheMessageWithAGenericFailure() =
        runTest {
            val h = harness()
            h.radio.failing = true
            val localId = h.newLocalId()
            h.send(localId = localId)
            assertTrue(
                h.mac
                    .acks()
                    .single()
                    .ok,
            )
            assertEquals(
                listOf(SmsStatusData(localId, null, SmsSendStatus.FAILED, ErrorCode.SMS_GENERIC_FAILURE.name)),
                h.mac.statuses(),
            )
        }

    @Test
    fun aRetriedLocalIdIsNotSentAgainAndGetsTheSameAckAndItsCurrentStatus() =
        runTest {
            val h = harness()
            val localId = h.newLocalId()
            h.send(localId = localId, body = "a".repeat(200))
            h.module.onSent(localId, 0, SentResult.OK)
            h.module.onSent(localId, 1, SentResult.OK)
            h.run()
            // After a restart of the client: a new envelope id, the same local_id (API 1 logic 4 and 7).
            h.send(localId = localId, body = "a".repeat(200))

            assertEquals(1, h.radio.sent.size)
            val acks = h.mac.acks().map { SmsHarness.decode(SmsSendAckData.serializer(), SmsHarness.data(it)) }
            assertEquals(listOf(SmsSendAckData(true, 2), SmsSendAckData(true, 2)), acks)
            assertEquals(
                listOf(SmsSendStatus.SENDING, SmsSendStatus.SENT, SmsSendStatus.SENT),
                h.mac.statuses().map { it.status },
            )
        }

    @Test
    fun aRepeatedEnvelopeIdGetsTheOldAckWithoutBeingProcessedAgain() =
        runTest {
            val h = harness()
            val id = h.send()
            h.send(id = id)
            assertEquals(1, h.radio.sent.size)
            assertEquals(listOf(id, id), h.mac.acks().map { it.re })
        }

    @Test
    fun theChecksFollowTheOrderOfTheErrorTable() =
        runTest {
            val h = harness()
            h.access.enabled = false
            h.send(body = "", addresses = "[]")
            h.access.enabled = true
            h.access.missing += "SEND_SMS"
            h.send(body = "", addresses = "[]")
            h.access.missing.clear()
            h.send(addresses = "[\"0900000123\",\"0911111111\"]")
            h.send(body = "   ")
            h.send(body = "a".repeat(1_601), addresses = "[\"not a number\"]")
            h.send(addresses = "[\"not a number\"]", subId = 9)
            h.send(subId = 9)
            val codes = h.mac.acks().map { it.error?.code }
            assertEquals(
                listOf(
                    ErrorCode.FEATURE_DISABLED,
                    ErrorCode.PERMISSION_MISSING,
                    ErrorCode.BAD_REQUEST,
                    ErrorCode.BAD_REQUEST,
                    ErrorCode.PAYLOAD_TOO_LARGE,
                    ErrorCode.SMS_INVALID_ADDRESS,
                    ErrorCode.SMS_SIM_UNAVAILABLE,
                ).map { it.name },
                codes,
            )
            val permission =
                h.mac
                    .acks()[1]
                    .error!!
                    .details!!["permission"]!!
                    .jsonPrimitive.content
            assertEquals("android.permission.SEND_SMS", permission)
            assertEquals(listOf("pair-mac" to "android.permission.SEND_SMS"), h.permissionsAsked)
            val sims =
                h.mac
                    .acks()
                    .last()
                    .error!!
                    .details!!["sims"]!!
                    .jsonArray
                    .map { it.jsonPrimitive.int }
            assertEquals(listOf(1, 2), sims)
            assertTrue(h.radio.sent.isEmpty())
        }

    @Test
    fun bodiesUpTo1600CharactersAndShortCodesAreAccepted() =
        runTest {
            val h = harness()
            h.send(body = "ă".repeat(1_600))
            h.send(addresses = "[\"8198\"]")
            h.send(addresses = "[\"+84900000123\"]")
            assertTrue(h.mac.acks().all { it.ok })
            assertEquals(listOf("+84900000123", "8198", "+84900000123"), h.radio.sent.map { it.destination })
        }

    @Test
    fun withoutSubIdTheDefaultSimOrTheOnlySimSendsAndSeveralWithoutDefaultAreRefused() =
        runTest {
            val h = harness()
            h.send()
            h.sims.default = null
            h.sims.active = listOf(SimCard(3, 0, "SIM 1", "VN"))
            h.send()
            h.sims.active = listOf(SimCard(3, 0, "SIM 1", "VN"), SimCard(4, 1, "SIM 2", "VN"))
            h.send()
            assertEquals(listOf(1, 3), h.radio.sent.map { it.subId })
            val refused =
                h.mac
                    .acks()
                    .last()
                    .error!!
            assertEquals(ErrorCode.SMS_SIM_UNAVAILABLE.name, refused.code)
            assertEquals(listOf(3, 4), refused.details!!["sims"]!!.jsonArray.map { it.jsonPrimitive.int })
        }

    @Test
    fun withoutReadPhoneStateOnlyTheDefaultSimCanSend() =
        runTest {
            val h = harness()
            h.sims.active = null
            h.send()
            h.send(subId = 1)
            assertEquals(listOf(1), h.radio.sent.map { it.subId })
            val refused =
                h.mac
                    .acks()
                    .last()
                    .error!!
            assertEquals(ErrorCode.SMS_SIM_UNAVAILABLE.name, refused.code)
            assertEquals(emptyList<Int>(), refused.details!!["sims"]!!.jsonArray.map { it.jsonPrimitive.int })
        }

    @Test
    fun theStatusWaitsForTheSendersNextSessionAndIsRebroadcastThere() =
        runTest {
            val h = harness()
            val localId = h.newLocalId()
            h.send(localId = localId)
            h.disconnect(h.mac)
            h.module.onSent(localId, 0, SentResult.OK)
            h.run()
            assertEquals(listOf(SmsSendStatus.SENDING), h.mac.statuses().map { it.status })
            assertTrue(h.iphone.statuses().isEmpty())

            h.mac.reconnect(h.wall)
            h.connect(h.mac)
            assertEquals(listOf(SmsSendStatus.SENDING, SmsSendStatus.SENT), h.mac.statuses().map { it.status })
        }

    private companion object {
        const val UNKNOWN_RESULT = 42
    }
}
