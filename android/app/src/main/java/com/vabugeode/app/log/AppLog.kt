package com.vabugeode.app.log

import android.util.Log
import com.vabugeode.app.store.AppLogRow
import com.vabugeode.app.store.StoreDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Журнал приложения: подключения, обрывы, ошибки Bluetooth, работа в фоне.
 * Пишется в базу пачками в своём потоке — вызывать можно откуда угодно, без задержек.
 * Уровни как у гаджета: 0 ошибка, 1 внимание, 2 инфо, 3 отладка.
 */
object AppLog {
    const val ERR = 0
    const val WARN = 1
    const val INFO = 2
    const val DBG = 3

    private const val KEEP = 5000
    private val queue = Channel<AppLogRow>(capacity = 1000)
    @Volatile private var started = false

    /** Подробные строки (DBG) пишутся только в отладочном режиме */
    @Volatile var debug = true

    fun start(dao: StoreDao) {
        if (started) return
        started = true
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            var sinceTrim = 0
            while (true) {
                val batch = ArrayList<AppLogRow>()
                batch += queue.receive()
                while (batch.size < 200) batch += queue.tryReceive().getOrNull() ?: break
                runCatching { dao.putAppLog(batch) }
                sinceTrim += batch.size
                if (sinceTrim > 500) {
                    sinceTrim = 0
                    runCatching { dao.trimAppLog(KEEP) }
                }
            }
        }
    }

    fun e(tag: String, text: String) = add(ERR, tag, text)
    fun w(tag: String, text: String) = add(WARN, tag, text)
    fun i(tag: String, text: String) = add(INFO, tag, text)
    fun d(tag: String, text: String) = add(DBG, tag, text)

    fun log(level: Int, tag: String, text: String) = add(level, tag, text)

    private fun add(level: Int, tag: String, text: String) {
        if (level == DBG && !debug) return
        val prio = when (level) { ERR -> Log.ERROR; WARN -> Log.WARN; INFO -> Log.INFO; else -> Log.DEBUG }
        runCatching { Log.println(prio, "vabu.$tag", text) }
        queue.trySend(AppLogRow(timeMs = System.currentTimeMillis(), level = level, tag = tag, text = text))
    }
}
