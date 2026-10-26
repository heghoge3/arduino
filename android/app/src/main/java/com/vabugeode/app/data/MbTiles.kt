package com.vabugeode.app.data

import android.database.sqlite.SQLiteDatabase
import java.io.File
import kotlin.math.PI
import kotlin.math.atan


/**
 * Починка описания MBTiles. Выгрузки (например, SAS.Planet) часто не пишут в metadata
 * minzoom/maxzoom/format/bounds. Без maxzoom карта при приближении просит несуществующие
 * уровни и показывает пустоту вместо растянутого последнего уровня.
 * Считаем всё по самим тайлам и дописываем — файл уже наша копия.
 */
object MbTiles {

    /** Что лежит в файле: диапазон уровней (по стандартной нумерации), число тайлов, формат */
    data class Info(val minZoom: Int, val maxZoom: Int, val tiles: Long, val format: String)

    fun fix(file: File): Info? = runCatching {
        // Свой обработчик ошибок: стандартный при «повреждённой базе» удаляет файл — а это гигабайты карты
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE) { }.use { db ->
            db.execSQL("CREATE TABLE IF NOT EXISTS metadata (name TEXT, value TEXT)")
            val meta = HashMap<String, String>()
            db.rawQuery("SELECT name, value FROM metadata", null).use { c ->
                while (c.moveToNext()) meta[c.getString(0)] = c.getString(1) ?: ""
            }
            val (minZ, maxZ, count) = db.rawQuery("SELECT MIN(zoom_level), MAX(zoom_level), COUNT(*) FROM tiles", null).use { c ->
                c.moveToFirst()
                Triple(c.getInt(0), c.getInt(1), c.getLong(2))
            }
            if (count == 0L) return@use null
            val format = meta["format"]?.takeIf { it.isNotBlank() } ?: db.rawQuery("SELECT tile_data FROM tiles LIMIT 1", null).use { c ->
                c.moveToFirst()
                sniff(c.getBlob(0))
            }
            fun put(name: String, value: String) {
                db.execSQL("DELETE FROM metadata WHERE name = ?", arrayOf(name))
                db.execSQL("INSERT INTO metadata (name, value) VALUES (?, ?)", arrayOf(name, value))
            }
            // Диапазон уровней всегда по факту: в описании бывает «до 24», а тайлов столько нет
            put("minzoom", minZ.toString())
            put("maxzoom", maxZ.toString())
            put("format", format)
            if (meta["name"].isNullOrBlank()) put("name", "Спутник")
            if (meta["type"].isNullOrBlank()) put("type", "baselayer")
            if (meta["bounds"].isNullOrBlank()) {
                // Рамка по тайлам самого подробного уровня; строки в MBTiles снизу вверх (TMS)
                db.rawQuery(
                    "SELECT MIN(tile_column), MAX(tile_column), MIN(tile_row), MAX(tile_row) FROM tiles WHERE zoom_level = ?",
                    arrayOf(maxZ.toString()),
                ).use { c ->
                    c.moveToFirst()
                    val n = 1 shl maxZ
                    val west = c.getInt(0) * 360.0 / n - 180
                    val east = (c.getInt(1) + 1) * 360.0 / n - 180
                    val south = tileLat(n - 1 - c.getInt(2) + 1, n)
                    val north = tileLat(n - 1 - c.getInt(3), n)
                    put("bounds", "%.6f,%.6f,%.6f,%.6f".format(java.util.Locale.US, west, south, east, north))
                }
            }
            Info(minZ, maxZ, count, format)
        }
    }.getOrNull()

    /** Широта верхнего края тайла y (XYZ) при n тайлах по стороне */
    private fun tileLat(y: Int, n: Int): Double = Math.toDegrees(atan(kotlin.math.sinh(PI * (1 - 2.0 * y / n))))

    private fun sniff(b: ByteArray?): String = when {
        b == null || b.size < 4 -> "jpg"
        b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() -> "png"
        b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() -> "webp"
        else -> "jpg"
    }

}
