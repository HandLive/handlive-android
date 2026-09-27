package app.handlive.android.feature.sms.send

import app.handlive.android.core.protocol.sms.SmsSendStatus.DELIVERED
import app.handlive.android.core.protocol.sms.SmsSendStatus.FAILED
import app.handlive.android.core.protocol.sms.SmsSendStatus.SENDING
import app.handlive.android.core.protocol.sms.SmsSendStatus.SENT
import org.junit.Assert.assertEquals
import org.junit.Test

/** The forward-only status rule (SMS-04 API 2 logic 2) and the delivery report status (API 3 logic 5). */
class SendStatusTest {
    @Test
    fun statusesOnlyMoveForward() {
        val allowed =
            listOf(null, SENDING, SENT, DELIVERED, FAILED).associateWith { from ->
                listOf(SENDING, SENT, DELIVERED, FAILED).filter { to -> SendStatus.canMove(from, to) }
            }
        assertEquals(listOf(SENDING, FAILED), allowed[null])
        assertEquals(listOf(SENT, DELIVERED, FAILED), allowed[SENDING])
        assertEquals("a failed report after sent does not lower it", listOf(DELIVERED), allowed[SENT])
        assertEquals(emptyList<String>(), allowed[DELIVERED])
        assertEquals(emptyList<String>(), allowed[FAILED])
    }

    @Test
    fun deliveryReportsOfBothPduFormats() {
        assertEquals(DeliveryReport.DELIVERED, DeliveryReport.of(0x00, threeGpp2 = false))
        assertEquals(DeliveryReport.DELIVERED, DeliveryReport.of(0x02, threeGpp2 = false))
        assertEquals(DeliveryReport.PENDING, DeliveryReport.of(0x20, threeGpp2 = false))
        assertEquals(DeliveryReport.FAILED, DeliveryReport.of(0x40, threeGpp2 = false))
        assertEquals(DeliveryReport.FAILED, DeliveryReport.of(0x65, threeGpp2 = false))
        assertEquals(DeliveryReport.DELIVERED, DeliveryReport.of(0x02 shl 16, threeGpp2 = true))
        assertEquals(DeliveryReport.PENDING, DeliveryReport.of(0x01 shl 16, threeGpp2 = true))
        assertEquals(DeliveryReport.PENDING, DeliveryReport.of((2 shl 24) or (0x04 shl 16), threeGpp2 = true))
        assertEquals(DeliveryReport.FAILED, DeliveryReport.of((3 shl 24) or (0x04 shl 16), threeGpp2 = true))
    }
}
