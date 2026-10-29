package com.vabugeode.app.watch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.vabugeode.app.MainActivity
import com.vabugeode.app.R
import com.vabugeode.app.data.PhoneLocation
import com.vabugeode.app.data.SharedLinkPrefs
import com.vabugeode.app.data.Source
import com.vabugeode.app.protocol.BeaconMsg
import com.vabugeode.app.protocol.Codec
import com.vabugeode.app.protocol.Proto
import com.vabugeode.app.log.AppLog
import com.vabugeode.app.store.StoreDb
import com.vabugeode.app.store.saveBeacon
import com.vabugeode.app.transport.BleScanner
import com.vabugeode.app.ui.Fmt
import com.vabugeode.app.ui.screens.beaconSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Фоновое наблюдение (docs/gadget-logic.md, раздел 5): слушаем маячки выбранного гаджета,
 * держим в уведомлении сводку, тревоги — отдельными уведомлениями со звуком,
 * запоминаем, где был телефон, когда последний раз слышал гаджет.
 * Без подключения: гаджет не тратит заряд на связь.
 *
 * Оболочка XOS может убивать фон — проверить на телефоне (инструкция в настройках приложения).
 */
class WatchService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var scanner: BleScanner? = null
    private var gadgetId: String? = null
    private var lastHeardMs = 0L
    private var scanStartMs = 0L
    private var lastBeacon: BeaconMsg? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppLog.start(StoreDb.get(this).dao())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // intent == null — система сама перезапустила службу после того, как убила процесс
        if (intent == null) AppLog.w("WATCH", "служба перезапущена системой — телефон её убивал")
        if (intent?.action == ACTION_STOP) {
            WatchPrefs(this).enabled = false
            stopSelf()
            return START_NOT_STICKY
        }
        createChannels(this)
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, NOTIF_WATCH, watchNotification("Ищем гаджет…", null), type)

        val prefs = SharedLinkPrefs(this)
        val id = prefs.gadgetId
        if (prefs.source != Source.GADGET || id == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (scanner == null || gadgetId != id) {
            val prevBeat = getSharedPreferences("watch_diag", 0).getLong("beat", 0)
            if (prevBeat > 0 && System.currentTimeMillis() - prevBeat > 20 * 60_000) {
                AppLog.w("WATCH", "наблюдение не работало ${Fmt.duration(System.currentTimeMillis() - prevBeat)} (с ${Fmt.time(prevBeat)})")
            }
            AppLog.i("WATCH", "фоновое наблюдение запущено")
            scanner?.stop()
            gadgetId = id
            scanner = BleScanner(this, scope, ScanSettings.SCAN_MODE_BALANCED).also { s ->
                s.start()
                scanStartMs = System.currentTimeMillis()
                scope.launch { s.found.collect { all -> all[id]?.let { onBeacon(id, it.beacon, it.seenAtMs) } } }
                scope.launch { s.error.collect { e -> if (e != null) updateWatch("Поиск не работает: $e") } }
            }
            // Отметка «служба жива» раз в 5 мин: дыры в них — телефон усыплял или убивал приложение
            scope.launch {
                var last = System.currentTimeMillis()
                while (isActive) {
                    getSharedPreferences("watch_diag", 0).edit().putLong("beat", System.currentTimeMillis()).apply()
                    delay(5 * 60_000)
                    val gap = System.currentTimeMillis() - last
                    if (gap > 15 * 60_000) AppLog.w("WATCH", "служба простаивала ${Fmt.duration(gap)} — телефон её усыплял")
                    else AppLog.d("WATCH", "жива; маячок ${if (lastHeardMs > 0) "был в ${Fmt.timeSec(lastHeardMs)}" else "не слышен"}")
                    last = System.currentTimeMillis()
                }
            }
            // Давно не слышно — сказать об этом в уведомлении (забыли рюкзак?)
            scope.launch {
                while (isActive) {
                    delay(60_000)
                    val now = System.currentTimeMillis()
                    if (lastHeardMs > 0 && now - lastHeardMs > 10 * 60_000) {
                        updateWatch("Гаджет не слышно ${Fmt.duration(now - lastHeardMs)}")
                    }
                    // 10 мин тишины с последнего маячка или перезапуска — перезапустить поиск: система могла
                    // его тихо остановить (сон, перезапуск службы). Не чаще раза в 10 мин
                    if (now - maxOf(lastHeardMs, scanStartMs) > 10 * 60_000) {
                        AppLog.w("WATCH", "маячок не слышен 10 мин — перезапускаю поиск")
                        scanner?.stop()
                        scanner?.start()
                        scanStartMs = now
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun onBeacon(id: String, b: BeaconMsg, seenMs: Long) {
        if (seenMs == lastHeardMs) return
        if (lastHeardMs > 0 && seenMs - lastHeardMs > 5 * 60_000) {
            AppLog.i("WATCH", "маячок снова слышен после ${Fmt.duration(seenMs - lastHeardMs)} тишины")
        } else if (lastHeardMs == 0L) {
            AppLog.i("WATCH", "первый маячок: заряд ${b.battPct}%, режим ${b.mode}")
        }
        lastHeardMs = seenMs
        lastBeacon = b
        updateWatch(beaconSummary(b))
        // «Где гаджет»: телефон слышит маячок в радиусе ~10–30 м — значит, гаджет был рядом с телефоном
        val pos = PhoneLocation.last(this, maxAgeMs = 5 * 60_000)
        scope.launch(Dispatchers.IO) {
            StoreDb.get(this@WatchService).dao().saveBeacon(id, Codec.encodeBeacon(b), seenMs, pos?.lat, pos?.lon, pos?.accuracyM)
        }
        notifyAlerts(b.alerts)
    }

    /** Новые тревоги — уведомление со звуком; одну и ту же тревогу не повторяем, пока не ушла */
    private fun notifyAlerts(allAlerts: Int) {
        // Подмену не уведомляем: сделать с ней ничего нельзя, а отвлекает (её видно в приложении)
        val alerts = allAlerts and Proto.AL_SPOOF.inv()
        val p = WatchPrefs(this)
        val fresh = alerts and p.notifiedAlerts.inv()
        p.notifiedAlerts = alerts
        if (fresh == 0) return
        AppLog.w("WATCH", "тревога по маячку: 0x%02X".format(fresh))
        val text = buildList {
            if (fresh and Proto.AL_CRIT_BATT != 0) add("Критический заряд гаджета — запись почти остановлена")
            else if (fresh and Proto.AL_LOW_BATT != 0) add("Низкий заряд гаджета (< 15 %)")
            if (fresh and Proto.AL_SD_ERROR != 0) add("Ошибка карты SD — запись только в память")
        }.joinToString("\n")
        val n = NotificationCompat.Builder(this, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("vabuGEOde: тревога")
            .setContentText(text.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(openApp(this))
            .setAutoCancel(true)
            .build()
        notify(NOTIF_ALERT + fresh, n)
    }

    private fun updateWatch(text: String) = notify(NOTIF_WATCH, watchNotification(text, lastHeardMs.takeIf { it > 0 }))

    private fun notify(id: Int, n: Notification) {
        try {
            getSystemService(NotificationManager::class.java).notify(id, n)
        } catch (_: SecurityException) {
            // Нет разрешения на уведомления (Android 13+) — сервис работает, просто молча
        }
    }

    private fun watchNotification(text: String, heardMs: Long?): Notification =
        NotificationCompat.Builder(this, CH_WATCH)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("vabuGEOde — наблюдение")
            .setContentText(text)
            .setSubText(heardMs?.let { "маячок в ${Fmt.time(it)}" })
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openApp(this))
            .addAction(0, "Остановить", PendingIntent.getService(
                this, 1, Intent(this, WatchService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ))
            .build()

    override fun onDestroy() {
        AppLog.i("WATCH", "фоновое наблюдение остановлено")
        scanner?.stop()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CH_WATCH = "watch"
        const val CH_ALERTS = "alerts"
        private const val NOTIF_WATCH = 1
        private const val NOTIF_ALERT = 100
        private const val ACTION_STOP = "stop"

        fun createChannels(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CH_WATCH, "Наблюдение за гаджетом", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Постоянное уведомление со сводкой из маячка"
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(CH_ALERTS, "Тревоги гаджета", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Подмена GPS, низкий заряд, ошибка SD"
                }
            )
        }

        fun start(ctx: Context) {
            WatchPrefs(ctx).enabled = true
            ContextCompat.startForegroundService(ctx, Intent(ctx, WatchService::class.java))
        }

        fun stop(ctx: Context) {
            WatchPrefs(ctx).enabled = false
            ctx.stopService(Intent(ctx, WatchService::class.java))
        }

        private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

class WatchPrefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("watch", Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(v) = sp.edit().putBoolean("enabled", v).apply()
    var notifiedAlerts: Int
        get() = sp.getInt("notified", 0)
        set(v) = sp.edit().putInt("notified", v).apply()
}
