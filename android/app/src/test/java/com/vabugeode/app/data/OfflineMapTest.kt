package com.vabugeode.app.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** Офлайн-карта: стиль из APK собирается, путь к файлу подставляется, шрифты на месте. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineMapTest {

    private val ctx = RuntimeEnvironment.getApplication()

    @Test
    fun styleUsesLocalFileAndBundledFonts() {
        File(ctx.filesDir, "maps").deleteRecursively()
        assertNull("без файла — онлайн-карта", OfflineMap.styleJson(ctx))

        val f = File(File(ctx.filesDir, "maps").apply { mkdirs() }, "region.pmtiles")
        f.writeBytes("PMTiles".toByteArray() + byteArrayOf(3))
        val style = JSONObject(OfflineMap.styleJson(ctx)!!)
        val url = style.getJSONObject("sources").getJSONObject("protomaps").getString("url")
        assertEquals("pmtiles://file://" + f.absolutePath, url)

        // Каждый шрифт из слоёв есть в assets, вместе с кириллицей
        val fonts = mutableSetOf<String>()
        val layers = style.getJSONArray("layers")
        for (i in 0 until layers.length()) {
            val font = layers.getJSONObject(i).optJSONObject("layout")?.opt("text-font") ?: continue
            collectStrings(font, fonts)
        }
        val names = fonts.filter { it.startsWith("Noto Sans") }
        assertTrue(names.isNotEmpty())
        names.forEach { name ->
            ctx.assets.open("fonts/$name/1024-1279.pbf").close()
            ctx.assets.open("fonts/$name/0-255.pbf").close()
        }
        f.delete()
    }

    private fun collectStrings(v: Any, out: MutableSet<String>) {
        when (v) {
            is String -> out += v
            is JSONArray -> for (i in 0 until v.length()) collectStrings(v.get(i), out)
        }
    }

    @Test
    fun satelliteMbtilesWithSchemeLabels() {
        val dir = File(ctx.filesDir, "maps").apply { deleteRecursively(); mkdirs() }
        assertNull(OfflineMap.satelliteStyleJson(ctx))
        File(dir, "satellite").writeBytes("SQLite format 3\u0000".toByteArray() + ByteArray(100))
        var style = JSONObject(OfflineMap.satelliteStyleJson(ctx)!!)
        val sat = style.getJSONObject("sources").getJSONObject("sat")
        assertEquals("raster", sat.getString("type"))
        assertEquals("mbtiles://" + File(dir, "satellite").absolutePath, sat.getString("url"))
        assertEquals(2, style.getJSONArray("layers").length())

        // Есть схема — поверх снимка подписи
        File(dir, "region.pmtiles").writeBytes(ByteArray(8))
        style = JSONObject(OfflineMap.satelliteStyleJson(ctx)!!)
        assertTrue(style.getJSONArray("layers").length() > 5)
        assertTrue(style.getJSONObject("sources").has("protomaps"))
        dir.deleteRecursively()
    }

    @Test
    fun detectFormats() {
        fun pm(type: Int) = ("PMTiles".toByteArray() + byteArrayOf(3) + ByteArray(119)).also { it[99] = type.toByte() }
        assertEquals(OfflineMap.Format.PMTILES_VECTOR, OfflineMap.detect(pm(1)))
        assertEquals(OfflineMap.Format.PMTILES_RASTER, OfflineMap.detect(pm(3)))
        assertEquals(OfflineMap.Format.MBTILES, OfflineMap.detect("SQLite format 3\u0000".toByteArray() + ByteArray(50)))
        assertNull(OfflineMap.detect("hello".toByteArray()))
    }
}
