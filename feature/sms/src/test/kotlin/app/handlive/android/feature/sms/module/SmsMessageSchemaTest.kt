package app.handlive.android.feature.sms.module

import app.handlive.android.core.protocol.ack.Ack
import app.handlive.android.core.protocol.envelope.PlaintextCodec
import app.handlive.android.core.protocol.sms.SmsOp
import app.handlive.android.core.protocol.testing.JsonSchemaValidation
import app.handlive.android.feature.sms.provider.SmsType
import app.handlive.android.feature.sms.send.DeliveryReport
import app.handlive.android.feature.sms.send.SentResult
import app.handlive.android.feature.sms.testing.BASE_TS
import app.handlive.android.feature.sms.testing.SmsHarness
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Every `sms` message the phone emits passes the strict sender-side schemas of shared/schemas (S2.2). */
class SmsMessageSchemaTest {
    @Test
    fun acksAndEventsOfAWholeSessionAreValid() =
        runTest {
            val h = SmsHarness(this)
            h.provider.conversation(42, "0900000123")
            h.provider.conversation(57, "VIETTEL")
            (1..3).forEach { h.provider.add(42, BASE_TS + it, read = it == 1) }
            h.provider.add(57, BASE_TS + 10, read = false)
            h.connect(h.mac)
            h.events.start()
            h.run()

            h.request(h.mac, "sync", """{"thread_limit":200,"per_thread_limit":1}""")
            h.request(h.mac, "sync", """{"thread_limit":200,"per_thread_limit":50}""")
            h.request(h.mac, "history", """{"thread_id":42,"before_ts":${BASE_TS + 3},"limit":1}""")
            val localId = h.newLocalId()
            h.request(
                h.mac,
                "send",
                """{"local_id":"$localId","thread_id":42,"addresses":["0900000123"],"body":"Ok"}""",
            )
            h.module.onSent(localId, 0, SentResult.OK)
            h.module.onDelivered(localId, 0, DeliveryReport.DELIVERED)
            val failed = h.newLocalId()
            h.request(h.mac, "send", """{"local_id":"$failed","addresses":["0900000123"],"body":"x"}""")
            h.module.onSent(failed, 0, SentResult.ERROR_RADIO_OFF)
            h.run()
            h.provider.add(42, BASE_TS + 20, type = SmsType.SENT, body = "Ok")
            h.provider.add(57, BASE_TS + 21, read = false)
            h.round()
            h.provider.update(4) { it.copy(read = true) }
            h.round()

            val acks =
                h.mac.sent
                    .filter { it.type.wire == "ack" }
                    .map { String(it.plaintext) }
            val refs = listOf("sms-sync", "sms-sync", "sms-history", "sms-send", "sms-send")
            assertEquals(refs.size, acks.size)
            acks.zip(refs).forEach { (ack, ref) ->
                JsonSchemaValidation.assertValid("$ref.schema.json#/\$defs/ack", ack)
            }
            val events = h.mac.sent.filter { it.type.wire == "sms" }
            events.forEach { event ->
                val op = PlaintextCodec.decodePayload(event.plaintext).op
                JsonSchemaValidation.assertValid("sms-$op.schema.json", String(event.plaintext))
            }
            val ops = events.map { PlaintextCodec.decodePayload(it.plaintext).op }.toSet()
            assertEquals(setOf(SmsOp.NEW, SmsOp.STATUS, SmsOp.READ_CHANGED), ops)
        }

    @Test
    fun errorAcksAreValid() {
        listOf(
            SmsError.featureDisabled(),
            SmsError.permissionMissing("READ_SMS"),
            SmsError.cursorInvalid(SmsError.REASON_PAGE_TOKEN),
            SmsError.simUnavailable(listOf(1, 2)),
            SmsError.threadNotFound(),
            SmsError.internal(),
        ).forEach { error ->
            val ack: Ack = error.ack("0192f3e2-5c6d-7e7f-8a9b-0c1d2e3f4a5b")
            JsonSchemaValidation.assertValid("ack.schema.json", String(PlaintextCodec.encodeAck(ack)))
        }
    }
}
