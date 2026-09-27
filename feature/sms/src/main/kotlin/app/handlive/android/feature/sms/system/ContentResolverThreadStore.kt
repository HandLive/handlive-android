package app.handlive.android.feature.sms.system

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import app.handlive.android.feature.sms.provider.ConversationRow
import app.handlive.android.feature.sms.provider.ThreadStore

/**
 * The conversations of the Telephony provider (SMS-01 5.1.5, SMS-03 5.3.5): `conversations?simple=true` (the
 * `threads` table, `recipient_ids` separated by spaces) and `canonical-addresses`. Needs `READ_SMS`.
 */
class ContentResolverThreadStore(
    resolver: ContentResolver,
) : ThreadStore {
    private val queries = ProviderQueries(resolver)

    override fun conversationsNewestFirst(limit: Int): List<ConversationRow> =
        queries
            .query(CONVERSATIONS, COLUMNS, Where.ALL, "$DATE DESC") { queries.readRows(it, limit, ::conversation) }
            .orEmpty()

    override fun conversations(threadIds: Collection<Long>): Map<Long, ConversationRow> =
        ProviderQueries
            .inChunks(threadIds) { ids ->
                queries
                    .query(CONVERSATIONS, COLUMNS, Where.inList(ID, ids), null) {
                        queries.readRows(it, Int.MAX_VALUE, ::conversation)
                    }.orEmpty()
            }.associateBy { it.threadId }

    override fun conversationExists(threadId: Long): Boolean =
        queries.query(CONVERSATIONS, arrayOf(ID), Where("$ID = ?", listOf(threadId)), null) { it.moveToFirst() } == true

    override fun canonicalAddresses(ids: Collection<Long>): Map<Long, String> =
        ProviderQueries
            .inChunks(ids) { chunk ->
                queries
                    .query(CANONICAL_ADDRESSES, arrayOf(ID, ADDRESS), Where.inList(ID, chunk), null) { cursor ->
                        queries.readRows(cursor, Int.MAX_VALUE) { row -> row.getLong(0) to row.getString(1) }
                    }.orEmpty()
            }.mapNotNull { (id, address) -> address?.let { id to it } }
            .toMap()

    private fun conversation(cursor: Cursor): ConversationRow =
        ConversationRow(
            threadId = cursor.getLong(cursor.getColumnIndexOrThrow(ID)),
            date = cursor.getLong(cursor.getColumnIndexOrThrow(DATE)),
            recipientIds =
                cursor
                    .getString(cursor.getColumnIndexOrThrow(RECIPIENT_IDS))
                    .orEmpty()
                    .split(' ')
                    .mapNotNull { it.trim().toLongOrNull() },
        )

    private companion object {
        val CONVERSATIONS: Uri = Uri.parse("content://mms-sms/conversations?simple=true")
        val CANONICAL_ADDRESSES: Uri = Uri.parse("content://mms-sms/canonical-addresses")
        const val ID = "_id"
        const val DATE = "date"
        const val ADDRESS = "address"
        const val RECIPIENT_IDS = "recipient_ids"
        val COLUMNS = arrayOf(ID, DATE, RECIPIENT_IDS)
    }
}
