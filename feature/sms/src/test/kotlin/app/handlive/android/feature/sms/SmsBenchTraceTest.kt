package app.handlive.android.feature.sms

import org.junit.Assert.assertEquals
import org.junit.Test

/** The fields of the phone's SMS rows of the `HLBENCH/1` table (shared/tools/bench/README.md). */
class SmsBenchTraceTest {
    private val lines = mutableListOf<String>()
    private val trace =
        SmsBenchTrace { event, fields ->
            lines +=
                "$event " + fields.joinToString(" ") { "${it.first}=${it.second}" }
        }

    @Test
    fun everyEventCarriesTheFieldsOfItsRow() {
        trace.detected("sms:12847", "inbox", 1_727_150_060_400, 1_727_150_060_456)
        trace.newSent("sms:12847", "dac073e0-123b-8ea5-9dd9-b3bda9cf6037", viaRelay = true)
        trace.sendReceived("0192f3e2", "dac073e0-123b-8ea5-9dd9-b3bda9cf6037")
        trace.sendAckSent("0192f3e2", "dac073e0-123b-8ea5-9dd9-b3bda9cf6037", ok = false, code = "SMS_SIM_UNAVAILABLE")
        trace.radioDone("0192f3e2", failed = false, code = null)
        trace.statusSent("0192f3e2", "dac073e0-123b-8ea5-9dd9-b3bda9cf6037", "sent", null)

        assertEquals(
            listOf(
                "sms_detected msg=sms:12847 box=inbox onchange=1727150060400 provider=1727150060456",
                "sms_new_sent msg=sms:12847 peer=dac073e0 via=relay",
                "sms_send_received local=0192f3e2 peer=dac073e0",
                "sms_send_ack_sent local=0192f3e2 peer=dac073e0 ok=false code=SMS_SIM_UNAVAILABLE",
                "sms_radio_done local=0192f3e2 result=sent",
                "sms_status_sent local=0192f3e2 peer=dac073e0 status=sent",
            ),
            lines,
        )
    }
}
