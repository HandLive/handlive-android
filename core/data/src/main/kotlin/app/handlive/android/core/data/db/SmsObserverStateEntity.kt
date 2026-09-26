package app.handlive.android.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/**
 * `sms_observer_state` (0.9.1): the single row that remembers the largest SMS `_id` already emitted as an event
 * (SMS-02 step 12), so a restart of the service neither replays old messages nor misses new ones. `id` is always 1.
 */
@Entity(tableName = "sms_observer_state")
class SmsObserverStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: Int = SINGLE_ROW,
    @ColumnInfo(name = "last_sms_id")
    val lastSmsId: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
) {
    companion object {
        const val SINGLE_ROW = 1
    }
}

/** Queries of SMS-02 (step 3, step 12, API 3 logic 1 and 4) on `sms_observer_state`. */
@Dao
interface SmsObserverStateDao {
    /** Step 3: the processed mark; `null` on the very first run. */
    @Query("SELECT last_sms_id FROM sms_observer_state WHERE id = 1")
    suspend fun lastSmsId(): Long?

    /** Step 12: write the mark, creating the row on the first run. */
    @Upsert
    suspend fun write(state: SmsObserverStateEntity)

    /** SET-02 A5: delete all HandLive data. */
    @Query("DELETE FROM sms_observer_state")
    suspend fun clear()
}
