package app.handlive.android.feature.sms.send

import app.handlive.android.core.protocol.ErrorCode
import app.handlive.android.core.protocol.sms.SmsSendStatus

/**
 * The forward-only status of a message sent for a client (SMS-04 API 2 logic 1–2): `sending` → `sent` → `delivered`,
 * or `sending` → `failed`. A duplicate or backward update is ignored; a failed delivery report after `sent` does not
 * lower the status.
 */
object SendStatus {
    private val RANK =
        mapOf(
            SmsSendStatus.SENDING to 0,
            SmsSendStatus.SENT to 1,
            SmsSendStatus.DELIVERED to 2,
        )

    /** Whether a message in [from] (`null` = not sent yet) may move to [to]. */
    fun canMove(
        from: String?,
        to: String,
    ): Boolean =
        when {
            from == null -> to == SmsSendStatus.SENDING || to == SmsSendStatus.FAILED
            from == SmsSendStatus.FAILED || from == SmsSendStatus.DELIVERED -> false
            to == SmsSendStatus.FAILED -> from == SmsSendStatus.SENDING
            else -> RANK.getValue(to) > RANK.getValue(from)
        }
}

/** The "sent" result code of one part → the HandLive code (SMS-04 API 3 table); `null` = the part was sent. */
object SentResult {
    /** `Activity.RESULT_OK`. */
    const val OK = -1
    const val ERROR_GENERIC_FAILURE = 1
    const val ERROR_RADIO_OFF = 2
    const val ERROR_NULL_PDU = 3
    const val ERROR_NO_SERVICE = 4
    const val ERROR_LIMIT_EXCEEDED = 5

    fun errorOf(resultCode: Int): ErrorCode? =
        when (resultCode) {
            OK -> null

            ERROR_NO_SERVICE -> ErrorCode.SMS_NO_SERVICE

            ERROR_RADIO_OFF -> ErrorCode.SMS_RADIO_OFF

            ERROR_LIMIT_EXCEEDED -> ErrorCode.SMS_LIMIT_EXCEEDED

            // RESULT_ERROR_GENERIC_FAILURE, RESULT_ERROR_NULL_PDU and every other code.
            else -> ErrorCode.SMS_GENERIC_FAILURE
        }
}

/**
 * A delivery report (SMS-04 API 3 logic 5), from the `status` of the status-report PDU: 3GPP TP-Status 0x00–0x1F is a
 * completed delivery, 0x20–0x3F still pending, above that failed; a 3GPP2 status carries its error class in bits
 * 24–25 and "delivered" as status code 2.
 */
enum class DeliveryReport {
    DELIVERED,
    PENDING,
    FAILED,
    ;

    companion object {
        private const val GSM_PENDING = 0x20
        private const val GSM_FAILED = 0x40
        private const val CDMA_CLASS_SHIFT = 24
        private const val CDMA_CODE_SHIFT = 16
        private const val CDMA_CLASS_MASK = 0x03
        private const val CDMA_CODE_MASK = 0x3f
        private const val CDMA_NO_ERROR = 0
        private const val CDMA_TEMPORARY = 2
        private const val CDMA_DELIVERED = 2

        fun of(
            status: Int,
            threeGpp2: Boolean,
        ): DeliveryReport =
            if (threeGpp2) {
                val errorClass = (status shr CDMA_CLASS_SHIFT) and CDMA_CLASS_MASK
                val code = (status shr CDMA_CODE_SHIFT) and CDMA_CODE_MASK
                when {
                    errorClass == CDMA_NO_ERROR && code == CDMA_DELIVERED -> DELIVERED
                    errorClass == CDMA_NO_ERROR || errorClass == CDMA_TEMPORARY -> PENDING
                    else -> FAILED
                }
            } else {
                when {
                    status < GSM_PENDING -> DELIVERED
                    status < GSM_FAILED -> PENDING
                    else -> FAILED
                }
            }
    }
}
