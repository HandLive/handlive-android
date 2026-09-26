package app.handlive.android.feature.sms.sync

import app.handlive.android.core.protocol.ProtocolJson
import app.handlive.android.core.protocol.sms.SmsHistoryResponse
import app.handlive.android.core.protocol.sms.SmsMessageData
import app.handlive.android.feature.sms.SmsConstants
import app.handlive.android.feature.sms.provider.SmsObjects
import app.handlive.android.feature.sms.provider.SmsProvider

/**
 * One `sms/history` page (SMS-03 API 1 logic 2–5): messages of a conversation with `date < before_ts`, newest first,
 * at most `limit`, plus the rest of the group sharing the last message's date (the next request uses `date <` and
 * must not skip messages of the same millisecond). The page stops early at [pageMaxBytes] of plaintext, at a date
 * boundary: the date group that did not fit is left out whole. `has_more` comes from reading one more row. No
 * summary, no cursor change.
 */
class SmsHistoryEngine(
    private val provider: SmsProvider,
    private val objects: () -> SmsObjects,
    private val pageMaxBytes: Int = SmsConstants.PAGE_MAX_BYTES,
) {
    /** `false` when the conversation no longer exists on the phone (`SMS_THREAD_NOT_FOUND`, E3). */
    fun exists(threadId: Long): Boolean = provider.conversationExists(threadId)

    fun page(
        threadId: Long,
        beforeTs: Long,
        limit: Int,
    ): SmsHistoryResponse {
        val scope = objects()
        return provider.olderMessages(threadId, beforeTs) { rows ->
            val iterator = rows.mapNotNull { scope.message(it) }.iterator()
            val page = mutableListOf<SmsMessageData>()
            var bytes = OVERHEAD_BYTES
            var hasMore = false
            while (!hasMore && iterator.hasNext()) {
                val message = iterator.next()
                val size = ProtocolJson.encodeToString(SmsMessageData.serializer(), message).toByteArray().size + 1
                val sameDateAsLast = page.isNotEmpty() && page.last().ts == message.ts
                when {
                    // Past the limit, only the group sharing the last date may still join; the next row says "more".
                    page.size >= limit && !sameDateAsLast -> {
                        hasMore = true
                    }

                    // Full: end before the date group of [message], unless the page is that group alone — the
                    // envelope limit (256 KiB) leaves room, and a cut would make the next request skip the rest.
                    page.isNotEmpty() && bytes + size > pageMaxBytes && page.any { it.ts != message.ts } -> {
                        hasMore = true
                        page.removeAll { it.ts == message.ts }
                    }

                    else -> {
                        page += message
                        bytes += size
                    }
                }
            }
            SmsHistoryResponse(page, hasMore)
        }
    }

    private companion object {
        /** `{"re":"<uuid>","ok":true,"data":{"messages":[],"has_more":false}}`. */
        const val OVERHEAD_BYTES = 96
    }
}
