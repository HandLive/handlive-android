package app.handlive.android.feature.call.log

import app.handlive.android.core.protocol.ErrorCode
import app.handlive.android.core.protocol.ProtocolJson
import app.handlive.android.core.protocol.ack.Ack
import app.handlive.android.core.protocol.call.CallLogEntryData
import app.handlive.android.core.protocol.call.CallLogSyncResponse
import app.handlive.android.core.protocol.call.CallLogType
import app.handlive.android.core.protocol.encoding.Base64Codecs
import app.handlive.android.feature.call.CallConstants
import app.handlive.android.feature.call.testing.BASE_TS
import app.handlive.android.feature.call.testing.CallHarness
import app.handlive.android.feature.connection.capability.AndroidPermissions
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** CALL-04 API 1: `call_event/log_sync` pages, cursors, their resets and errors. */
class CallLogSyncTest {
    @Test
    fun theFirstSyncTakesTheNewest500EntriesOfNinetyDaysInAscendingOrder() =
        runTest {
            val h = CallHarness(this)
            h.connect(h.mac)
            h.callLog.add(type = 1, date = BASE_TS - DAY * 91, id = 100)
            (1..520).forEach { h.callLog.add(type = 2, date = BASE_TS - DAY * 10 + it, id = 1_000L + it) }

            val first = h.sync("""{"limit":200}""")
            assertEquals(200, first.entries.size)
            assertEquals(1_021L, first.entries.first().entryId)
            assertEquals(first.entries.map { it.entryId }.sorted(), first.entries.map { it.entryId })
            assertTrue(first.hasMore)
            assertFalse(first.reset)
            assertEquals(1_220L, cursorId(first.cursor))

            val second = h.sync("""{"cursor":"${first.cursor}","limit":200}""")
            val third = h.sync("""{"cursor":"${second.cursor}","limit":200}""")
            assertEquals(1_221L, second.entries.first().entryId)
            assertEquals(100, third.entries.size)
            assertFalse(third.hasMore)
            assertEquals(1_520L, cursorId(third.cursor))

            val empty = h.sync("""{"cursor":"${third.cursor}","limit":200}""")
            assertTrue(empty.entries.isEmpty())
            assertEquals("an empty page keeps the cursor", third.cursor, empty.cursor)
        }

    @Test
    fun theCursorMatchesTheSpecExamples() =
        runTest {
            val h = CallHarness(this)
            h.connect(h.mac)
            h.callLog.add(
                type = 2,
                date = BASE_TS - 10_400_123,
                number = "0900000456",
                id = 5119,
            ) { it.copy(durationS = 62) }
            h.callLog.add(type = 3, date = BASE_TS + 123, id = 5120)

            val page = h.sync("""{"limit":200}""")
            assertEquals("eyJ2IjoxLCJpZCI6NTEyMH0", page.cursor)
            assertEquals(
                listOf(
                    CallLogEntryData(
                        5119,
                        "+84900000456",
                        "Trần Thị B",
                        CallLogType.OUTGOING,
                        BASE_TS - 10_400_123,
                        62,
                        1,
                    ),
                    CallLogEntryData(5120, "+84900000123", "Nguyễn Văn A", CallLogType.MISSED, BASE_TS + 123, 0, 1),
                ),
                page.entries,
            )
            h.callLog.add(type = 1, date = BASE_TS + 9_600_000, id = 5123) { it.copy(durationS = 125) }
            val next = h.sync("""{"cursor":"eyJ2IjoxLCJpZCI6NTEyMH0","limit":200}""")
            assertEquals(listOf(5123L), next.entries.map { it.entryId })
            assertEquals("eyJ2IjoxLCJpZCI6NTEyM30", next.cursor)
        }

    @Test
    fun anUnusableCursorStartsAgainWithReset() =
        runTest {
            val h = CallHarness(this)
            h.connect(h.mac)
            h.callLog.add(type = 1, date = BASE_TS, id = 7)
            for (cursor in listOf("not-base64!", cursorOf("""{"v":2,"id":1}"""), cursorOf("""{"v":1,"id":8}"""))) {
                val page = h.sync("""{"cursor":"$cursor","limit":200}""")
                assertTrue(page.reset)
                assertEquals(listOf(7L), page.entries.map { it.entryId })
            }
        }

    @Test
    fun anEmptyCallLogGivesCursorZero() =
        runTest {
            val h = CallHarness(this)
            h.connect(h.mac)
            val page = h.sync("""{"limit":200}""")
            assertTrue(page.entries.isEmpty())
            assertEquals(0L, cursorId(page.cursor))
            assertFalse(page.hasMore)

            h.callLog.add(type = 1, date = BASE_TS - DAY * 100, id = 42)
            assertEquals("nothing in 90 days: past the newest entry", 42L, cursorId(h.sync("""{"limit":200}""").cursor))
        }

    @Test
    fun rowsBecomeEntriesAsTheSharedObjectSays() =
        runTest {
            val h = CallHarness(this)
            h.connect(h.mac)
            h.callLog.add(type = 1, date = BASE_TS, number = "0911111111", id = 1) { it.copy(cachedName = "Cached") }
            h.callLog.add(type = 1, date = BASE_TS, number = "0900000123", id = 2) { it.copy(presentation = 2) }
            h.callLog.add(type = 99, date = BASE_TS, id = 3)
            h.callLog.add(type = 6, date = BASE_TS, number = "", id = 4)
            h.callLog.add(type = 4, date = BASE_TS, id = 5) { it.copy(accountId = "sim2") }
            h.callLog.add(type = 5, date = BASE_TS, id = 6) { it.copy(accountId = null) }

            val page = h.sync("""{"limit":3}""")
            assertEquals(listOf(1L, 2L), page.entries.map { it.entryId })
            assertEquals("the unknown type is skipped, the cursor moves past it", 3L, cursorId(page.cursor))
            assertEquals("Cached", page.entries[0].displayName)
            assertNull("restricted: no number", page.entries[1].number)
            assertNull(page.entries[1].displayName)

            val rest = h.sync("""{"cursor":"${page.cursor}","limit":3}""").entries
            assertEquals(listOf(CallLogType.BLOCKED, CallLogType.VOICEMAIL, CallLogType.REJECTED), rest.map { it.type })
            assertNull(rest[0].number)
            assertEquals(2, rest[1].subId)
            assertNull(rest[2].subId)
        }

    @Test
    fun withoutContactsTheCachedNameIsUsed() =
        runTest {
            val h = CallHarness(this)
            h.connect(h.mac)
            h.access.granted -= AndroidPermissions.READ_CONTACTS
            h.callLog.add(type = 1, date = BASE_TS, id = 1) { it.copy(cachedName = "Anh A") }
            h.callLog.add(type = 1, date = BASE_TS, id = 2)
            val entries = h.sync("""{"limit":10}""").entries
            assertEquals(listOf("Anh A", null), entries.map { it.displayName })
        }

    @Test
    fun namesAreLookedUpOncePerNumberInOneSync() =
        runTest {
            val h = CallHarness(this)
            h.connect(h.mac)
            (1..50).forEach { h.callLog.add(type = 3, date = BASE_TS + it, id = it.toLong()) }
            h.sync("""{"limit":500}""")
            assertEquals(1, h.numbers.lookups)
            h.sync("""{"limit":500}""")
            assertEquals("the cache lives for one sync", 2, h.numbers.lookups)
        }

    @Test
    fun aPageClosesAt180KiB() =
        runTest {
            val h = CallHarness(this)
            h.connect(h.mac)
            val longName = "N".repeat(2_000)
            (1..200).forEach {
                h.callLog.add(
                    type = 1,
                    date = BASE_TS + it,
                    number = "0922$it",
                ) { row -> row.copy(cachedName = longName) }
            }
            val page = h.sync("""{"limit":200}""")
            assertTrue(page.hasMore)
            assertTrue(page.entries.size in 2 until 200)
            val bytes = ProtocolJson.encodeToString(CallLogSyncResponse.serializer(), page).toByteArray().size
            assertTrue("$bytes bytes", bytes <= CallConstants.PAGE_MAX_BYTES)
            val next = h.sync("""{"cursor":"${page.cursor}","limit":200}""")
            assertEquals(page.entries.last().entryId + 1, next.entries.first().entryId)
        }

    @Test
    fun refusalsFollowTheCheckOrder() =
        runTest {
            val h = CallHarness(this)
            h.connect(h.mac)
            h.access.granted -= AndroidPermissions.READ_CALL_LOG
            val missing = h.syncError("""{"limit":0}""")
            assertEquals(ErrorCode.PERMISSION_MISSING.name, missing.error?.code)
            assertEquals(JsonPrimitive("android.permission.READ_CALL_LOG"), missing.error?.details?.get("permission"))
            assertEquals(listOf("pair-mac" to "android.permission.READ_CALL_LOG"), h.permissionsAsked)

            h.access.enabled = false
            assertEquals(ErrorCode.FEATURE_DISABLED.name, h.syncError("""{"limit":0}""").error?.code)

            h.access.enabled = true
            h.access.granted += AndroidPermissions.READ_CALL_LOG
            for (data in listOf("""{"limit":0}""", """{"limit":501}""", "{}")) {
                assertEquals(ErrorCode.BAD_REQUEST.name, h.syncError(data).error?.code)
            }
            assertTrue(h.sync("""{"limit":500}""").entries.isEmpty())
        }

    @Test
    fun aProviderFailureIsInternal() =
        runTest {
            val h = CallHarness(this)
            h.connect(h.mac)
            h.callLog.failing = true
            assertEquals(ErrorCode.INTERNAL.name, h.syncError("""{"limit":200}""").error?.code)
        }

    private suspend fun CallHarness.sync(data: String): CallLogSyncResponse {
        val ack = syncAck(data)
        assertTrue("$data: ${ack.error}", ack.ok)
        return CallHarness.decode(CallLogSyncResponse.serializer(), ack.data)
    }

    private suspend fun CallHarness.syncError(data: String): Ack = syncAck(data).also { assertFalse(it.ok) }

    private suspend fun CallHarness.syncAck(data: String): Ack {
        val id = request(mac, "log_sync", data)
        return mac.acks().last { it.re == id }
    }

    private fun cursorId(cursor: String): Long = checkNotNull(CallLogCursor.decode(cursor)).id

    private fun cursorOf(json: String): String = Base64Codecs.encodeB64u(json.toByteArray())

    private companion object {
        const val DAY = 24 * 60 * 60 * 1000L
    }
}
