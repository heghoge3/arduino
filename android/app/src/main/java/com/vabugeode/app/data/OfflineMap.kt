package com.vabugeode.app.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Офлайн-карта: один файл .pmtiles региона (выжимка Protomaps из OpenStreetMap).
 * Пользователь выбирает файл в телефоне, мы копируем его к себе в память приложения.
 * Стиль и шрифты лежат в APK (assets/map, assets/fonts), интернет не нужен.
 */
object OfflineMap {

    /** Что сейчас с картой: файла нет / копируем (доля 0..1) / есть (размер в байтах) */
    sealed interface State {
        data object None : State
        data class Copying(val fraction: Float) : State
        data class Ready(val bytes: Long) : State
    }

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state

    private fun file(ctx: Context) = File(File(ctx.filesDir, "maps"), "region.pmtiles")

    fun refresh(ctx: Context) {
        if (_state.value is State.Copying) return
        val f = file(ctx)
        _state.value = if (f.isFile && f.length() > 0) State.Ready(f.length()) else State.None
    }

    fun available(ctx: Context): Boolean = file(ctx).let { it.isFile && it.length() > 0 }

    /** Стиль с путём к файлу карты; null — файла нет, показываем онлайн-карту */
    fun styleJson(ctx: Context): String? {
        val f = file(ctx)
        if (!f.isFile) return null
        val template = ctx.assets.open("map/style.json").bufferedReader().use { it.readText() }
        return template.replace("__PMTILES__", "pmtiles://file://" + f.absolutePath)
    }

    /** Скопировать выбранный файл. Сначала во временный, чтобы обрыв не оставил битую карту */
    suspend fun import(ctx: Context, uri: Uri): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = ctx.contentResolver
            val total = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length }?.takeIf { it > 0 } ?: -1L
            val dst = file(ctx)
            dst.parentFile?.mkdirs()
            val tmp = File(dst.parentFile, "region.tmp")
            _state.value = State.Copying(0f)
            resolver.openInputStream(uri)?.use { input ->
                // Заголовок PMTiles v3: «PMTiles» + байт версии 3
                val head = ByteArray(8)
                if (input.read(head) != 8 || String(head, 0, 7, Charsets.US_ASCII) != "PMTiles" || head[7].toInt() != 3) {
                    throw IOException("Это не файл карты .pmtiles (нужна версия 3)")
                }
                tmp.outputStream().use { out ->
                    out.write(head)
                    val buf = ByteArray(1 shl 16)
                    var done = 8L
                    var lastShown = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0 && done - lastShown > (1 shl 22)) {
                            lastShown = done
                            _state.value = State.Copying(done.toFloat() / total)
                        }
                    }
                }
            } ?: throw IOException("Не удалось открыть файл")
            if (!tmp.renameTo(dst)) throw IOException("Не удалось сохранить карту")
            dst.length()
        }.also { r ->
            File(file(ctx).parentFile, "region.tmp").delete()
            _state.value = null
            refresh(ctx)
        }
    }

    fun delete(ctx: Context) {
        file(ctx).delete()
        refresh(ctx)
    }
}
