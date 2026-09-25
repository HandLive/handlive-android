package app.handlive.android.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** Queries of PAIR-01…03 and CONN-01 on `paired_device` (0.9.1). */
@Dao
interface PairedDeviceDao {
    /** PAIR-01 step 4: active pairs (at most 8). */
    @Query("SELECT COUNT(*) FROM paired_device WHERE revoked_at IS NULL")
    suspend fun activeCount(): Int

    /** CONN-01 step 7: the pair named by `session/hello`, tombstones included (→ `PAIR_REVOKED`). */
    @Query("SELECT * FROM paired_device WHERE pair_id = :pairId")
    fun findBlocking(pairId: String): PairedDeviceEntity?

    @Query("SELECT * FROM paired_device WHERE pair_id = :pairId")
    suspend fun find(pairId: String): PairedDeviceEntity?

    /** PAIR-02 step 2: active pairs, most recently seen first. */
    @Query(
        "SELECT * FROM paired_device WHERE revoked_at IS NULL " +
            "ORDER BY COALESCE(last_seen_at, created_at) DESC",
    )
    fun observeActive(): Flow<List<PairedDeviceEntity>>

    /** CONN-01 API 1: pairs whose discovery hint goes into the TXT record. */
    @Query("SELECT * FROM paired_device WHERE revoked_at IS NULL")
    suspend fun active(): List<PairedDeviceEntity>

    @Query("DELETE FROM paired_device WHERE peer_device_id = :peerDeviceId")
    suspend fun deleteByPeer(peerDeviceId: String)

    @Insert
    suspend fun insert(entity: PairedDeviceEntity)

    /** PAIR-01 API 4 rule 4: a client pairing again replaces its old row in one transaction. */
    @Transaction
    suspend fun replaceForPeer(entity: PairedDeviceEntity) {
        deleteByPeer(entity.peerDeviceId)
        insert(entity)
    }

    /** CONN-01 step 10 and SET-02 API 1: last contact and the peer's latest capability. */
    @Query("UPDATE paired_device SET last_seen_at = :now, features_json = :featuresJson WHERE pair_id = :pairId")
    suspend fun recordSeen(
        pairId: String,
        now: Long,
        featuresJson: String,
    )

    /** PAIR-03 step 7: tombstone — keys and capability are wiped, `revoked_at` set. */
    @Query(
        "UPDATE paired_device SET revoked_at = :now, prk_enc = X'', features_json = '{}' " +
            "WHERE pair_id = :pairId AND revoked_at IS NULL",
    )
    suspend fun tombstone(
        pairId: String,
        now: Long,
    ): Int

    /** PAIR-03 step 9: drop a tombstone once the relay knows, or when the pair never reached the relay. */
    @Query("DELETE FROM paired_device WHERE pair_id = :pairId AND revoked_at IS NOT NULL")
    suspend fun deleteTombstone(pairId: String): Int

    /** SET-02 A4/A5: remove every pair (no tombstones, the relay already dropped them). */
    @Query("DELETE FROM paired_device")
    suspend fun deleteAll()
}
