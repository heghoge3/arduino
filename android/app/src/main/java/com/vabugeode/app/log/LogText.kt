package com.vabugeode.app.log

import android.os.Build
import com.vabugeode.app.BuildConfig
import com.vabugeode.app.store.AppLogRow
import com.vabugeode.app.store.GadgetLogRow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Журналы гаджета и телефона в одном виде: для экрана и для файла «поделиться». */
object LogText {
    /** Модули прошивки по номеру (docs/protocol.md, LOG) */
    val MODULES = listOf("SYS", "PWR", "GPS", "SPOOF", "SENS", "SD", "RTC", "BLE", "UI", "CMD")

    data class Line(
        val timeMs: Long,         // 0 — время на гаджете было неизвестно
        val gadget: Boolean,
        val level: Int,
        val tag: String,
        val text: String,
        val timeOk: Boolean = true,
        val uptimeSec: Long? = null,
        val key: String,
    )

    fun levelChar(level: Int) = "EWID".getOrElse(level) { '?' }

    fun fromGadget(r: GadgetLogRow) = Line(
        r.timeMs, true, r.level, MODULES.getOrElse(r.module) { "M${r.module}" }, r.text, r.timeOk, r.uptimeSec,
        key = "g${r.device}#${r.seq}",
    )

    fun fromApp(r: AppLogRow) = Line(r.timeMs, false, r.level, r.tag, r.text, key = "a${r.id}")

    /** Обе ленты вместе, новые сверху */
    fun merge(g: List<GadgetLogRow>, a: List<AppLogRow>): List<Line> =
        (g.map(::fromGadget) + a.map(::fromApp)).sortedByDescending { it.timeMs }

    private val full = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** «2026-10-31 17:03:22 Г W GPS фикс потерян»; Г — гаджет, Т — телефон; «?» — время гаджета не проверено */
    fun format(l: Line, zone: ZoneId = ZoneId.systemDefault()): String {
        val ts = if (l.timeMs > 0) full.format(Instant.ofEpochMilli(l.timeMs).atZone(zone)) + (if (l.timeOk) "" else "?")
        else "---- +${l.uptimeSec ?: 0}с"
        return "$ts ${if (l.gadget) "Г" else "Т"} ${levelChar(l.level)} ${l.tag} ${l.text}"
    }

    fun export(g: List<GadgetLogRow>, a: List<AppLogRow>, zone: ZoneId = ZoneId.systemDefault()): String = buildString {
        appendLine("Журнал vabuGEOde")
        appendLine("Приложение ${BuildConfig.VERSION_NAME}, ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
        appendLine("Выгружено ${full.format(Instant.now().atZone(zone))} ($zone)")
        appendLine("Г — гаджет, Т — телефон; E ошибка, W внимание, I инфо, D отладка; «?» — время гаджета не проверено")
        appendLine("Строк: гаджет ${g.size}, телефон ${a.size}")
        appendLine()
        merge(g, a).asReversed().forEach { appendLine(format(it, zone)) }
    }
}
