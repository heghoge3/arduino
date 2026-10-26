package com.vabugeode.app.data

import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Дыры в выгрузке спутника затыкаются увеличенным снимком уровнем ниже; сервер отдаёт тайлы по HTTP. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class SatTilesTest {

    @Test
    fun holeFilledFromParentAndServedOverHttp() {
        val f = File(RuntimeEnvironment.getApplication().cacheDir, "sat.mbtiles")
        // Есть только z10 (x=598, y=297 в XYZ) — Петербург
        makeMbtiles(f, listOf(Triple(10, 598, 297)))
        val template = SatTiles.urlTemplate(f)

        assertNotNull("сам тайл", SatTiles.tileOrParent(10, 598, 297))
        // z12 внутри того же тайла: 598*4+1, 297*4+2 — берётся кусок родителя
        val child = SatTiles.tileOrParent(12, 598 * 4 + 1, 297 * 4 + 2)!!
        val bmp = BitmapFactory.decodeByteArray(child, 0, child.size)
        assertEquals(256, bmp.width)
        assertEquals(256, bmp.height)
        // Совсем другое место — пусто
        assertNull(SatTiles.tileOrParent(12, 0, 0))

        val url = template.replace("{z}", "12").replace("{x}", "${598 * 4 + 1}").replace("{y}", "${297 * 4 + 2}")
        val conn = URL(url).openConnection() as HttpURLConnection
        assertEquals(200, conn.responseCode)
        assertEquals("image/jpeg", conn.contentType)
        conn.inputStream.use { assert(it.readBytes().isNotEmpty()) }

        val empty = URL(template.replace("{z}", "12").replace("{x}", "0").replace("{y}", "0")).openConnection() as HttpURLConnection
        assertEquals(204, empty.responseCode)
        SatTiles.close()
        f.delete()
    }

    companion object {
        /** MBTiles с JPEG-тайлами по списку (z, x, y в схеме XYZ) */
        fun makeMbtiles(f: File, tiles: List<Triple<Int, Int, Int>>) {
            f.delete()
            val bmp = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(40, 90, 60)) }
            val jpg = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
            SQLiteDatabase.openOrCreateDatabase(f, null).use { db ->
                db.execSQL("CREATE TABLE metadata (name TEXT, value TEXT)")
                db.execSQL("CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB)")
                tiles.forEach { (z, x, y) ->
                    db.execSQL("INSERT INTO tiles VALUES (?, ?, ?, ?)", arrayOf(z, x, (1 shl z) - 1 - y, jpg))
                }
            }
        }
    }
}
