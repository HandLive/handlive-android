package app.handlive.android.feature.sms.module

import app.handlive.android.feature.sms.send.SentResult
import app.handlive.android.feature.sms.testing.BASE_TS
import app.handlive.android.feature.sms.testing.SmsHarness
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where the SMS module reports the `HLBENCH/1` SMS events of the phone (shared/tools/bench/README.md, T2.1). */
class SmsTraceTest {
    private val events = mutableListOf<String>()
    private val trace =
        object : SmsTrace {
            override fun detected(
                messageKey: String,
                box: String,
                onChangeAt: Long,
                providerDate: Long,
            ) {
                events += "detected $messageKey $box $onChangeAt $providerDate"
            }

            override fun newSent(
                messageKey: String,
                peerDeviceId: String,
                viaRelay: Boolean,
            ) {
                events += "new_sent $messageKey $peerDeviceId relay=$viaRelay"
            }

            override fun sendReceived(
                localId: String,
                peerDeviceId: String,
            ) {
                events += "send_received $localId $peerDeviceId"
            }

            override fun sendAckSent(
                localId: String,
                peerDeviceId: String,
                ok: Boolean,
                code: String?,
            ) {
                events += "ack_sent $localId ok=$ok $code"
            }

            override fun radioDone(
                localId: String,
                failed: Boolean,
                code: String?,
            ) {
                events += "radio_done $localId failed=$failed $code"
            }

            override fun statusSent(
                localId: String,
                peerDeviceId: String,
                status: String,
                code: String?,
            ) {
                events += "status_sent $localId $peerDeviceId $status $code"
            }
        }

    @Test
    fun aNewMessageIsTimedFromTheFirstOnChangeOfItsBatch() =
        runTest {
            val h = SmsHarness(this, trace)
            h.provider.conversation(42, "+84900000123")
            h.connect(h.mac)
            h.events.start()
            h.run()
            h.provider.add(42, BASE_TS + 2, read = false)

            h.round(onChangeAt = BASE_TS + 3)

            assertEquals(
                listOf(
                    "detected sms:1 inbox ${BASE_TS + 3} ${BASE_TS + 2}",
                    "new_sent sms:1 pair-mac-device relay=false",
                ),
                events,
            )
        }

    @Test
    fun theRoundAtStartIsNotTimed() =
        runTest {
            val h = SmsHarness(this, trace)
            h.provider.conversation(42, "+84900000123")
            h.provider.add(42, BASE_TS + 1)
            h.connect(h.mac)
            h.round()
            assertTrue(events.none { it.startsWith("detected") })
        }

    @Test
    fun aSendIsTracedFromTheRequestToEveryRadioResultAndStatus() =
        runTest {
            val h = SmsHarness(this, trace)
            h.provider.conversation(42, "+84900000123")
            h.connect(h.mac)
            val sent = h.newLocalId()
            val refused = h.newLocalId()
            h.request(h.mac, "send", """{"local_id":"$sent","addresses":["0900000123"],"body":"Ok"}""")
            h.request(h.mac, "send", """{"local_id":"$refused","addresses":["0900000123"],"body":"   "}""")
            h.module.onSent(sent, 0, SentResult.ERROR_RADIO_OFF)
            h.run()

            assertEquals(
                listOf(
                    "send_received $sent pair-mac-device",
                    "ack_sent $sent ok=true null",
                    "send_received $refused pair-mac-device",
                    "ack_sent $refused ok=false BAD_REQUEST",
                    "radio_done $sent failed=true SMS_RADIO_OFF",
                    "status_sent $sent pair-mac-device failed SMS_RADIO_OFF",
                ),
                events.filterNot { it.startsWith("status_sent $sent pair-mac-device sending") },
            )
        }
}
