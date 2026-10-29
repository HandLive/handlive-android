package app.handlive.android.core.transport.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** 0.4.5 "Admission": cận và bộ đếm thất bại của toàn bộ listener Bluetooth, không khoá theo địa chỉ. */
class BluetoothLinkAdmissionTest {
    @Test
    fun rejectsOverTheLinkLimit() {
        val admission = BluetoothLinkAdmission(linkLimit = 2, preauthLimit = 2)
        assertEquals(BluetoothLinkAdmission.AdmissionResult.Admitted, admission.admitNewChannel())
        assertEquals(BluetoothLinkAdmission.AdmissionResult.Admitted, admission.admitNewChannel())
        assertEquals(BluetoothLinkAdmission.AdmissionResult.Rejected, admission.admitNewChannel())
    }

    @Test
    fun rejectsOverThePreauthLimitEvenUnderTheLinkLimit() {
        val admission = BluetoothLinkAdmission(linkLimit = 4, preauthLimit = 1)
        assertEquals(BluetoothLinkAdmission.AdmissionResult.Admitted, admission.admitNewChannel())
        assertEquals(BluetoothLinkAdmission.AdmissionResult.Rejected, admission.admitNewChannel())
        admission.confirmLink() // bước 5 xong: không còn tính vào slot pre-auth
        assertEquals(BluetoothLinkAdmission.AdmissionResult.Admitted, admission.admitNewChannel())
    }

    @Test
    fun closingALinkFreesItsSlots() {
        val admission = BluetoothLinkAdmission(linkLimit = 1, preauthLimit = 1)
        assertEquals(BluetoothLinkAdmission.AdmissionResult.Admitted, admission.admitNewChannel())
        admission.closeLink(wasPreauth = true)
        assertEquals(BluetoothLinkAdmission.AdmissionResult.Admitted, admission.admitNewChannel())
    }

    @Test
    fun preauthTimesOutAfterTheConfiguredDuration() {
        var now = 0L
        val admission = BluetoothLinkAdmission(preauthTimeout = 7.seconds, clock = { now })
        val openedAt = now
        now += 6_999
        assertFalse(admission.isPreauthTimedOut(openedAt))
        now += 1
        assertTrue(admission.isPreauthTimedOut(openedAt))
    }

    @Test
    fun blocksEveryNewChannelAfterAuthFailBlockWithinTheWindow() {
        var now = 0L
        val admission =
            BluetoothLinkAdmission(
                authFailBlock = 5,
                authFailWindow = 1.minutes,
                clock = { now },
                linkLimit = 100,
                preauthLimit = 100,
            )
        repeat(4) { admission.recordAuthFailure() }
        assertEquals(BluetoothLinkAdmission.AdmissionResult.Admitted, admission.admitNewChannel())
        admission.recordAuthFailure() // lần thất bại thứ 5 trong một phút
        assertEquals(BluetoothLinkAdmission.AdmissionResult.Rejected, admission.admitNewChannel())
        now += 60_000
        assertEquals(BluetoothLinkAdmission.AdmissionResult.Admitted, admission.admitNewChannel())
    }

    @Test
    fun oldFailuresOutsideTheWindowDoNotCount() {
        var now = 0L
        val admission = BluetoothLinkAdmission(authFailBlock = 5, authFailWindow = 1.minutes, clock = { now })
        repeat(4) { admission.recordAuthFailure() }
        now += 61_000 // 4 lần thất bại đầu đã quá cũ
        admission.recordAuthFailure()
        assertEquals(BluetoothLinkAdmission.AdmissionResult.Admitted, admission.admitNewChannel())
    }
}
