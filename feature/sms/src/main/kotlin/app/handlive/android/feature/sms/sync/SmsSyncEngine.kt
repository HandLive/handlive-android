package app.handlive.android.feature.sms.sync

import app.handlive.android.core.protocol.ProtocolJson
import app.handlive.android.core.protocol.sms.SmsMessageData
import app.handlive.android.core.protocol.sms.SmsSyncResponse
import app.handlive.android.core.protocol.sms.SmsThreadData
import app.handlive.android.core.protocol.sms.SmsUnreadEntry
import app.handlive.android.feature.sms.SmsConstants
import app.handlive.android.feature.sms.provider.ConversationRow
import app.handlive.android.feature.sms.provider.SmsObjects
import app.handlive.android.feature.sms.provider.SmsProvider

/** A checked `sms/sync` request (SMS-01 API 1): limits in range, `cursor` and `page_token` decoded. */
class SyncPageRequest(
    val threadLimit: Int,
    val perThreadLimit: Int,
    val cursor: SyncCursor?,
    val token: PageToken?,
)

/**
 * One page of SMS-01 (step 5, API 1 logic 2–8), stateless between pages: the first sync takes the newest
 * conversations and their newest messages up to the snapshot mark `snap`; a catch-up sync takes every message after
 * the cursor in ascending `_id`. A page stops at [pageMax] messages or [pageMaxBytes] of plaintext; the last page
 * carries `unread`. Reads the provider on the caller's thread (the module calls it on a background dispatcher).
 */
class SmsSyncEngine(
    private val provider: SmsProvider,
    private val objects: () -> SmsObjects,
    private val pageMax: Int = SmsConstants.PAGE_MAX,
    private val pageMaxBytes: Int = SmsConstants.PAGE_MAX_BYTES,
) {
    fun page(request: SyncPageRequest): SmsSyncResponse {
        val scope = objects()
        return if (request.cursor == null) firstSync(request, scope) else catchUp(request, request.cursor, scope)
    }

    private fun firstSync(
        request: SyncPageRequest,
        scope: SmsObjects,
    ): SmsSyncResponse {
        val token = request.token
        val snap = token?.m ?: provider.maxSmsId() ?: 0
        val listed = if (token == null) provider.conversationsNewestFirst(request.threadLimit) else null
        listed?.let(scope::prefetchAddresses)
        val walk =
            ThreadWalk(
                remaining = ArrayDeque(token?.th ?: listed.orEmpty().map { it.threadId }),
                offset = token?.o ?: 0,
                conversations = listed?.associateBy { it.threadId }.orEmpty(),
                snap = snap,
                perThreadLimit = request.perThreadLimit,
            )
        val cursor = SyncTokens.encode(SyncCursor(snap, provider.maxDate(snap, null) ?: 0))
        val unread = scope.unreadEntries
        val maxToken = SyncTokens.encode(PageToken.firstSync(snap, walk.remaining.toList(), request.perThreadLimit))
        val page = PageContent()
        fill(page, walk, PageBudget(pageMax, pageMaxBytes, overhead(cursor, maxToken, unread)), scope)
        val hasMore = walk.remaining.isNotEmpty()
        return page.response(
            cursor = cursor,
            pageToken =
                if (hasMore) {
                    SyncTokens.encode(
                        PageToken.firstSync(snap, walk.remaining.toList(), walk.offset),
                    )
                } else {
                    null
                },
            unread = if (hasMore) null else unread,
        )
    }

    /** Where a first sync stands: the conversations left, the first one partly sent ([offset] messages). */
    private class ThreadWalk(
        val remaining: ArrayDeque<Long>,
        var offset: Int,
        val conversations: Map<Long, ConversationRow>,
        val snap: Long,
        val perThreadLimit: Int,
    )

    /** Adds conversations to [page] until the budget is full; a conversation cut in two stays first in [walk]. */
    private fun fill(
        page: PageContent,
        walk: ThreadWalk,
        budget: PageBudget,
        scope: SmsObjects,
    ) {
        while (walk.remaining.isNotEmpty()) {
            val threadId = walk.remaining.first()
            val wanted = walk.perThreadLimit - walk.offset
            val messages =
                if (wanted > 0) {
                    provider.threadMessages(threadId, walk.snap, walk.offset, wanted).mapNotNull { scope.message(it) }
                } else {
                    emptyList()
                }
            val header =
                if (walk.offset == 0 &&
                    messages.isNotEmpty()
                ) {
                    scope.thread(threadId, walk.conversations[threadId])
                } else {
                    null
                }
            val added = page.addRows(messages, header, budget)
            if (added < messages.size) {
                walk.offset += added
                return
            }
            // The conversation is done: its messages reached `per_thread_limit`, or it has no more (MMS only: none).
            walk.remaining.removeFirst()
            walk.offset = 0
        }
    }

    private fun catchUp(
        request: SyncPageRequest,
        cursor: SyncCursor,
        scope: SmsObjects,
    ): SmsSyncResponse {
        val token = request.token
        val snap = token?.m ?: provider.maxSmsId() ?: 0
        val since = cursor.id to cursor.t
        val newCursor =
            SyncTokens.encode(
                SyncCursor(maxOf(cursor.id, snap), maxOf(cursor.t, provider.maxDate(snap, since) ?: cursor.t)),
            )
        val unread = scope.unreadEntries
        val maxToken = SyncTokens.encode(PageToken.catchUp(snap, Long.MAX_VALUE))
        val budget = PageBudget(pageMax, pageMaxBytes, overhead(newCursor, maxToken, unread))
        // One row past the page tells whether another page follows.
        val rows = provider.messagesSince(cursor.id, cursor.t, token?.a ?: 0, snap, pageMax + 1)
        val page = PageContent()
        val summarized = HashSet<Long>()
        var consumed = 0
        var lastId = token?.a ?: 0
        for (row in rows) {
            val message = scope.message(row)
            if (message != null) {
                // Every conversation with messages in the page gets its summary, with its first message there.
                val header = if (summarized.add(row.threadId)) scope.thread(row.threadId) else null
                if (page.addRows(listOf(message), header, budget) == 0) break
            }
            consumed++
            lastId = row.id
        }
        val hasMore = consumed < rows.size
        return page.response(
            cursor = newCursor,
            pageToken = if (hasMore) SyncTokens.encode(PageToken.catchUp(snap, lastId)) else null,
            unread = if (hasMore) null else unread,
        )
    }

    /** Everything around the lists: the ack wrapper, the cursor, the largest possible token and `unread`. */
    private fun overhead(
        cursor: String,
        maxToken: String,
        unread: List<SmsUnreadEntry>,
    ): Int {
        val empty = SmsSyncResponse(emptyList(), emptyList(), cursor, maxToken, hasMore = true, unread = unread)
        return ProtocolJson.encodeToString(SmsSyncResponse.serializer(), empty).toByteArray().size +
            PageBudget.ACK_WRAPPER_BYTES
    }

    /** The threads and messages of one page. */
    private class PageContent {
        val threads = mutableListOf<SmsThreadData>()
        val messages = mutableListOf<SmsMessageData>()

        /**
         * Adds [rows] in order while they fit; the first one goes together with its conversation's [header] (the
         * `thread` object travels with the page holding the conversation's first message). Returns how many fit.
         */
        fun addRows(
            rows: List<SmsMessageData>,
            header: SmsThreadData?,
            budget: PageBudget,
        ): Int {
            var added = 0
            for (message in rows) {
                val headerBytes = if (added == 0 && header != null) PageBudget.sizeOf(THREAD, header) else 0
                if (!budget.tryAdd(headerBytes + PageBudget.sizeOf(MESSAGE, message))) break
                if (added == 0 && header != null) threads += header
                messages += message
                added++
            }
            return added
        }

        fun response(
            cursor: String,
            pageToken: String?,
            unread: List<SmsUnreadEntry>?,
        ) = SmsSyncResponse(threads, messages, cursor, pageToken, hasMore = pageToken != null, unread = unread)
    }

    private companion object {
        val THREAD = SmsThreadData.serializer()
        val MESSAGE = SmsMessageData.serializer()
    }
}
