package app.handlive.android.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/** `push_outbox.kind` (0.9.1): a wake for Android or an alert for iPhone/iPad (CONN-04). */
enum class PushKind(
    val wire: String,
) {
    WAKE("wake"),
    ALERT("alert"),
    ;

    companion object {
        fun fromWire(value: String): PushKind? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * One row of `push_outbox` (0.9.1): a push the relay refused for a temporary reason (CONN-04 E2), retried until
 * [expiresAt]. [bodyB64] is the envelope sealed with `K_push` (never the plaintext); the row goes with its pair.
 * The `pair_id` index serves the cascade delete and is not part of the 0.9.1 design.
 */
@Entity(
    tableName = "push_outbox",
    foreignKeys = [
        ForeignKey(
            entity = PairedDeviceEntity::class,
            parentColumns = ["pair_id"],
            childColumns = ["pair_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["pair_id"])],
)
class PushOutboxEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "pair_id")
    val pairId: String,
    @ColumnInfo(name = "kind")
    val kind: PushKind,
    @ColumnInfo(name = "body_b64")
    val bodyB64: String?,
    @ColumnInfo(name = "collapse_key")
    val collapseKey: String?,
    @ColumnInfo(name = "attempts", defaultValue = "0")
    val attempts: Int = 0,
    @ColumnInfo(name = "next_attempt_at")
    val nextAttemptAt: Long,
    @ColumnInfo(name = "expires_at")
    val expiresAt: Long,
)

/** Queries of CONN-04 (step 5b, E2) and SET-02 (A4, A5) on `push_outbox`. */
@Dao
interface PushOutboxDao {
    /** Step 5b (E2): queue a push. */
    @Insert
    suspend fun insert(entry: PushOutboxEntity)

    /** The pushes due for a retry, oldest first, at most [limit]. */
    @Query(
        "SELECT * FROM push_outbox WHERE next_attempt_at <= :now AND expires_at > :now " +
            "ORDER BY next_attempt_at ASC LIMIT :limit",
    )
    suspend fun due(
        now: Long,
        limit: Int,
    ): List<PushOutboxEntity>

    /** When the earliest waiting push is due, to schedule the next retry. */
    @Query("SELECT MIN(next_attempt_at) FROM push_outbox WHERE expires_at > :now")
    suspend fun nextAttemptAt(now: Long): Long?

    @Query("UPDATE push_outbox SET attempts = :attempts, next_attempt_at = :nextAttemptAt WHERE id = :id")
    suspend fun reschedule(
        id: String,
        attempts: Int,
        nextAttemptAt: Long,
    )

    /** A push that was sent (or can never be sent), and every push past its expiry. */
    @Query("DELETE FROM push_outbox WHERE id = :id OR expires_at <= :now")
    suspend fun delete(
        id: String,
        now: Long,
    )

    @Query("DELETE FROM push_outbox WHERE expires_at <= :now")
    suspend fun deleteExpired(now: Long)

    /** SET-02 A4/A5. */
    @Query("DELETE FROM push_outbox")
    suspend fun clear()
}
