package com.vabugeode.app.data

import com.vabugeode.app.demo.DemoDevice
import com.vabugeode.app.model.LinkState
import com.vabugeode.app.model.MarkType
import com.vabugeode.app.transport.VirtualTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Сквозная проверка «бэкенда»: виртуальное устройство → байты → репозиторий.
 * Время виртуальное: минуты похода проходят за миллисекунды.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VirtualLinkTest {

    private class Rig(scope: TestScope) {
        val dev = DemoDevice(scope.backgroundScope)
        val transport = VirtualTransport(scope.backgroundScope, dev)
        val repo = ProtocolDeviceRepository(scope.backgroundScope, transport, emptyList())
    }

    /** Трек в приложении совпадает с «SD-картой» устройства: все точки, без дублей. */
    private fun Rig.assertTrackInSync() {
        val times = repo.track.value.map { it.tsUtcMs }
        assertEquals("дубли точек", times.size, times.toSet().size)
        assertEquals("точки не по порядку", times.sorted(), times)
        // За последнюю секунду устройство могло записать ещё одну — она придёт со следующим тиком
        assertTrue("в приложении ${times.size}, на устройстве ${dev.pointCount}", dev.pointCount - times.size in 0..1)
    }

    @Test
    fun connectSyncsHistory() = runTest {
        val r = Rig(this)
        r.repo.connect()
        advanceTimeBy(5_000)
        assertEquals(LinkState.CONNECTED, r.repo.link.value)
        assertTrue("история не пришла", r.repo.track.value.size > 500)
        r.assertTrackInSync()
        assertEquals(r.dev.markCount, r.repo.marks.value.size)
        assertEquals(r.dev.hikeStartUtcSec * 1000, r.repo.hikeStartMs.value)
        assertTrue(r.repo.status.value != null)
        assertEquals(9, r.repo.info.value!!.checks.size)
        assertEquals(0L, r.repo.stats.value.errors)
    }

    @Test
    fun reconnectFetchesOnlyMissed() = runTest {
        val r = Rig(this)
        r.repo.connect()
        advanceTimeBy(5_000)
        val inBefore = r.repo.stats.value.packetsIn

        r.repo.disconnect()
        advanceTimeBy(20_000) // 10 минут похода без связи
        val missed = r.dev.pointCount - r.repo.track.value.size
        assertTrue("за время без связи точек не прибавилось", missed > 20)

        r.repo.connect()
        advanceTimeBy(5_000)
        r.assertTrackInSync()
        // Догрузили только пропущенное, а не всю историю заново
        val received = r.repo.stats.value.packetsIn - inBefore
        assertTrue("пакетов $received, пропущено $missed", received < missed + 40)
    }

    @Test
    fun markCommandRoundTrip() = runTest {
        val r = Rig(this)
        r.repo.connect()
        advanceTimeBy(5_000)
        val before = r.repo.marks.value.size
        r.repo.addMark(MarkType.FOUND, "Тест метки")
        r.repo.clap()
        advanceTimeBy(1_000)
        val marks = r.repo.marks.value
        assertEquals(before + 2, marks.size)
        assertEquals("Тест метки", marks[marks.size - 2].text)
        assertEquals(MarkType.SYNC, marks.last().type)
    }

    @Test
    fun recordingStopsAndResumes() = runTest {
        val r = Rig(this)
        r.repo.connect()
        advanceTimeBy(5_000)
        r.repo.setRecording(false)
        advanceTimeBy(1_500)
        assertEquals(false, r.repo.status.value!!.recording)
        val n = r.dev.pointCount
        advanceTimeBy(10_000)
        assertEquals("запись на паузе, а точки пишутся", n, r.dev.pointCount)
        r.repo.setRecording(true)
        advanceTimeBy(10_000)
        assertTrue(r.dev.pointCount > n)
    }

    @Test
    fun newHikeReplacesOldData() = runTest {
        val r = Rig(this)
        r.repo.connect()
        advanceTimeBy(5_000)
        val oldStart = r.repo.hikeStartMs.value
        val oldSize = r.repo.track.value.size

        r.dev.restart(historyMin = 30) // устройство начало новый поход
        advanceTimeBy(6_000)
        assertEquals(LinkState.CONNECTED, r.repo.link.value)
        assertTrue("старый трек не очищен", r.repo.track.value.size < oldSize)
        r.assertTrackInSync()
        assertNotEquals("приложение не заметило новый поход", oldStart, r.repo.hikeStartMs.value)
        assertEquals(r.dev.hikeStartUtcSec * 1000, r.repo.hikeStartMs.value)
    }
}
