package app.handlive.android.feature.sms.sync

import app.handlive.android.core.protocol.ProtocolJson
import kotlinx.serialization.KSerializer

/**
 * The size limits of one `ack` page (SMS-01 API 1 logic 7): at most [maxMessages] messages and [maxBytes] of
 * plaintext, so that the envelope stays within 256 KiB after encryption and base64. The first message always fits
 * (a single message always fits in one page), so a sync always makes progress.
 */
class PageBudget(
    private val maxMessages: Int,
    private val maxBytes: Int,
    overheadBytes: Int,
) {
    var messages = 0
        private set
    private var bytes = overheadBytes

    /** Adds [messageCount] messages taking [extraBytes] when they fit; `false` leaves the budget unchanged. */
    fun tryAdd(
        extraBytes: Int,
        messageCount: Int = 1,
    ): Boolean {
        val fits = messages + messageCount <= maxMessages && (messages == 0 || bytes + extraBytes <= maxBytes)
        if (fits) {
            messages += messageCount
            bytes += extraBytes
        }
        return fits
    }

    companion object {
        /** `{"re":"<uuid>","ok":true,"data":…}` around the page. */
        const val ACK_WRAPPER_BYTES = 64

        /** UTF-8 size of one element of a JSON array, with its separating comma. */
        fun <T> sizeOf(
            serializer: KSerializer<T>,
            value: T,
        ): Int = ProtocolJson.encodeToString(serializer, value).toByteArray(Charsets.UTF_8).size + 1
    }
}
