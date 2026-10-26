package com.vabugeode.app.store

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Локальная база телефона. Храним сырые пакеты протокола (POINT, MARK, STATUS, INFO) —
 * разбираются тем же кодеком, что и по радио, и формат не надо дублировать.
 * device — MAC гаджета или «virtual»; hike — hike_start похода (Unix-с).
 */
@Entity(tableName = "points", primaryKeys = ["device", "hike", "ts"])
class PointRow(val device: String, val hike: Long, val ts: Long, val data: ByteArray)

@Entity(tableName = "marks", primaryKeys = ["device", "hike", "id"])
class MarkRow(val device: String, val hike: Long, val id: Int, val data: ByteArray)

/** Своё название похода («Урбекс Кронштадт»). Нет строки — название по дате. */
@Entity(tableName = "hikes", primaryKeys = ["device", "hike"])
class HikeRow(val device: String, val hike: Long, val name: String)

/** Поход в базе: сколько точек и за какое время (ts — Unix-с) */
data class HikeKey(val device: String, val hike: Long, val n: Int, val first: Long, val last: Long)

/** Последнее известное состояние гаджета + где его последний раз слышали */
@Entity(tableName = "gadgets", primaryKeys = ["device"])
class GadgetRow(
    val device: String,
    val hike: Long?,
    val status: ByteArray?,
    val sats: ByteArray?,
    val info: ByteArray?,
    val lastSeenMs: Long,
    // Последний маячок и позиция телефона в этот момент («где гаджет»)
    val beacon: ByteArray? = null,
    val beaconMs: Long = 0,
    val phoneLat: Double? = null,
    val phoneLon: Double? = null,
    val phoneAcc: Int? = null,
)

@Dao
interface StoreDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putPoints(rows: List<PointRow>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putMarks(rows: List<MarkRow>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putGadget(row: GadgetRow)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putHike(row: HikeRow)

    /** Все походы всех гаджетов — пересчитывается при каждой записи точек */
    @Query("SELECT device, hike, COUNT(*) AS n, MIN(ts) AS first, MAX(ts) AS last FROM points GROUP BY device, hike ORDER BY hike DESC")
    fun hikeKeys(): kotlinx.coroutines.flow.Flow<List<HikeKey>>

    @Query("SELECT * FROM hikes")
    fun hikeNames(): kotlinx.coroutines.flow.Flow<List<HikeRow>>

    @Query("DELETE FROM points WHERE device = :device AND hike = :hike")
    suspend fun deletePoints(device: String, hike: Long)

    @Query("DELETE FROM marks WHERE device = :device AND hike = :hike")
    suspend fun deleteMarks(device: String, hike: Long)

    @Query("DELETE FROM hikes WHERE device = :device AND hike = :hike")
    suspend fun deleteHikeName(device: String, hike: Long)

    /** Удалить поход только из телефона — файл на SD гаджета остаётся копией */
    @Transaction
    suspend fun deleteHike(device: String, hike: Long) {
        deletePoints(device, hike)
        deleteMarks(device, hike)
        deleteHikeName(device, hike)
    }

    @Query("SELECT * FROM points WHERE device = :device AND hike = :hike ORDER BY ts")
    suspend fun points(device: String, hike: Long): List<PointRow>

    @Query("SELECT * FROM marks WHERE device = :device AND hike = :hike ORDER BY id")
    suspend fun marks(device: String, hike: Long): List<MarkRow>

    @Query("SELECT * FROM gadgets WHERE device = :device")
    suspend fun gadget(device: String): GadgetRow?

    @Query("SELECT DISTINCT hike FROM points WHERE device = :device ORDER BY hike DESC")
    suspend fun hikes(device: String): List<Long>

    @Query("SELECT * FROM gadgets WHERE device = :device")
    fun gadgetFlow(device: String): kotlinx.coroutines.flow.Flow<GadgetRow?>

    // Состояние и маячок пишут разные части приложения (экран и фоновое наблюдение) —
    // обновляем только свои поля, чтобы не затереть чужие
    @Query("UPDATE gadgets SET hike = :hike, status = :status, sats = :sats, info = :info, lastSeenMs = :seen WHERE device = :device")
    suspend fun updateState(device: String, hike: Long?, status: ByteArray?, sats: ByteArray?, info: ByteArray?, seen: Long): Int

    @Query("UPDATE gadgets SET beacon = :beacon, beaconMs = :ms, phoneLat = :lat, phoneLon = :lon, phoneAcc = :acc WHERE device = :device")
    suspend fun updateBeacon(device: String, beacon: ByteArray, ms: Long, lat: Double?, lon: Double?, acc: Int?): Int
}

suspend fun StoreDao.saveState(device: String, hike: Long?, status: ByteArray?, sats: ByteArray?, info: ByteArray?, seen: Long) {
    if (updateState(device, hike, status, sats, info, seen) == 0) {
        putGadget(GadgetRow(device, hike, status, sats, info, seen))
    }
}

/** Маячок + где был телефон в этот момент («где гаджет») */
suspend fun StoreDao.saveBeacon(device: String, beacon: ByteArray, ms: Long, lat: Double?, lon: Double?, acc: Int?) {
    if (updateBeacon(device, beacon, ms, lat, lon, acc) == 0) {
        putGadget(GadgetRow(device, null, null, null, null, 0, beacon, ms, lat, lon, acc))
    }
}

/** v2: таблица названий походов */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `hikes` (`device` TEXT NOT NULL, `hike` INTEGER NOT NULL, `name` TEXT NOT NULL, PRIMARY KEY(`device`, `hike`))")
    }
}

@Database(entities = [PointRow::class, MarkRow::class, GadgetRow::class, HikeRow::class], version = 2, exportSchema = false)
abstract class StoreDb : RoomDatabase() {
    abstract fun dao(): StoreDao

    companion object {
        @Volatile private var instance: StoreDb? = null

        /** Одна база на процесс: её пишут и экраны, и фоновое наблюдение */
        fun get(context: Context): StoreDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, StoreDb::class.java, "vabugeode.db")
                .addMigrations(MIGRATION_1_2)
                .build().also { instance = it }
        }
    }
}
