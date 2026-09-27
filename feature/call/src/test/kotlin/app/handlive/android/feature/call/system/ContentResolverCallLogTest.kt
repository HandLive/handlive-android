package app.handlive.android.feature.call.system

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteQueryBuilder
import android.net.Uri
import android.provider.CallLog
import androidx.test.core.app.ApplicationProvider
import app.handlive.android.feature.call.log.CallLogRow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The CALL-04 queries run on real SQLite: a test provider serves `content://call_log/calls` from a table shaped like
 * the call log's and applies the `limit` query parameter as the platform provider does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ContentResolverCallLogTest {
    private val callLog =
        ContentResolverCallLog(ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver)

    @Before
    fun setUp() {
        database =
            SQLiteDatabase.create(null).apply {
                execSQL(
                    "CREATE TABLE calls (_id INTEGER PRIMARY KEY, number TEXT, presentation INTEGER, name TEXT, " +
                        "type INTEGER, date INTEGER, duration INTEGER, subscription_component_name TEXT, " +
                        "subscription_id TEXT)",
                )
            }
        Robolectric.buildContentProvider(CallLogTable::class.java).create(
            ProviderInfo().also {
                it.authority =
                    "call_log"
            },
        )
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun anEmptyCallLogHasNoLargestId() {
        assertNull(callLog.maxId())
        assertEquals(emptyList<Long>(), callLog.newestIdsSince(0, 500))
    }

    @Test
    fun theLargestIdTheNewestInTheWindowAndPagesInAscendingOrder() {
        call(5119, date = 1_000, type = 2) { put(CallLog.Calls.DURATION, 62) }
        call(5120, date = 3_000, type = 3)
        call(5118, date = 500, type = 1)
        call(5121, date = 2_000, type = 1) {
            putNull(CallLog.Calls.NUMBER)
            put(CallLog.Calls.NUMBER_PRESENTATION, 2)
        }

        assertEquals(5121L, callLog.maxId())
        assertEquals(listOf(5121L, 5120L, 5119L), callLog.newestIdsSince(1_000, 500))
        assertEquals(listOf(5121L, 5120L), callLog.newestIdsSince(1_000, 2))
        assertEquals(listOf(5119L, 5120L), callLog.rows(5119, inclusive = true, limit = 2).map { it.id })
        assertEquals(listOf(5120L, 5121L), callLog.rows(5119, inclusive = false, limit = 10).map { it.id })
        assertEquals(
            CallLogRow(5119, "0900000123", 1, "Cached", 2, 1_000, 62, COMPONENT, "sim1"),
            callLog.rows(5118, inclusive = false, limit = 1).single(),
        )
        val withheld = callLog.rows(5120, inclusive = false, limit = 1).single()
        assertNull(withheld.number)
        assertEquals(2, withheld.presentation)
    }

    /** A row of `calls`; [change] sets the columns that differ from a plain call of `sim1`. */
    private fun call(
        id: Long,
        date: Long,
        type: Int,
        change: ContentValues.() -> Unit = {},
    ) = database.insert(
        "calls",
        null,
        ContentValues().apply {
            put(CallLog.Calls._ID, id)
            put(CallLog.Calls.NUMBER, "0900000123")
            put(CallLog.Calls.NUMBER_PRESENTATION, 1)
            put(CallLog.Calls.CACHED_NAME, "Cached")
            put(CallLog.Calls.TYPE, type)
            put(CallLog.Calls.DATE, date)
            put(CallLog.Calls.DURATION, 0)
            put(CallLog.Calls.PHONE_ACCOUNT_COMPONENT_NAME, COMPONENT)
            put(CallLog.Calls.PHONE_ACCOUNT_ID, "sim1")
            change()
        },
    )

    /** The `calls` table behind `content://call_log/calls`, with the provider's `limit` parameter. */
    class CallLogTable : ContentProvider() {
        override fun onCreate() = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            assertEquals("calls", uri.pathSegments.single())
            @Suppress("UNCHECKED_CAST")
            return SQLiteQueryBuilder()
                .apply { tables = "calls" }
                .query(
                    database,
                    projection as Array<String>?,
                    selection,
                    selectionArgs as Array<String>?,
                    null,
                    null,
                    sortOrder,
                    uri.getQueryParameter(CallLog.Calls.LIMIT_PARAM_KEY),
                )
        }

        override fun getType(uri: Uri): String? = null

        override fun insert(
            uri: Uri,
            values: ContentValues?,
        ): Uri? = null

        override fun delete(
            uri: Uri,
            selection: String?,
            selectionArgs: Array<out String>?,
        ) = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ) = 0
    }

    private companion object {
        const val COMPONENT = "com.android.phone/com.android.services.telephony.TelephonyConnectionService"
        lateinit var database: SQLiteDatabase
    }
}
