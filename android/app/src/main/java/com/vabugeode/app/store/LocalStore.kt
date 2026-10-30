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

/** Строка журнала гаджета (пакет LOG как есть, разобранные поля — для фильтров) */
@Entity(tableName = "gadget_log", primaryKeys = ["device", "seq"])
class GadgetLogRow(
    val device: String,
    val seq: Long,
    val timeMs: Long,      // 0 — время неизвестно
    val uptimeSec: Long,
    val level: Int,
    val timeOk: Boolean,
    val module: Int,
    val text: String,
)

/** Строка журнала приложения: подключения, обрывы, ошибки Bluetooth, работа в фоне */
@Entity(tableName = "app_log")
class AppLogRow(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timeMs: Long,
    val level: Int,
    val tag: String,
    val text: String,
)

/** Поход в базе: сколько точек и за какое время (ts — Unix-с) */
data class HikeKey(val device: String, val hike: Long, val n: Int, val first: Long, val last: Long)

/** Только явно выбранные пользователем походы участвуют в достижениях. */
@Entity(tableName = "achievement_selections", primaryKeys = ["device", "hike"])
data class AchievementSelection(val device: String, val hike: Long, val enabled: Boolean)

@Entity(tableName = "achievement_unlocks", primaryKeys = ["id"])
data class AchievementUnlock(val id: String, val unlockedMs: Long, val seenMs: Long = 0)

data class AchievementKey(val device: String, val hike: Long, val n: Int, val last: Long, val marks: Int, val lastMark: Int)

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
    @Query("INSERT OR IGNORE INTO achievement_selections(device,hike,enabled) SELECT :device,:hike,1 WHERE :device != 'virtual' AND EXISTS(SELECT 1 FROM points WHERE device=:device AND hike=:hike)")
    suspend fun includeAchievementHike(device: String, hike: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM achievement_selections WHERE device=:device AND hike=:hike AND enabled=1)")
    suspend fun isAchievementHike(device: String, hike: Long): Boolean

    @Query("SELECT * FROM achievement_selections WHERE enabled = 1")
    fun achievementSelections(): kotlinx.coroutines.flow.Flow<List<AchievementSelection>>

    @Query("SELECT p.device, p.hike, COUNT(*) AS n, MAX(p.ts) AS last, (SELECT COUNT(*) FROM marks m WHERE m.device=p.device AND m.hike=p.hike) AS marks, COALESCE((SELECT MAX(m.id) FROM marks m WHERE m.device=p.device AND m.hike=p.hike),0) AS lastMark FROM points p INNER JOIN achievement_selections s ON p.device=s.device AND p.hike=s.hike WHERE s.enabled=1 AND p.device != 'virtual' GROUP BY p.device,p.hike ORDER BY p.hike")
    fun achievementKeys(): kotlinx.coroutines.flow.Flow<List<AchievementKey>>

    @Query("SELECT * FROM achievement_unlocks")
    suspend fun achievementUnlocks(): List<AchievementUnlock>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAchievementUnlocks(rows: List<AchievementUnlock>)

    @Query("UPDATE achievement_unlocks SET seenMs=:now WHERE id=:id")
    suspend fun seeAchievement(id: String, now: Long)

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
        if (isAchievementHike(device, hike)) return
        deletePoints(device, hike)
        deleteMarks(device, hike)
        deleteHikeName(device, hike)
    }

    // ---- Журналы ----

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun putGadgetLog(rows: List<GadgetLogRow>)

    @Query("SELECT MAX(seq) FROM gadget_log WHERE device = :device")
    suspend fun maxLogSeq(device: String): Long?

    @Insert
    suspend fun putAppLog(rows: List<AppLogRow>)

    @Query("SELECT * FROM gadget_log ORDER BY timeMs DESC, seq DESC LIMIT :limit")
    fun gadgetLogFlow(limit: Int): kotlinx.coroutines.flow.Flow<List<GadgetLogRow>>

    @Query("SELECT * FROM app_log ORDER BY id DESC LIMIT :limit")
    fun appLogFlow(limit: Int): kotlinx.coroutines.flow.Flow<List<AppLogRow>>

    @Query("SELECT * FROM gadget_log ORDER BY timeMs, seq")
    suspend fun allGadgetLog(): List<GadgetLogRow>

    @Query("SELECT * FROM app_log ORDER BY id")
    suspend fun allAppLog(): List<AppLogRow>

    /** Держим журналы в разумных размерах: старые строки удаляем */
    @Query("DELETE FROM gadget_log WHERE rowid IN (SELECT rowid FROM gadget_log ORDER BY timeMs DESC, seq DESC LIMIT -1 OFFSET :keep)")
    suspend fun trimGadgetLog(keep: Int)

    @Query("DELETE FROM app_log WHERE id IN (SELECT id FROM app_log ORDER BY id DESC LIMIT -1 OFFSET :keep)")
    suspend fun trimAppLog(keep: Int)

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

/** v3: журналы гаджета и приложения */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `gadget_log` (`device` TEXT NOT NULL, `seq` INTEGER NOT NULL, `timeMs` INTEGER NOT NULL, " +
                "`uptimeSec` INTEGER NOT NULL, `level` INTEGER NOT NULL, `timeOk` INTEGER NOT NULL, `module` INTEGER NOT NULL, " +
                "`text` TEXT NOT NULL, PRIMARY KEY(`device`, `seq`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `app_log` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timeMs` INTEGER NOT NULL, " +
                "`level` INTEGER NOT NULL, `tag` TEXT NOT NULL, `text` TEXT NOT NULL)",
        )
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `achievement_selections` (`device` TEXT NOT NULL, `hike` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, PRIMARY KEY(`device`, `hike`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `achievement_unlocks` (`id` TEXT NOT NULL, `unlockedMs` INTEGER NOT NULL, `seenMs` INTEGER NOT NULL, PRIMARY KEY(`id`))")
    }
}

@Database(
    entities = [PointRow::class, MarkRow::class, GadgetRow::class, HikeRow::class, GadgetLogRow::class, AppLogRow::class, AchievementSelection::class, AchievementUnlock::class],
    version = 4,
    exportSchema = false,
)
abstract class StoreDb : RoomDatabase() {
    abstract fun dao(): StoreDao

    companion object {
        @Volatile private var instance: StoreDb? = null

        /** Одна база на процесс: её пишут и экраны, и фоновое наблюдение */
        fun get(context: Context): StoreDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, StoreDb::class.java, "vabugeode.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build().also { instance = it }
        }
    }
}
