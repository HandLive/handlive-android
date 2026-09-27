package app.handlive.android.feature.sms.system

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteQueryBuilder
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.handlive.android.feature.sms.provider.ConversationRow
import app.handlive.android.feature.sms.provider.SmsType
import app.handlive.android.feature.sms.provider.UnreadRow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The selections and sort orders of 05-sms run on real SQLite: a test provider serves `content://sms`,
 * `content://mms-sms/conversations` and `canonical-addresses` from tables shaped like the Telephony provider's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ContentResolverSmsProviderTest {
    private val provider =
        ContentResolverSmsProvider(ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver)

    @Before
    fun setUp() {
        database =
            SQLiteDatabase.create(null).apply {
                execSQL(
                    "CREATE TABLE sms (_id INTEGER PRIMARY KEY, thread_id INTEGER, address TEXT, body TEXT, " +
                        "type INTEGER, date INTEGER, date_sent INTEGER, read INTEGER, sub_id INTEGER)",
                )
                execSQL("CREATE TABLE threads (_id INTEGER PRIMARY KEY, date INTEGER, recipient_ids TEXT)")
                execSQL("CREATE TABLE canonical_addresses (_id INTEGER PRIMARY KEY, address TEXT)")
            }
        listOf("sms", "mms-sms").forEach { authority ->
            Robolectric.buildContentProvider(TelephonyTables::class.java).create(
                ProviderInfo().also {
                    it.authority =
                        authority
                },
            )
        }
        address(1, "+84900000123")
        address(2, "0911111111")
        thread(42, date = 500, "1")
        thread(57, date = 900, "1 2")
        sms(1, 42, date = 100, type = SmsType.INBOX, read = 0)
        sms(2, 42, date = 200, type = SmsType.SENT)
        sms(3, 42, date = 200, type = SmsType.DRAFT)
        sms(4, 57, date = 300, type = SmsType.INBOX, read = 0)
        sms(5, 42, date = 400, type = SmsType.OUTBOX)
        sms(6, 57, date = 900, type = SmsType.FAILED)
        database.execSQL("UPDATE sms SET sub_id = NULL WHERE _id = 6")
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun conversationsCanonicalAddressesAndExistence() {
        assertEquals(
            listOf(ConversationRow(57, 900, listOf(1, 2)), ConversationRow(42, 500, listOf(1))),
            provider.conversationsNewestFirst(10),
        )
        assertEquals(listOf(57L), provider.conversationsNewestFirst(1).map { it.threadId })
        assertEquals(setOf(42L), provider.conversations(listOf(42L, 99L)).keys)
        assertEquals(mapOf(2L to "0911111111"), provider.canonicalAddresses(listOf(2L, 7L)))
        assertTrue(provider.conversationExists(42))
        assertFalse(provider.conversationExists(99))
    }

    @Test
    fun firstSyncQueriesSkipDraftsAndStopAtTheSnapshot() {
        assertEquals(6L, provider.maxSmsId())
        assertEquals(listOf(5L, 2L, 1L), provider.threadMessages(42, snap = 6, skip = 0, limit = 10).map { it.id })
        assertEquals(listOf(2L), provider.threadMessages(42, snap = 6, skip = 1, limit = 1).map { it.id })
        assertEquals(listOf(2L, 1L), provider.threadMessages(42, snap = 4, skip = 0, limit = 10).map { it.id })
        assertEquals(emptyList<Long>(), provider.threadMessages(42, snap = 6, skip = 5, limit = 10).map { it.id })
        assertEquals(400L, provider.maxDate(snap = 5, since = null))
        val row = provider.threadMessages(57, snap = 6, skip = 0, limit = 1).single()
        assertEquals(-1, row.subId)
        assertEquals(SmsType.FAILED, row.type)
    }

    @Test
    fun catchUpQueriesFollowTheCursorAndTheLastIdSent() {
        // Cursor {id: 2, t: 250}: 1 is older; 4, 5 and 6 are new; the draft never counts.
        assertEquals(
            listOf(4L, 5L, 6L),
            provider.messagesSince(2, 250, afterId = 0, snap = 6, limit = 10).map { it.id },
        )
        assertEquals(listOf(5L), provider.messagesSince(2, 250, afterId = 4, snap = 5, limit = 10).map { it.id })
        // A row at or below the cursor's _id still counts through a later date (a reused _id).
        assertEquals(listOf(1L, 2L, 4L), provider.messagesSince(4, 50, afterId = 0, snap = 4, limit = 10).map { it.id })
        assertEquals(listOf(2L), provider.messagesSince(4, 150, afterId = 0, snap = 3, limit = 10).map { it.id })
        assertEquals(900L, provider.maxDate(snap = 6, since = 2L to 250L))
    }

    @Test
    fun observerSummaryUnreadAndHistoryQueries() {
        assertEquals(listOf(4L, 5L, 6L), provider.rowsAfter(3).map { it.id })
        assertEquals(setOf(3L, 5L), provider.rowsById(listOf(3L, 5L)).map { it.id }.toSet())
        assertEquals(5L, provider.newestMessage(42)?.id)
        assertEquals(setOf(UnreadRow(42, 100), UnreadRow(57, 300)), provider.unreadInbox().toSet())
        val older = provider.olderMessages(42, beforeTs = 400) { rows -> rows.map { it.id }.toList() }
        assertEquals(listOf(2L, 1L), older)
        val firstOnly = provider.olderMessages(42, beforeTs = 1_000) { rows -> rows.first().id }
        assertEquals(5L, firstOnly)
    }

    private fun address(
        id: Long,
        value: String,
    ) = database.insert(
        "canonical_addresses",
        null,
        ContentValues().apply {
            put("_id", id)
            put("address", value)
        },
    )

    private fun thread(
        id: Long,
        date: Long,
        recipients: String,
    ) = database.insert(
        "threads",
        null,
        ContentValues().apply {
            put("_id", id)
            put("date", date)
            put("recipient_ids", recipients)
        },
    )

    private fun sms(
        id: Long,
        threadId: Long,
        date: Long,
        type: Int,
        read: Int = 1,
    ) = database.insert(
        "sms",
        null,
        ContentValues().apply {
            put("_id", id)
            put("thread_id", threadId)
            put("address", "+84900000123")
            put("body", "tin $id")
            put("type", type)
            put("date", date)
            put("date_sent", 0)
            put("read", read)
            put("sub_id", 1)
        },
    )

    /** The Telephony provider's tables behind `sms` and `mms-sms`, queried as the platform does. */
    class TelephonyTables : ContentProvider() {
        override fun onCreate() = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            val table =
                when {
                    uri.authority == "sms" -> "sms"
                    uri.pathSegments.firstOrNull() == "conversations" -> "threads"
                    else -> "canonical_addresses"
                }
            @Suppress("UNCHECKED_CAST")
            return SQLiteQueryBuilder()
                .apply { tables = table }
                .query(
                    database,
                    projection as Array<String>?,
                    selection,
                    selectionArgs as Array<String>?,
                    null,
                    null,
                    sortOrder,
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
        lateinit var database: SQLiteDatabase
    }
}
