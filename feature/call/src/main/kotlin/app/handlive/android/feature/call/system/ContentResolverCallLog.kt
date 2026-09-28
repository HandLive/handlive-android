package app.handlive.android.feature.call.system

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.CallLog
import app.handlive.android.feature.call.log.CallLogProvider
import app.handlive.android.feature.call.log.CallLogRow

/**
 * The CALL-04 queries on `CallLog.Calls.CONTENT_URI` (needs `READ_CALL_LOG`). The row limit goes in the
 * `LIMIT_PARAM_KEY` query parameter, since the provider refuses `LIMIT` inside the sort order. A provider that is not
 * there (a `null` cursor) is an error like any other (E6).
 */
class ContentResolverCallLog(
    private val resolver: ContentResolver,
) : CallLogProvider {
    override fun maxId(): Long? =
        query(limited(1), ID_ONLY, Where.ALL, ID_DESC) { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        }

    override fun newestIdsSince(
        since: Long,
        limit: Int,
    ): List<Long> =
        query(limited(limit), ID_ONLY, Where("${CallLog.Calls.DATE} >= ?", since), ID_DESC) {
            buildList { while (it.moveToNext()) add(it.getLong(0)) }
        }

    override fun rows(
        afterId: Long,
        inclusive: Boolean,
        limit: Int,
    ): List<CallLogRow> {
        val where = Where(if (inclusive) "${CallLog.Calls._ID} >= ?" else "${CallLog.Calls._ID} > ?", afterId)
        return query(limited(limit), PROJECTION, where, ID_ASC) { cursor ->
            buildList { while (cursor.moveToNext()) add(row(cursor)) }
        }
    }

    private fun row(cursor: Cursor) =
        CallLogRow(
            id = cursor.getLong(COLUMN_ID),
            number = cursor.getString(COLUMN_NUMBER),
            presentation = cursor.getInt(COLUMN_PRESENTATION),
            cachedName = cursor.getString(COLUMN_CACHED_NAME),
            type = cursor.getInt(COLUMN_TYPE),
            date = cursor.getLong(COLUMN_DATE),
            durationS = cursor.getLong(COLUMN_DURATION),
            accountComponent = cursor.getString(COLUMN_ACCOUNT_COMPONENT),
            accountId = cursor.getString(COLUMN_ACCOUNT_ID),
        )

    private fun <T> query(
        uri: Uri,
        projection: Array<String>,
        where: Where,
        sortOrder: String,
        read: (Cursor) -> T,
    ): T =
        checkNotNull(resolver.query(uri, projection, where.selection, where.args, sortOrder)) { "call log unavailable" }
            .use(read)

    /** A selection with its one numeric argument. */
    private class Where(
        val selection: String?,
        value: Long?,
    ) {
        val args: Array<String>? = value?.let { arrayOf("$it") }

        companion object {
            val ALL = Where(null, null)
        }
    }

    private fun limited(limit: Int): Uri =
        CallLog.Calls.CONTENT_URI
            .buildUpon()
            .appendQueryParameter(CallLog.Calls.LIMIT_PARAM_KEY, "$limit")
            .build()

    private companion object {
        const val ID_DESC = "${CallLog.Calls._ID} DESC"
        const val ID_ASC = "${CallLog.Calls._ID} ASC"
        val ID_ONLY = arrayOf(CallLog.Calls._ID)

        /** Positions in [PROJECTION]. */
        const val COLUMN_ID = 0
        const val COLUMN_NUMBER = 1
        const val COLUMN_PRESENTATION = 2
        const val COLUMN_CACHED_NAME = 3
        const val COLUMN_TYPE = 4
        const val COLUMN_DATE = 5
        const val COLUMN_DURATION = 6
        const val COLUMN_ACCOUNT_COMPONENT = 7
        const val COLUMN_ACCOUNT_ID = 8

        val PROJECTION =
            arrayOf(
                CallLog.Calls._ID,
                CallLog.Calls.NUMBER,
                CallLog.Calls.NUMBER_PRESENTATION,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION,
                CallLog.Calls.PHONE_ACCOUNT_COMPONENT_NAME,
                CallLog.Calls.PHONE_ACCOUNT_ID,
            )
    }
}
