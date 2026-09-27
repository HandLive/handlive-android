package app.handlive.android.feature.sms.system

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.Telephony
import app.handlive.android.feature.sms.provider.SmsRow

/** A `WHERE` clause with its arguments, as `ContentResolver.query` takes them. */
class Where(
    val clause: String?,
    val args: List<Any> = emptyList(),
) {
    fun argsOrNull(): Array<String>? = args.takeIf { it.isNotEmpty() }?.map { it.toString() }?.toTypedArray()

    companion object {
        val ALL = Where(null)

        /** `_id IN (?, ?, …)` over [column]. */
        fun inList(
            column: String,
            ids: List<Long>,
        ) = Where("$column IN (${ids.joinToString(", ") { "?" }})", ids)
    }
}

/**
 * Cursor plumbing shared by the Telephony queries: every query reads and closes its cursor at once, and stops reading
 * at the number of rows it needs (05-sms: `LIMIT` in `sortOrder` is not relied on).
 */
internal class ProviderQueries(
    private val resolver: ContentResolver,
) {
    /** Runs [read] on the cursor and closes it; a `null` cursor (provider unavailable) reads as nothing. */
    fun <T> query(
        uri: Uri,
        projection: Array<String>,
        where: Where,
        sortOrder: String?,
        read: (Cursor) -> T,
    ): T? = resolver.query(uri, projection, where.clause, where.argsOrNull(), sortOrder)?.use(read)

    /** At most [limit] rows from the cursor's current position. */
    fun <T> readRows(
        cursor: Cursor,
        limit: Int,
        convert: (Cursor) -> T,
    ): List<T> {
        val rows = mutableListOf<T>()
        while (rows.size < limit && cursor.moveToNext()) rows += convert(cursor)
        return rows
    }

    companion object {
        /** SQLite allows 999 host parameters on old versions; stay well below. */
        private const val CHUNK = 500

        fun <T> inChunks(
            ids: Collection<Long>,
            read: (List<Long>) -> List<T>,
        ): List<T> = ids.distinct().chunked(CHUNK).flatMap(read)
    }
}

/**
 * The columns every SMS query of group 5 reads (5.1.5): `_id`, `thread_id`, `address`, `body`, `type`, `date`,
 * `date_sent`, `read`, `sub_id`. `date_sent` and `sub_id` may be missing on some phones (read as 0 and -1).
 */
internal object SmsColumns {
    val SMS: Uri = Telephony.Sms.CONTENT_URI
    const val ID = "_id"
    const val THREAD_ID = "thread_id"
    const val ADDRESS = "address"
    const val BODY = "body"
    const val TYPE = "type"
    const val DATE = "date"
    const val DATE_SENT = "date_sent"
    const val READ = "read"
    const val SUB_ID = "sub_id"
    private const val NO_SUB_ID = -1
    val ALL = arrayOf(ID, THREAD_ID, ADDRESS, BODY, TYPE, DATE, DATE_SENT, READ, SUB_ID)

    /** At most [limit] rows from the cursor's current position. */
    fun read(
        cursor: Cursor,
        limit: Int,
    ): List<SmsRow> {
        val indexes = Indexes(cursor)
        val rows = mutableListOf<SmsRow>()
        while (rows.size < limit && cursor.moveToNext()) rows += indexes.row(cursor)
        return rows
    }

    /** The rows read one by one while the consumer asks for more; the caller closes the cursor. */
    fun sequence(cursor: Cursor?): Sequence<SmsRow> {
        if (cursor == null) return emptySequence()
        val indexes = Indexes(cursor)
        return generateSequence { if (cursor.moveToNext()) indexes.row(cursor) else null }
    }

    private class Indexes(
        cursor: Cursor,
    ) {
        private val id = cursor.getColumnIndexOrThrow(ID)
        private val threadId = cursor.getColumnIndexOrThrow(THREAD_ID)
        private val address = cursor.getColumnIndexOrThrow(ADDRESS)
        private val body = cursor.getColumnIndexOrThrow(BODY)
        private val type = cursor.getColumnIndexOrThrow(TYPE)
        private val date = cursor.getColumnIndexOrThrow(DATE)
        private val dateSent = cursor.getColumnIndex(DATE_SENT)
        private val read = cursor.getColumnIndexOrThrow(READ)
        private val subId = cursor.getColumnIndex(SUB_ID)

        fun row(cursor: Cursor) =
            SmsRow(
                id = cursor.getLong(id),
                threadId = cursor.getLong(threadId),
                address = cursor.getString(address),
                body = cursor.getString(body),
                type = cursor.getInt(type),
                date = cursor.getLong(date),
                dateSent = if (dateSent >= 0) cursor.getLong(dateSent) else 0,
                read = cursor.getInt(read) == 1,
                subId = if (subId >= 0 && !cursor.isNull(subId)) cursor.getInt(subId) else NO_SUB_ID,
            )
    }
}
