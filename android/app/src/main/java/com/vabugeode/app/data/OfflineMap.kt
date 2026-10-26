package com.vabugeode.app.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Офлайн-карты: два файла в памяти приложения.
 *  - SCHEME — схема, векторный .pmtiles (выжимка Protomaps из OpenStreetMap), стиль и шрифты в APK;
 *  - SATELLITE — спутник, растровый .pmtiles или .mbtiles (JPEG/PNG/WebP).
 * Пользователь выбирает файл в телефоне, мы копируем его к себе. Интернет не нужен.
 */
object OfflineMap {

    enum class Kind(val title: String, val fileName: String) {
        SCHEME("Схема", "region.pmtiles"),
        SATELLITE("Спутник", "satellite"),
    }

    /** Формат файла по заголовку */
    enum class Format { PMTILES_VECTOR, PMTILES_RASTER, MBTILES }

    /** Что с файлом: нет / копируем (доля 0..1) / есть (размер в байтах) */
    sealed interface State {
        data object None : State
        data class Copying(val fraction: Float) : State
        data class Ready(val bytes: Long, val format: Format, val info: String? = null) : State
    }

    private val states = Kind.entries.associateWith { MutableStateFlow<State?>(null) }
    fun state(kind: Kind): StateFlow<State?> = states.getValue(kind)

    private fun dir(ctx: Context) = File(ctx.filesDir, "maps")
    private fun file(ctx: Context, kind: Kind) = File(dir(ctx), kind.fileName)

    fun refresh(ctx: Context) = Kind.entries.forEach { refresh(ctx, it) }

    private fun refresh(ctx: Context, kind: Kind) {
        val st = states.getValue(kind)
        if (st.value is State.Copying) return
        val f = file(ctx, kind)
        val fmt = if (f.isFile && f.length() > 0) runCatching { detect(f.inputStream().use { readHead(it) }) }.getOrNull() else null
        st.value = if (fmt != null) State.Ready(f.length(), fmt, describe(f, fmt)) else State.None
    }

    /** Подпись для настроек: какие уровни приближения есть в файле */
    private fun describe(f: File, fmt: Format): String? = when (fmt) {
        Format.MBTILES -> MbTiles.fix(f)?.let { "уровни ${it.minZoom}–${it.maxZoom} · ${it.tiles} тайлов · ${it.format}" }
        else -> null
    }

    fun available(ctx: Context, kind: Kind = Kind.SCHEME): Boolean = file(ctx, kind).let { it.isFile && it.length() > 0 }

    /** Стиль схемы; null — файла нет, показываем онлайн-карту */
    fun styleJson(ctx: Context): String? {
        val f = file(ctx, Kind.SCHEME)
        if (!f.isFile) return null
        return schemeTemplate(ctx).replace("__PMTILES__", "pmtiles://file://" + f.absolutePath)
    }

    /**
     * Стиль «спутник»: только снимок. hybrid — гибрид: поверх снимка дороги, тропы,
     * границы и подписи из схемы (если она загружена).
     */
    fun satelliteStyleJson(ctx: Context, hybrid: Boolean = false): String? {
        val f = file(ctx, Kind.SATELLITE)
        if (!f.isFile) return null
        val fmt = runCatching { detect(f.inputStream().use { readHead(it) }) }.getOrNull() ?: return null
        val url = when (fmt) {
            // Описание файла дописываем до того, как его прочитает карта (см. MbTiles)
            Format.MBTILES -> MbTiles.fix(f).let { "mbtiles://" + f.absolutePath }
            else -> "pmtiles://file://" + f.absolutePath
        }
        val style = JSONObject()
            .put("version", 8)
            .put("name", "vabuGEOde satellite")
            .put("glyphs", "asset://fonts/{fontstack}/{range}.pbf")
        val sources = JSONObject().put("sat", JSONObject().put("type", "raster").put("url", url).put("tileSize", 256))
        val layers = JSONArray()
            .put(JSONObject().put("id", "background").put("type", "background").put("paint", JSONObject().put("background-color", "#060A12")))
            .put(JSONObject().put("id", "sat").put("type", "raster").put("source", "sat"))
        if (hybrid) styleJson(ctx)?.let { scheme ->
            val s = JSONObject(scheme)
            sources.put("protomaps", s.getJSONObject("sources").getJSONObject("protomaps"))
            val all = s.getJSONArray("layers")
            for (i in 0 until all.length()) {
                val l = all.getJSONObject(i)
                val id = l.optString("id")
                when {
                    l.optString("type") == "symbol" -> layers.put(l)
                    // Линии дорог и троп без «обводок» — на снимке обводка выглядит грязью
                    l.optString("type") == "line" && (id.startsWith("roads") || id.startsWith("boundaries")) && !id.contains("casing") -> {
                        val paint = l.optJSONObject("paint") ?: JSONObject().also { l.put("paint", it) }
                        paint.put("line-opacity", 0.75)
                        layers.put(l)
                    }
                }
            }
        }
        return style.put("sources", sources).put("layers", layers).toString()
    }

    private fun schemeTemplate(ctx: Context) = ctx.assets.open("map/style.json").bufferedReader().use { it.readText() }

    private fun readHead(input: java.io.InputStream): ByteArray {
        val head = ByteArray(127)
        var n = 0
        while (n < head.size) {
            val r = input.read(head, n, head.size - n)
            if (r < 0) break
            n += r
        }
        return head.copyOf(n)
    }

    /** PMTiles v3: «PMTiles», версия 3, тип тайлов в байте 99 (1 — вектор). MBTiles — это SQLite */
    internal fun detect(head: ByteArray): Format? {
        if (head.size >= 100 && String(head, 0, 7, Charsets.US_ASCII) == "PMTiles" && head[7].toInt() == 3) {
            return when (head[99].toInt()) {
                1 -> Format.PMTILES_VECTOR
                2, 3, 4, 5 -> Format.PMTILES_RASTER
                else -> null
            }
        }
        if (head.size >= 16 && String(head, 0, 15, Charsets.US_ASCII) == "SQLite format 3") return Format.MBTILES
        return null
    }

    /** Скопировать выбранный файл. Сначала во временный, чтобы обрыв не оставил битую карту */
    suspend fun import(ctx: Context, kind: Kind, uri: Uri): Result<Long> = withContext(Dispatchers.IO) {
        val st = states.getValue(kind)
        val tmp = File(dir(ctx), kind.fileName + ".tmp")
        runCatching {
            val resolver = ctx.contentResolver
            val total = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length }?.takeIf { it > 0 } ?: -1L
            val dst = file(ctx, kind)
            dir(ctx).mkdirs()
            st.value = State.Copying(0f)
            resolver.openInputStream(uri)?.use { input ->
                val head = readHead(input)
                val fmt = detect(head)
                when (kind) {
                    Kind.SCHEME -> if (fmt != Format.PMTILES_VECTOR) throw IOException("Для схемы нужен векторный файл .pmtiles")
                    Kind.SATELLITE -> if (fmt != Format.PMTILES_RASTER && fmt != Format.MBTILES) {
                        throw IOException("Для спутника нужен растровый .pmtiles или .mbtiles")
                    }
                }
                tmp.outputStream().use { out ->
                    out.write(head)
                    val buf = ByteArray(1 shl 16)
                    var done = head.size.toLong()
                    var lastShown = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0 && done - lastShown > (1 shl 22)) {
                            lastShown = done
                            st.value = State.Copying(done.toFloat() / total)
                        }
                    }
                }
            } ?: throw IOException("Не удалось открыть файл")
            if (!tmp.renameTo(dst)) throw IOException("Не удалось сохранить карту")
            dst.length()
        }.also {
            tmp.delete()
            st.value = null
            refresh(ctx, kind)
        }
    }

    fun delete(ctx: Context, kind: Kind) {
        file(ctx, kind).delete()
        refresh(ctx, kind)
    }
}
