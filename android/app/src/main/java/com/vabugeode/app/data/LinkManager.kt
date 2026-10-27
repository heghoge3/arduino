package com.vabugeode.app.data

import com.vabugeode.app.log.AppLog
import android.content.Context
import com.vabugeode.app.model.LinkState
import com.vabugeode.app.transport.FoundGadget
import com.vabugeode.app.transport.GadgetScanner
import com.vabugeode.app.transport.NoTransport
import com.vabugeode.app.transport.Transport
import com.vabugeode.app.transport.VIRTUAL_ID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Откуда берём данные. */
enum class Source(val title: String) { EMULATOR("Эмулятор"), GADGET("Гаджет") }

/** Где хранить выбор (SharedPreferences в приложении, память — в тестах). */
interface LinkPrefs {
    var source: Source
    var gadgetId: String?
    var gadgetName: String?
}

class SharedLinkPrefs(context: Context) : LinkPrefs {
    private val sp = context.getSharedPreferences("link", Context.MODE_PRIVATE)
    override var source: Source
        get() = runCatching { Source.valueOf(sp.getString("source", Source.EMULATOR.name)!!) }.getOrDefault(Source.EMULATOR)
        set(v) = sp.edit().putString("source", v.name).apply()
    override var gadgetId: String?
        get() = sp.getString("gadget", null)
        set(v) = sp.edit().putString("gadget", v).apply()
    override var gadgetName: String?
        get() = sp.getString("gadgetName", null)
        set(v) = sp.edit().putString("gadgetName", v).apply()
}

/**
 * Менеджер связи (docs/gadget-logic.md, раздел 5): пока приложение открыто — слушаем маячки
 * и сами подключаемся к выбранному гаджету, как только он в эфире.
 * Пользователь нажал «Отключить» — не переподключаемся, пока не попросит.
 */
class LinkManager(
    private val scope: CoroutineScope,
    private val repo: ProtocolDeviceRepository,
    private val prefs: LinkPrefs,
    private val virtualScanner: GadgetScanner,
    private val virtualTransport: Transport,
    private val bleScanner: () -> GadgetScanner,
    private val bleTransport: (id: String, name: String) -> Transport,
) {
    val source = MutableStateFlow(prefs.source)
    val selectedId = MutableStateFlow(if (prefs.source == Source.EMULATOR) VIRTUAL_ID else prefs.gadgetId)
    val selectedName = MutableStateFlow(if (prefs.source == Source.EMULATOR) "Эмулятор" else prefs.gadgetName)

    private val _scanner = MutableStateFlow(if (prefs.source == Source.EMULATOR) virtualScanner else bleScanner())
    val scanner: StateFlow<GadgetScanner> = _scanner

    /** Последняя сводка выбранного гаджета из маячка — видна и без подключения */
    val lastBeacon = MutableStateFlow<FoundGadget?>(null)

    private var foreground = false
    private var userDisconnected = false
    private var backgroundJob: kotlinx.coroutines.Job? = null

    init {
        repo.use(
            when {
                prefs.source == Source.EMULATOR -> virtualTransport
                prefs.gadgetId != null -> bleTransport(prefs.gadgetId!!, prefs.gadgetName ?: "vabuGEOde")
                else -> NoTransport
            }
        )
        scope.launch {
            // Слушаем маячки текущего сканера и подключаемся к выбранному
            // collectLatest: сменили сканер или гаджет — старая подписка отменяется
            combine(_scanner, selectedId) { s, id -> s to id }.collectLatest { (s, id) ->
                s.found.collect { all ->
                    val g = id?.let { all[it] } ?: return@collect
                    lastBeacon.value = g
                    maybeConnect()
                }
            }
        }
    }

    /**
     * Приложение на экране (true) или свёрнуто (false).
     * Свернули: через 30 с — тихий сеанс, через 2 мин — BYE (gadget-logic.md, раздел 5).
     */
    fun setForeground(on: Boolean) {
        AppLog.d("APP", if (on) "приложение на экране" else "приложение свёрнуто")
        foreground = on
        repo.foreground = on
        backgroundJob?.cancel()
        if (on) {
            userDisconnected = false
            repo.setLive(true)
            _scanner.value.start()
            maybeConnect()
        } else {
            _scanner.value.stop()
            backgroundJob = scope.launch {
                kotlinx.coroutines.delay(QUIET_AFTER_MS)
                AppLog.d("LINK", "тихий сеанс (свёрнуто 30 с)")
                repo.setLive(false)
                kotlinx.coroutines.delay(BYE_AFTER_MS - QUIET_AFTER_MS)
                AppLog.d("LINK", "BYE (свёрнуто 2 мин)")
                repo.bye()
            }
        }
    }

    private fun maybeConnect() {
        if (!foreground || userDisconnected) return
        if (selectedId.value == null || repo.link.value != LinkState.DISCONNECTED) return
        repo.connect()
    }

    fun setSource(s: Source) {
        if (s == source.value) return
        AppLog.i("LINK", "источник: ${if (s == Source.EMULATOR) "эмулятор" else "гаджет"}")
        _scanner.value.stop()
        prefs.source = s
        source.value = s
        lastBeacon.value = null
        if (s == Source.EMULATOR) {
            selectedId.value = VIRTUAL_ID
            selectedName.value = "Эмулятор"
            repo.use(virtualTransport)
            _scanner.value = virtualScanner
        } else {
            selectedId.value = prefs.gadgetId
            selectedName.value = prefs.gadgetName
            repo.use(prefs.gadgetId?.let { bleTransport(it, prefs.gadgetName ?: "vabuGEOde") } ?: NoTransport)
            _scanner.value = bleScanner()
        }
        if (foreground) _scanner.value.start()
        maybeConnect()
    }

    /** Выбрать гаджет из найденных и подключиться */
    fun choose(g: FoundGadget) {
        AppLog.i("LINK", "выбран гаджет ${g.name} ${g.id}, сигнал ${g.rssi} дБм")
        prefs.gadgetId = g.id
        prefs.gadgetName = g.name
        selectedId.value = g.id
        selectedName.value = g.name
        lastBeacon.value = g
        repo.use(bleTransport(g.id, g.name))
        userDisconnected = false
        repo.connect()
    }

    fun forget() {
        AppLog.i("LINK", "гаджет забыт")
        prefs.gadgetId = null
        prefs.gadgetName = null
        selectedId.value = null
        selectedName.value = null
        lastBeacon.value = null
        repo.use(NoTransport)
    }

    fun connectNow() {
        if (selectedId.value == null) return
        userDisconnected = false
        if (!_scanner.value.scanning.value) _scanner.value.start()
        repo.connect()
    }

    fun disconnectByUser() {
        AppLog.i("LINK", "отключено пользователем")
        userDisconnected = true
        // BYE: гаджет поймёт, что отключили нарочно, и не будет держать окно переподключения
        if (repo.link.value == LinkState.CONNECTED) repo.bye() else repo.disconnect()
    }

    /** Перезапустить поиск (после выдачи разрешений или включения Bluetooth) */
    fun restartScan() {
        _scanner.value.stop()
        if (foreground) _scanner.value.start()
    }

    companion object {
        const val QUIET_AFTER_MS = 30_000L
        const val BYE_AFTER_MS = 120_000L
    }
}
