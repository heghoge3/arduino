package com.vabugeode.app.data

import com.vabugeode.app.model.TrackPoint
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln

/** Условные пояснения за последние 30–60 минут; без непрерывных данных ничего не показываем. */
fun chartInsight(metric: String, track: List<TrackPoint>): String? {
    val sorted = track.distinctBy { it.tsUtcMs }.sortedBy { it.tsUtcMs }
    if (sorted.size < 7) return null
    val end = sorted.last().tsUtcMs
    val duration = if (end - sorted.first().tsUtcMs >= 3_600_000) 3_600_000L else 1_800_000L
    if (end - sorted.first().tsUtcMs < duration) return null
    val points = sorted.filter { it.tsUtcMs >= end - duration }
    if (points.size < 7 || points.first().tsUtcMs > end - duration + 120_000 ||
        points.zipWithNext().any { (a, b) -> b.tsUtcMs - a.tsUtcMs > 300_000 }) return null
    fun stats(value: (TrackPoint) -> Double, valid: (Double) -> Boolean = { it.isFinite() }): Trend? {
        val good = points.mapNotNull { p -> value(p).takeIf(valid)?.let { p.tsUtcMs to it } }
        if (good.size < 7 || good.size < points.size * 0.8 ||
            good.zipWithNext().any { (a, b) -> b.first - a.first > 300_000 }) return null
        val first = good.filter { it.first <= end - duration + 300_000 }.map { it.second }
        val last = good.filter { it.first >= end - 300_000 }.map { it.second }
        if (first.size < 2 || last.size < 2) return null
        return Trend(first.average(), last.average(), good.maxOf { it.second } - good.minOf { it.second })
    }
    val t = stats({ it.tempCorr }, { it.isFinite() && it in -60.0..85.0 })
    val raw = stats({ it.tempRaw }, { it.isFinite() && it in -60.0..85.0 })
    val p = stats({ it.pressureHpa }, { it.isFinite() && it in 300.0..1100.0 })
    val h = stats({ it.humidity }, { it.isFinite() && it in 1.0..100.0 })
    val a = stats({ it.altBaro })
    val v = stats({ if (it.battV in 0.5..6.0) it.battV else Double.NaN })
    val b = stats({ if (it.battV in 0.5..6.0) it.battPercent.toDouble() else Double.NaN })
    val period = if (duration == 3_600_000L) "За последний час" else "За последние 30 минут"
    fun f(x: Double) = String.format(Locale("ru"), "%.1f", abs(x))
    val text = when (metric) {
        "TEMP" -> when {
            t == null -> null
            raw != null && abs(raw.last - t.last) >= 1.5 -> "Поправка отличается от датчика на ${f(raw.last - t.last)} °C и требует калибровки в корпусе."
            t.first > 0 && t.last <= 0 -> "Температура датчика пересекла 0 °C. На мокрых поверхностях возможно обледенение; состояние дороги датчик не определяет."
            t.delta <= -2 && h != null && h.delta >= 5 -> "Температура снизилась на ${f(t.delta)} °C, влажность выросла на ${f(h.delta)} п.п. Воздух у датчика приблизился к насыщению."
            t.delta >= 2 && h != null && h.delta <= -5 -> "Температура выросла на ${f(t.delta)} °C, влажность снизилась. Так бывает и при нагреве корпуса, а не только при смене погоды."
            t.delta <= -2 -> "Датчик остыл на ${f(t.delta)} °C. Возможны похолодание или остывание корпуса после помещения."
            t.delta >= 2 -> "Датчик нагрелся на ${f(t.delta)} °C. Проверьте влияние руки, солнца и электроники."
            t.last >= 30 && h != null && h.last >= 70 -> "У датчика жарко и влажно. Внутри корпуса условия могут отличаться от воздуха снаружи."
            t.range <= 0.5 -> "Температура почти стабильна: разброс ${f(t.range)} °C. Это не подтверждает точность измерения наружного воздуха."
            else -> null
        }
        "PRESSURE" -> when {
            p == null -> null
            a != null && abs(a.delta) >= 30 && abs(p.delta) >= 1 -> "Давление изменилось на ${f(p.delta)} гПа вместе с барометрической высотой. По этому сочетанию нельзя отличить подъём/спуск от смены погоды."
            p.delta <= -1 && t != null && t.delta >= 1 -> "Давление упало на ${f(p.delta)} гПа, температура выросла на ${f(t.delta)} °C. При неизменной высоте возможна смена воздушной массы; осадки не гарантированы."
            p.delta <= -1 && t != null && t.delta <= -1 -> "Давление и температура снизились. При неизменной высоте возможна смена погоды, но по одному часу нельзя предсказать дождь."
            p.delta <= -2 -> "Давление заметно упало: на ${f(p.delta)} гПа. Если высота не менялась, следите за погодой и прогнозом."
            p.delta <= -1 -> "Давление снизилось на ${f(p.delta)} гПа. Причиной может быть подъём или изменение погоды."
            p.delta >= 1 -> "Давление выросло на ${f(p.delta)} гПа. Возможны спуск или изменение погоды; это не обещание ясного неба."
            p.range <= 0.4 -> "Давление почти не менялось: разброс ${f(p.range)} гПа. Выраженного тренда на этом участке нет."
            else -> null
        }
        "HUMIDITY" -> when {
            h == null -> null
            h.last >= 90 && t != null && dewGap(t.last, h.last) <= 2 -> "Влажность близка к насыщению, до точки росы около ${f(dewGap(t.last, h.last))} °C. На более холодных поверхностях возможен конденсат."
            h.last >= 80 && h.delta >= 5 -> "Влажность достигла ${f(h.last)} % и растёт. Проверьте, не намокло ли отверстие датчика."
            h.last <= 25 -> "У датчика сухо: около ${f(h.last)} %. Нагрев корпуса также снижает относительную влажность."
            h.delta >= 10 && t != null && t.delta <= -1 -> "Влажность выросла на ${f(h.delta)} п.п. на фоне охлаждения. Это не обязательно означает появление новой влаги."
            h.delta <= -10 && t != null && t.delta >= 1 -> "Влажность снизилась на ${f(h.delta)} п.п. при нагреве. Это может быть эффектом температуры корпуса."
            h.delta >= 10 -> "Влажность выросла на ${f(h.delta)} п.п. Возможны более влажный воздух, охлаждение или влага возле датчика."
            h.delta <= -10 -> "Влажность снизилась на ${f(h.delta)} п.п. Сравните с температурой: нагрев способен дать такой эффект."
            else -> null
        }
        "ALTITUDE" -> when {
            a == null -> null
            points.zipWithNext().any { (x, y) -> x.altBaro.isFinite() && y.altBaro.isFinite() && abs(y.altBaro - x.altBaro) > 100 && y.tsUtcMs - x.tsUtcMs <= 120_000 } -> "Есть скачок барометрической высоты более 100 м за две минуты. Возможны быстрый подъём/спуск или изменение условий у датчика."
            abs(a.delta) >= 20 && points.last().steps == points.first().steps -> "Высота изменилась на ${f(a.delta)} м без зарегистрированных шагов. Возможны изменение давления, транспорт или пропуск шагов."
            a.delta >= 80 -> "Барометрическая высота выросла примерно на ${f(a.delta)} м. Это разница начала и конца участка, а не сумма всех подъёмов."
            a.delta <= -80 -> "Барометрическая высота снизилась примерно на ${f(a.delta)} м. Это относительная оценка по давлению."
            a.delta >= 20 -> "Возможен подъём примерно на ${f(a.delta)} м. Погода тоже смещает барометрическую высоту."
            a.delta <= -20 -> "Возможен спуск примерно на ${f(a.delta)} м. Без калибровки абсолютная отметка ненадёжна."
            a.range <= 5 -> "Барометрическая высота почти стабильна: разброс ${f(a.range)} м. Мелкие колебания не стоит считать отдельными подъёмами."
            else -> null
        }
        "BATTERY" -> when {
            b == null || v == null -> null
            v.last <= 3.4 -> "Напряжение около ${f(v.last)} В. Заряд близок к нижней части диапазона; проверьте питание под нагрузкой GPS."
            b.last <= 15 -> "Оценка заряда — ${f(b.last)} %. Подготовьте резервное питание; процент по напряжению приблизительный."
            b.delta <= -5 && t != null && t.last <= 0 -> "Оценка заряда снизилась на ${f(b.delta)} п.п. на холоде. Часть снижения может быть просадкой напряжения."
            b.delta <= -20 -> "Оценка заряда упала на ${f(b.delta)} п.п. Проверьте нагрузку и просадку напряжения; прогноз автономности по этому участку ненадёжен."
            b.delta <= -5 -> "Оценка заряда снизилась на ${f(b.delta)} п.п. Это не точное измерение потраченной ёмкости."
            b.delta >= 5 -> "Оценка заряда выросла на ${f(b.delta)} п.п. Возможны зарядка, прогрев или восстановление напряжения после нагрузки."
            b.last >= 95 && v.last >= 4.1 -> "Напряжение близко к верхней части диапазона Li-ion. Окончание зарядки по одному графику определить нельзя."
            else -> null
        }
        else -> null
    }
    return text?.let { "$period: ${it.replaceFirstChar { c -> c.lowercase() }}" }
}

private data class Trend(val first: Double, val last: Double, val range: Double) { val delta get() = last - first }
private fun dewGap(temp: Double, humidity: Double): Double {
    val gamma = ln(humidity / 100) + 17.62 * temp / (243.12 + temp)
    return temp - 243.12 * gamma / (17.62 - gamma)
}
