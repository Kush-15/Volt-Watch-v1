package com.example.myapplication

import android.content.Context
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * BatterySample entity for persisting battery telemetry.
 *
 * Fields:
 * - id: Auto-generated primary key.
 * - timestampEpochMillis: UTC epoch milliseconds (indexed for fast range queries).
 * - batteryLevel: Battery percentage 0.0..100.0.
 * - voltage: Battery voltage in millivolts.
 * - servicesActive: Boolean indicating whether background services are active.
 * - foreground: Boolean indicating whether device is in foreground.
 *
 * Feature mask usage:
 * - bit0: time (required, always enabled)
 * - bit1: voltage
 * - bit2: services
 * - bit3: usage minutes
 */
@Entity(
    tableName = "batterysample",
    indices = [Index(value = ["timestampEpochMillis"])]
)
data class BatterySample(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    val timestampEpochMillis: Long,

    val batteryLevel: Float,

    val voltage: Int,

    val servicesActive: Boolean,

    val foreground: Boolean = false
)

@Dao
interface BatterySampleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSample(sample: BatterySample): Long

    @Query("SELECT * FROM batterysample WHERE timestampEpochMillis >= :sinceEpochMillis ORDER BY timestampEpochMillis ASC")
    suspend fun getSamplesSince(sinceEpochMillis: Long): List<BatterySample>


    @Query("DELETE FROM batterysample WHERE timestampEpochMillis < :cutoffEpochMillis")
    suspend fun deleteOlderThan(cutoffEpochMillis: Long): Int


    @Query("SELECT * FROM batterysample WHERE timestampEpochMillis >= :sinceEpochMillis ORDER BY timestampEpochMillis ASC")
    fun samplesSinceFlow(sinceEpochMillis: Long): Flow<List<BatterySample>>


    @Query("SELECT COUNT(*) FROM batterysample")
    suspend fun getSampleCount(): Int


    @Query("DELETE FROM batterysample")
    suspend fun clearAllSamples()
}


@Database(
    entities = [BatterySample::class],
    version = 1,
    exportSchema = false
)
abstract class BatteryDatabase : RoomDatabase() {
    abstract fun batterySampleDao(): BatterySampleDao

    companion object {
        @Volatile
        private var instance: BatteryDatabase? = null

        fun getInstance(context: Context): BatteryDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    BatteryDatabase::class.java,
                    "battery_database"
                ).build().also { instance = it }
            }
        }
    }
}
