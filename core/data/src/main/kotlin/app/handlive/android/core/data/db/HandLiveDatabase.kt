package app.handlive.android.core.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

/** Room `handlive.db` of the Android hub (0.9.1). Phase 1 holds `paired_device`; SMS and push tables come later. */
@Database(entities = [PairedDeviceEntity::class], version = 1, exportSchema = true)
@TypeConverters(HandLiveConverters::class)
abstract class HandLiveDatabase : RoomDatabase() {
    abstract fun pairedDevices(): PairedDeviceDao

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
}
