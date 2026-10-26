package com.vabugeode.app.data

import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Тайлы спутника из MBTiles через маленький HTTP-сервер на 127.0.0.1.
 * Зачем: в выгрузках бывают дыры (на z17 скачалась только часть). Карта сама не берёт
 * снимок уровнем ниже — там, где тайла нет, получается пустота. Здесь вместо пустого тайла
 * отдаём увеличенный кусок «родителя» (до 6 уровней вверх).
 */
object SatTiles {

    private const val MAX_UP = 6

    @Volatile private var server: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(4)
    private var dbFile: File? = null
    private var dbStamp = 0L
    private var db: SQLiteDatabase? = null
    private val parents = LruCache<String, Bitmap>(24)

    /** Адрес тайлов для стиля карты; поднимает сервер при первом вызове */
    @Synchronized
    fun urlTemplate(file: File): String {
        open(file)
        val s = server ?: ServerSocket(0, 16, InetAddress.getByName("127.0.0.1")).also { srv ->
            server = srv
            Thread({ acceptLoop(srv) }, "sat-tiles").apply { isDaemon = true }.start()
        }
        // Метка файла в адресе: заменили файл — карта не возьмёт старые тайлы из кэша
        return "http://127.0.0.1:${s.localPort}/$dbStamp/{z}/{x}/{y}"
    }

    @Synchronized
    private fun open(file: File) {
        val stamp = file.lastModified() xor file.length()
        if (db != null && dbFile == file && dbStamp == stamp) return
        db?.close()
        parents.evictAll()
        dbFile = file
        dbStamp = stamp
        // Только чтение и свой обработчик ошибок — стандартный удаляет «повреждённый» файл
        db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY) { }
    }

    @Synchronized
    fun close() {
        db?.close()
        db = null
        dbFile = null
        parents.evictAll()
    }

    /** Тайл в схеме XYZ (как просит карта); в MBTiles строки снизу вверх */
    private fun raw(z: Int, x: Int, y: Int): ByteArray? {
        val d = synchronized(this) { db } ?: return null
        val row = (1 shl z) - 1 - y
        return runCatching {
            d.rawQuery(
                "SELECT tile_data FROM tiles WHERE zoom_level = ? AND tile_column = ? AND tile_row = ?",
                arrayOf(z.toString(), x.toString(), row.toString()),
            ).use { c -> if (c.moveToFirst()) c.getBlob(0) else null }
        }.getOrNull()
    }

    /** Тайл или увеличенный кусок ближайшего предка; null — снимка нет совсем */
    fun tileOrParent(z: Int, x: Int, y: Int): ByteArray? {
        raw(z, x, y)?.let { return it }
        for (up in 1..MAX_UP) {
            val pz = z - up
            if (pz < 0) break
            val px = x shr up
            val py = y shr up
            val key = "$pz/$px/$py"
            val src = parents.get(key) ?: raw(pz, px, py)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }?.also { parents.put(key, it) }
                ?: continue
            val part = src.width shr up
            if (part < 1) return null
            val ox = (x - (px shl up)) * part
            val oy = (y - (py shl up)) * part
            val crop = Bitmap.createBitmap(src, ox, oy, part, part)
            val scaled = Bitmap.createScaledBitmap(crop, src.width, src.height, true)
            return ByteArrayOutputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
                out.toByteArray()
            }
        }
        return null
    }

    private fun acceptLoop(srv: ServerSocket) {
        while (!srv.isClosed) {
            val sock = runCatching { srv.accept() }.getOrNull() ?: continue
            pool.execute { handle(sock) }
        }
    }

    private fun handle(sock: Socket) = sock.use { s ->
        runCatching {
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.US_ASCII))
            val request = reader.readLine() ?: return@use
            while (true) { val h = reader.readLine(); if (h.isNullOrEmpty()) break }
            // GET /<метка>/<z>/<x>/<y> HTTP/1.1
            val parts = request.split(" ").getOrNull(1)?.trim('/')?.split("/")
            val z = parts?.getOrNull(1)?.toIntOrNull()
            val x = parts?.getOrNull(2)?.toIntOrNull()
            val y = parts?.getOrNull(3)?.substringBefore('.')?.toIntOrNull()
            val body = if (z != null && x != null && y != null) tileOrParent(z, x, y) else null
            val out = s.getOutputStream()
            if (body == null) {
                out.write("HTTP/1.1 204 No Content\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".toByteArray())
            } else {
                val type = if (body.size > 1 && body[0] == 0x89.toByte()) "image/png" else "image/jpeg"
                out.write(
                    ("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
                        "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(),
                )
                out.write(body)
            }
            out.flush()
        }
    }
}
