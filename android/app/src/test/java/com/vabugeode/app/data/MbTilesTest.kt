package com.vabugeode.app.data

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** Выгрузка без описания (как из SAS.Planet): дописываем уровни, формат и рамку по самим тайлам. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MbTilesTest {

    @Test
    fun fillsMissingMetadata() {
        val f = File(RuntimeEnvironment.getApplication().cacheDir, "sat.mbtiles").apply { delete() }
        SQLiteDatabase.openOrCreateDatabase(f, null).use { db ->
            db.execSQL("CREATE TABLE metadata (name TEXT, value TEXT)")
            db.execSQL("INSERT INTO metadata VALUES ('maxzoom', '24')")  // описание врёт
            db.execSQL("CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB)")
            val jpg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0, 0)
            // Тайл с Петербургом на z10 (x=598, y XYZ=297 → TMS 1023-297=726) и z14
            db.execSQL("INSERT INTO tiles VALUES (10, 598, 726, ?)", arrayOf(jpg))
            db.execSQL("INSERT INTO tiles VALUES (14, 9572, 11615, ?)", arrayOf(jpg))
        }
        val info = MbTiles.fix(f)!!
        assertEquals(10, info.minZoom)
        assertEquals(14, info.maxZoom)
        assertEquals("jpg", info.format)

        val meta = HashMap<String, String>()
        SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT name, value FROM metadata", null).use { c -> while (c.moveToNext()) meta[c.getString(0)] = c.getString(1) }
        }
        assertEquals("14", meta["maxzoom"])
        val (w, s, e, n) = meta["bounds"]!!.split(",").map { it.toDouble() }
        // Тайл z14 под Петербургом: около 30,3 в. д., 59,9 с. ш.
        assertTrue("bounds=${meta["bounds"]}", w in 30.0..30.5 && e in 30.0..30.5 && s in 59.7..60.1 && n in 59.7..60.1 && n > s)
        f.delete()
    }
}
