package app.handlive.android.core.transport.bluetooth

import app.handlive.android.core.protocol.bluetooth.BtLinkConstants
import app.handlive.android.core.protocol.bluetooth.BtTransportFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.4.5 "Priority": ngắn > dài > bulk ở mỗi ranh giới mảnh; `BT_BULK_INFLIGHT` chỉ chặn dài+bulk, không chặn ngắn. */
class BluetoothLinkSchedulerTest {
    @Test
    fun shortAlwaysPollsBeforeLongAndBulk() {
        val scheduler = BluetoothLinkScheduler()
        scheduler.enqueueBulk(ByteArray(10))
        // "clipboard" vượt BT_FRAGMENT_MAX -> làn dài; "ping" nhỏ -> làn ngắn.
        scheduler.enqueueControlEnvelope("clipboard", ByteArray(BtLinkConstants.FRAGMENT_MAX + 10))
        scheduler.enqueueControlEnvelope("ping", ByteArray(5))
        val first = scheduler.poll()
        assertNotNull(first)
        val frame = BtTransportFrame.decode(first!!)
        assertEquals(BtLinkConstants.CHAN_CONTROL, frame.chan)
        assertEquals(false, frame.long)
        assertEquals(5, frame.data.size)
    }

    @Test
    fun longGoesBeforeBulkWhenNoShortIsPending() {
        val scheduler = BluetoothLinkScheduler()
        scheduler.enqueueBulk(ByteArray(10))
        scheduler.enqueueControlEnvelope("session", ByteArray(20)) // type "session" -> luôn làn dài
        val frame = BtTransportFrame.decode(scheduler.poll()!!)
        assertEquals(BtLinkConstants.CHAN_CONTROL, frame.chan)
        assertTrue(frame.long)
    }

    @Test
    fun bulkInflightCapNeverBlocksAShortEnvelopeStillBoundedByAtMostThatManyRecordsAhead() {
        val scheduler = BluetoothLinkScheduler(bulkInflight = 2)
        scheduler.enqueueBulk(ByteArray(3000)) // 3 mảnh bulk
        scheduler.poll() // mảnh 1 -> inflight = 1
        scheduler.poll() // mảnh 2 -> inflight = 2 (chạm cận)
        assertNull(scheduler.poll()) // cận chặn mảnh 3 trong khi chưa có gì khác để gửi

        scheduler.enqueueControlEnvelope("ping", ByteArray(5))
        // Ngắn không bị cận inflight chặn: tới ngay, sau tối đa BT_BULK_INFLIGHT mảnh dài/bulk đã gửi.
        val next = scheduler.poll()
        assertNotNull(next)
        val frame = BtTransportFrame.decode(next!!)
        assertEquals(5, frame.data.size)
        assertEquals(false, frame.long)

        scheduler.onDrained()
        scheduler.onDrained()
        val third = BtTransportFrame.decode(scheduler.poll()!!)
        assertEquals(BtLinkConstants.CHAN_BULK, third.chan)
    }

    @Test
    fun shortEnvelopeOvertakesA256KibEnvelopeAlreadyQueued() {
        val scheduler = BluetoothLinkScheduler(bulkInflight = 2)
        scheduler.enqueueControlEnvelope("clipboard", ByteArray(256 * 1024))
        // Hai mảnh đầu của tin 256 KiB đã "gửi" (chiếm hết inflight), đúng như một liên kết thật đang bận.
        scheduler.poll()
        scheduler.poll()
        scheduler.enqueueControlEnvelope("ping", ByteArray(5))
        val shortFrame = BtTransportFrame.decode(scheduler.poll()!!)
        assertEquals(false, shortFrame.long)
        assertEquals(5, shortFrame.data.size)
    }

    @Test
    fun sameTypeEnvelopesKeepTheirSendOrder() {
        val scheduler = BluetoothLinkScheduler(bulkInflight = 100)
        // A: vượt cận -> làn dài. B: cùng type, còn nhỏ nhưng A vẫn đang xếp hàng -> cũng làn dài. C: type khác.
        scheduler.enqueueControlEnvelope("clipboard", ByteArray(BtLinkConstants.FRAGMENT_MAX + 10))
        scheduler.enqueueControlEnvelope("clipboard", ByteArray(5))
        scheduler.enqueueControlEnvelope("ping", ByteArray(5))

        val output = generateSequence { scheduler.poll()?.also { scheduler.onDrained() } }.toList()
        val frames = output.map { BtTransportFrame.decode(it) }

        // C (ping, type khác) đi làn ngắn, luôn trước mọi mảnh dài — không ảnh hưởng thứ tự A/B.
        assertEquals(false, frames.first().long)
        assertEquals(5, frames.first().data.size)

        val longFrames = frames.filter { it.long }
        assertTrue("B phải sau mọi mảnh của A", longFrames.last().data.size == 5)
        assertEquals(1, longFrames.count { it.data.size == 5 })
    }

    @Test
    fun sessionTypeAlwaysUsesTheLongLaneRegardlessOfSize() {
        val scheduler = BluetoothLinkScheduler()
        scheduler.enqueueControlEnvelope("session", ByteArray(5))
        val frame = BtTransportFrame.decode(scheduler.poll()!!)
        assertTrue(frame.long)
    }

    @Test
    fun hasPendingReflectsAllThreeQueues() {
        val scheduler = BluetoothLinkScheduler()
        assertEquals(false, scheduler.hasPending())
        scheduler.enqueueBulk(ByteArray(1))
        assertTrue(scheduler.hasPending())
    }
}
