package app.handlive.android.feature.sms.system

import android.content.ContentResolver
import android.database.Cursor
import app.handlive.android.feature.sms.provider.SmsProvider
import app.handlive.android.feature.sms.provider.SmsRow
import app.handlive.android.feature.sms.provider.SmsStore
import app.handlive.android.feature.sms.provider.ThreadStore
import app.handlive.android.feature.sms.provider.UnreadRow
import app.handlive.android.feature.sms.system.SmsColumns.DATE
import app.handlive.android.feature.sms.system.SmsColumns.ID
import app.handlive.android.feature.sms.system.SmsColumns.READ
import app.handlive.android.feature.sms.system.SmsColumns.SMS
import app.handlive.android.feature.sms.system.SmsColumns.THREAD_ID
import app.handlive.android.feature.sms.system.SmsColumns.TYPE

/**
 * [SmsProvider] on the Telephony provider with the queries of 05-sms (SMS-01 5.1.5, SMS-02 5.2.5, SMS-03 5.3.5,
 * SMS-05 5.5.5). Needs `READ_SMS`; HandLive is not the default SMS app, so it only reads (0.9.2).
 */
class ContentResolverSmsProvider(
    resolver: ContentResolver,
) : SmsProvider,
    SmsStore by ContentResolverSmsStore(resolver),
    ThreadStore by ContentResolverThreadStore(resolver)

/** The SMS rows of `content://sms`: drafts (`type = 3`) never leave the phone (`type IN (1, 2, 4, 5, 6)`). */
class ContentResolverSmsStore(
    private val resolver: ContentResolver,
) : SmsStore {
    private val queries = ProviderQueries(resolver)

    override fun maxSmsId(): Long? =
        queries.query(SMS, arrayOf(ID), Where.ALL, "$ID DESC") { cursor ->
            cursor.takeIf { it.moveToFirst() }?.getLong(0)
        }

    override fun threadMessages(
        threadId: Long,
        snap: Long,
        skip: Int,
        limit: Int,
    ): List<SmsRow> =
        rows(Where("$THREAD_ID = ? AND $ID <= ? AND $SYNCED_TYPES", listOf(threadId, snap)), NEWEST_FIRST) { cursor ->
            if (skip == 0 || cursor.moveToPosition(skip - 1)) SmsColumns.read(cursor, limit) else emptyList()
        }

    override fun messagesSince(
        cursorId: Long,
        cursorDate: Long,
        afterId: Long,
        snap: Long,
        limit: Int,
    ): List<SmsRow> {
        val where =
            Where(
                "($ID > ? OR $DATE > ?) AND $ID > ? AND $ID <= ? AND $SYNCED_TYPES",
                listOf(cursorId, cursorDate, afterId, snap),
            )
        return rows(where, "$ID ASC") { SmsColumns.read(it, limit) }
    }

    override fun maxDate(
        snap: Long,
        since: Pair<Long, Long>?,
    ): Long? {
        val where =
            if (since == null) {
                Where("$ID <= ? AND $SYNCED_TYPES", listOf(snap))
            } else {
                Where("$ID <= ? AND $SYNCED_TYPES AND ($ID > ? OR $DATE > ?)", listOf(snap, since.first, since.second))
            }
        return queries.query(SMS, arrayOf(DATE), where, "$DATE DESC") { cursor ->
            cursor.takeIf { it.moveToFirst() }?.getLong(0)
        }
    }

    override fun newestMessage(threadId: Long): SmsRow? =
        rows(Where("$THREAD_ID = ? AND $SYNCED_TYPES", listOf(threadId)), NEWEST_FIRST) { SmsColumns.read(it, 1) }
            .firstOrNull()

    override fun unreadInbox(): List<UnreadRow> =
        queries
            .query(SMS, arrayOf(THREAD_ID, DATE), Where("$TYPE = 1 AND $READ = 0"), null) { cursor ->
                queries.readRows(cursor, Int.MAX_VALUE) { UnreadRow(it.getLong(0), it.getLong(1)) }
            }.orEmpty()

    override fun <T> olderMessages(
        threadId: Long,
        beforeTs: Long,
        read: (Sequence<SmsRow>) -> T,
    ): T {
        val where = Where("$THREAD_ID = ? AND $DATE < ? AND $SYNCED_TYPES", listOf(threadId, beforeTs))
        return resolver.query(SMS, SmsColumns.ALL, where.clause, where.argsOrNull(), NEWEST_FIRST).use { cursor ->
            read(SmsColumns.sequence(cursor))
        }
    }

    override fun rowsAfter(afterId: Long): List<SmsRow> =
        rows(Where("$ID > ?", listOf(afterId)), "$ID ASC") { SmsColumns.read(it, Int.MAX_VALUE) }

    override fun rowsById(ids: Collection<Long>): List<SmsRow> =
        ProviderQueries.inChunks(ids) { chunk ->
            rows(Where.inList(ID, chunk), null) { SmsColumns.read(it, Int.MAX_VALUE) }
        }

    private fun rows(
        where: Where,
        sortOrder: String?,
        read: (Cursor) -> List<SmsRow>,
    ): List<SmsRow> = queries.query(SMS, SmsColumns.ALL, where, sortOrder, read).orEmpty()

    private companion object {
        const val SYNCED_TYPES = "$TYPE IN (1, 2, 4, 5, 6)"
        const val NEWEST_FIRST = "$DATE DESC, $ID DESC"
    }
}
