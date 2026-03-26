package com.example.myapplication

import android.content.Context
import androidx.room.Database
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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

    val foreground: Boolean = false,

    @ColumnInfo(name = "is_charging")
    val isCharging: Boolean = false
)

@Dao
interface BatterySampleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSample(sample: BatterySample): Long

    @Query("SELECT * FROM batterysample ORDER BY timestampEpochMillis DESC LIMIT 1")
    suspend fun getLatestSample(): BatterySample?

    @Query("SELECT * FROM batterysample WHERE timestampEpochMillis >= :sinceEpochMillis ORDER BY timestampEpochMillis ASC")
    suspend fun getSamplesSince(sinceEpochMillis: Long): List<BatterySample>

    @Query(
        "SELECT * FROM batterysample WHERE is_charging = 0 ORDER BY timestampEpochMillis DESC LIMIT 50"
    )
    suspend fun getLast50NonChargingSamples(): List<BatterySample>

    @Query("DELETE FROM batterysample WHERE is_charging = 1")
    suspend fun deleteChargingRows(): Int

    @Query(
        """
        DELETE FROM batterysample
        WHERE id IN (
            SELECT cur.id
            FROM batterysample AS cur
            JOIN batterysample AS prev
              ON prev.timestampEpochMillis = (
                    SELECT MAX(p.timestampEpochMillis)
                    FROM batterysample AS p
                    WHERE p.timestampEpochMillis < cur.timestampEpochMillis
                )
            WHERE cur.is_charging = 0
              AND prev.is_charging = 0
              AND (
                    cur.batteryLevel > prev.batteryLevel
                 OR cur.voltage > prev.voltage
              )
        )
        """
    )
    suspend fun deleteOrphanUpwardSpikes(): Int


    @Query("DELETE FROM batterysample WHERE timestampEpochMillis < :cutoffEpochMillis")
    suspend fun deleteOlderThan(cutoffEpochMillis: Long): Int


    @Query("SELECT * FROM batterysample WHERE timestampEpochMillis >= :sinceEpochMillis ORDER BY timestampEpochMillis ASC")
    fun samplesSinceFlow(sinceEpochMillis: Long): Flow<List<BatterySample>>


    @Query("SELECT COUNT(*) FROM batterysample")
    suspend fun getSampleCount(): Int

    @Query("SELECT COUNT(*) FROM batterysample WHERE is_charging = 0")
    suspend fun getNonChargingSampleCount(): Int

    @Query("DELETE FROM batterysample")
    suspend fun clearAllSamples()

    @Query("DELETE FROM batterysample")
    suspend fun clearAllData()
}


@Database(
    entities = [BatterySample::class],
    version = 2,
    exportSchema = false
)
abstract class BatteryDatabase : RoomDatabase() {
    abstract fun batterySampleDao(): BatterySampleDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE batterysample ADD COLUMN is_charging INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        @Volatile
        private var instance: BatteryDatabase? = null

        fun getInstance(context: Context): BatteryDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    BatteryDatabase::class.java,
                    "battery_database"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
