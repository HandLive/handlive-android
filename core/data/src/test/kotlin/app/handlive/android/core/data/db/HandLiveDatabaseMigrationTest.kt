package app.handlive.android.core.data.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Older `handlive.db` files open as the current version with their rows intact (Room auto-migrations). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HandLiveDatabaseMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun versionOneGainsTheSmsAndPushTablesAndKeepsItsPairs() =
        runTest {
            val file = context.getDatabasePath("migration-test.db").apply { parentFile?.mkdirs() }
            file.delete()
            createVersion(file, SCHEMA_V1, version = 1)

            val database = Room.databaseBuilder(context, HandLiveDatabase::class.java, file.absolutePath).build()
            try {
                val pair = database.pairedDevices().find(PAIR_ID)
                assertEquals("MacBook của Lan", pair?.peerName)
                assertEquals(false, pair?.relayRegistered)
                assertNull(database.smsObserverState().lastSmsId())
                assertEquals(emptyList<PushOutboxEntity>(), database.pushOutbox().due(Long.MAX_VALUE - 1, LIMIT))
            } finally {
                database.close()
            }
        }

    @Test
    fun versionTwoGainsThePushReasonAndKeepsItsQueuedSmsPushes() =
        runTest {
            val file = context.getDatabasePath("migration-test-2.db").apply { parentFile?.mkdirs() }
            file.delete()
            createVersion(file, SCHEMA_V2, version = 2) { db ->
                db.insert(
                    "push_outbox",
                    null,
                    ContentValues().apply {
                        put("id", "0192f3e4-7a10-7b20-8c30-9d40ae50bf60")
                        put("pair_id", PAIR_ID)
                        put("kind", "alert")
                        put("body_b64", "e30=")
                        put("collapse_key", "sms:12847")
                        put("attempts", 2)
                        put("next_attempt_at", 1_000L)
                        put("expires_at", 90_000L)
                    },
                )
            }

            val database = Room.databaseBuilder(context, HandLiveDatabase::class.java, file.absolutePath).build()
            try {
                val queued = database.pushOutbox().due(now = 2_000, limit = LIMIT).single()
                assertEquals("sms_new", queued.reason)
                assertEquals("sms:12847", queued.collapseKey)
                assertEquals(2, queued.attempts)
            } finally {
                database.close()
            }
        }

    /** The tables of the exported schema [schemaPath] with one pair, as an install of that [version] left them. */
    private fun createVersion(
        file: File,
        schemaPath: String,
        version: Int,
        rows: (SQLiteDatabase) -> Unit = {},
    ) {
        val schema =
            Json
                .parseToJsonElement(File(schemaPath).readText())
                .jsonObject
                .getValue("database")
                .jsonObject
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            for (entity in schema.getValue("entities").jsonArray.map { it.jsonObject }) {
                val table = entity.getValue("tableName").jsonPrimitive.content
                db.execSQL(
                    entity
                        .getValue("createSql")
                        .jsonPrimitive.content
                        .replace("\${TABLE_NAME}", table),
                )
                entity["indices"]?.jsonArray?.forEach { index ->
                    db.execSQL(
                        index.jsonObject
                            .getValue("createSql")
                            .jsonPrimitive.content
                            .replace("\${TABLE_NAME}", table),
                    )
                }
            }
            db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            db.execSQL(
                "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, ?)",
                arrayOf(schema.getValue("identityHash").jsonPrimitive.content),
            )
            db.insert("paired_device", null, pairRow())
            rows(db)
            db.version = version
        } finally {
            db.close()
        }
    }

    private fun pairRow() =
        ContentValues().apply {
            put("pair_id", PAIR_ID)
            put("peer_device_id", "5b1f8c2e-9a4d-8e6f-a1b2-c3d4e5f60718")
            put("peer_name", "MacBook của Lan")
            put("peer_platform", "macos")
            put("peer_ik_sig_pub", ByteArray(KEY_SIZE))
            put("peer_ik_dh_pub", ByteArray(KEY_SIZE))
            put("prk_enc", ByteArray(KEY_SIZE))
            put("attestation", ByteArray(KEY_SIZE))
            put("sig_self", ByteArray(KEY_SIZE))
            put("sig_peer", ByteArray(KEY_SIZE))
            put("created_at", 1_727_150_003_210L)
        }

    private companion object {
        const val SCHEMA_V1 = "schemas/app.handlive.android.core.data.db.HandLiveDatabase/1.json"
        const val SCHEMA_V2 = "schemas/app.handlive.android.core.data.db.HandLiveDatabase/2.json"
        const val PAIR_ID = "3f2b1c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"
        const val KEY_SIZE = 32
        const val LIMIT = 20
    }
}
