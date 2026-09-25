package app.handlive.android.ui.pairing

import app.handlive.android.core.strings.R
import app.handlive.android.feature.pairing.exchange.PairingFailure
import org.junit.Assert.assertEquals
import org.junit.Test

/** PAIR-01 field 10: E1–E9 keep their own texts; failures without one say "Pairing didn't finish. Try again." */
class PairingFailureMessageTest {
    @Test
    fun failuresWithTheirOwnTextKeepIt() {
        assertEquals(R.string.error_qr_invalid, PairingFailure.QR_INVALID.message)
        assertEquals(R.string.error_pairing_closed, PairingFailure.PAIRING_CLOSED.message)
        assertEquals(R.string.error_pairing_auth_failed, PairingFailure.AUTH_FAILED.message)
        assertEquals(R.string.error_pin_invalid, PairingFailure.PIN_INVALID.message)
        assertEquals(R.string.pairing_limit_reached, PairingFailure.LIMIT_REACHED.message)
        assertEquals(R.string.pairing_camera_denied, PairingFailure.CAMERA_DENIED.message)
    }

    @Test
    fun aLostConnectionOrAnInternalErrorUsesTheGenericText() {
        assertEquals(R.string.error_pairing_failed, PairingFailure.DISCONNECTED.message)
        assertEquals(R.string.error_pairing_failed, PairingFailure.INTERNAL.message)
    }
}
