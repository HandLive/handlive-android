package app.handlive.android.core.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

/**
 * Room `handlive.db` of the Android hub (0.9.1): `paired_device` since Phase 1; Phase 2 adds `sms_observer_state`
 * (SMS-02) and `push_outbox` (CONN-04). Version 2 only adds tables, so Room migrates 1 → 2 by itself.
 */
@Database(
    entities = [PairedDeviceEntity::class, SmsObserverStateEntity::class, PushOutboxEntity::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
@TypeConverters(HandLiveConverters::class)
abstract class HandLiveDatabase : RoomDatabase() {
    abstract fun pairedDevices(): PairedDeviceDao

    abstract fun smsObserverState(): SmsObserverStateDao

    abstract fun pushOutbox(): PushOutboxDao

    companion object {
        const val FILE_NAME = "handlive.db"

        fun open(context: Context): HandLiveDatabase =
            Room.databaseBuilder(context.applicationContext, HandLiveDatabase::class.java, FILE_NAME).build()
    }
}

internal class HandLiveConverters {
    @TypeConverter
    fun platformToWire(platform: PeerPlatform): String = platform.wire

    @TypeConverter
    fun platformFromWire(value: String): PeerPlatform =
        requireNotNull(PeerPlatform.fromWire(value)) { "unknown peer_platform" }

    @TypeConverter
    fun pushKindToWire(kind: PushKind): String = kind.wire

    @TypeConverter
    fun pushKindFromWire(value: String): PushKind = requireNotNull(PushKind.fromWire(value)) { "unknown kind" }
}
