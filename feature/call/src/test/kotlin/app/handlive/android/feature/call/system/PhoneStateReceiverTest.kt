package app.handlive.android.feature.call.system

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import app.handlive.android.feature.call.context.BroadcastCopy
import app.handlive.android.feature.call.context.PhoneState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** CALL-01 API 3: each copy of `ACTION_PHONE_STATE_CHANGED` reaches the tracker with the presence of the number key. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhoneStateReceiverTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val copies = mutableListOf<BroadcastCopy>()
    private val receiver = PhoneStateReceiver(context, { 1_727_150_400_123 }) { copies += it }

    @Test
    fun bothCopiesArriveWithOrWithoutTheNumberKey() {
        assertTrue(receiver.register())
        send(TelephonyManager.EXTRA_STATE_RINGING, number = "0900000123")
        send(TelephonyManager.EXTRA_STATE_RINGING, number = null)
        send(TelephonyManager.EXTRA_STATE_OFFHOOK, number = "")
        send("SOMETHING_ELSE", number = null)

        assertEquals(
            listOf(
                BroadcastCopy(PhoneState.RINGING, true, "0900000123", 1_727_150_400_123),
                BroadcastCopy(PhoneState.RINGING, false, null, 1_727_150_400_123),
                BroadcastCopy(PhoneState.OFFHOOK, true, "", 1_727_150_400_123),
            ),
            copies,
        )
    }

    @Test
    fun nothingArrivesOnceUnregistered() {
        receiver.register()
        receiver.unregister()
        send(TelephonyManager.EXTRA_STATE_IDLE, number = null)
        assertTrue(copies.isEmpty())
    }

    @Suppress("DEPRECATION")
    private fun send(
        state: String,
        number: String?,
    ) {
        val intent =
            Intent(TelephonyManager.ACTION_PHONE_STATE_CHANGED).putExtra(TelephonyManager.EXTRA_STATE, state)
        number?.let { intent.putExtra(TelephonyManager.EXTRA_INCOMING_NUMBER, it) }
        context.sendBroadcast(intent)
        shadowOf(Looper.getMainLooper()).idle()
    }
}
