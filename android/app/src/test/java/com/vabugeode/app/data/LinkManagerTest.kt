package com.vabugeode.app.data

import com.vabugeode.app.demo.DemoDevice
import com.vabugeode.app.model.LinkState
import com.vabugeode.app.protocol.BeaconMsg
import com.vabugeode.app.transport.FoundGadget
import com.vabugeode.app.transport.GadgetScanner
import com.vabugeode.app.transport.NoTransport
import com.vabugeode.app.transport.Packet
import com.vabugeode.app.transport.Transport
import com.vabugeode.app.transport.VIRTUAL_ID
import com.vabugeode.app.transport.VirtualScanner
import com.vabugeode.app.transport.VirtualTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Менеджер связи: маячок выбранного гаджета → подключение; «Отключить» не переподключает. */
@OptIn(ExperimentalCoroutinesApi::class)
class LinkManagerTest {

    private class MemPrefs(
        override var source: Source = Source.EMULATOR,
        override var gadgetId: String? = null,
        override var gadgetName: String? = null,
    ) : LinkPrefs

    private class FakeScanner : GadgetScanner {
        override val found = MutableStateFlow<Map<String, FoundGadget>>(emptyMap())
        override val scanning = MutableStateFlow(false)
        override val error = MutableStateFlow<String?>(null)
        override fun start() { scanning.value = true }
        override fun stop() { scanning.value = false }
        fun hear(id: String) {
            val b = BeaconMsg(1, 0, 0, 5.0, 80, 7, 1010.0, 0, 1, 0)
            found.value = found.value + (id to FoundGadget(id, "vabuGEOde", -60, b, System.currentTimeMillis()))
        }
    }

    /** Гаджет, который только считает попытки подключения */
    private class FakeTransport(override val deviceName: String) : Transport {
        override val key = deviceName
        override val state = MutableStateFlow(LinkState.DISCONNECTED)
        override val incoming = MutableSharedFlow<Packet>()
        var connects = 0
        override fun connect() { connects++; state.value = LinkState.CONNECTING }
        override fun disconnect() { state.value = LinkState.DISCONNECTED }
        override suspend fun send(data: ByteArray) = false
    }

    private class Rig(scope: TestScope, prefs: MemPrefs) {
        val dev = DemoDevice(scope.backgroundScope)
        val virtual = VirtualTransport(scope.backgroundScope, dev)
        val repo = ProtocolDeviceRepository(scope.backgroundScope, virtual, emptyList())
        val ble = FakeScanner()
        val transports = mutableMapOf<String, FakeTransport>()
        val link = LinkManager(
            scope.backgroundScope, repo, prefs,
            VirtualScanner(scope.backgroundScope, dev) { repo.currentTransport === virtual && repo.link.value != LinkState.DISCONNECTED },
            virtual, { ble }, { id, name -> FakeTransport(name).also { transports[id] = it } },
        )
    }

    @Test
    fun emulatorConnectsByBeacon() = runTest {
        val r = Rig(this, MemPrefs())
        advanceTimeBy(1000)
        assertEquals("без экрана не подключаемся", LinkState.DISCONNECTED, r.repo.link.value)
        r.link.setForeground(true)
        advanceTimeBy(5000)
        assertEquals(LinkState.CONNECTED, r.repo.link.value)
        assertEquals(VIRTUAL_ID, r.link.lastBeacon.value?.id)
        assertTrue("история не догрузилась", r.repo.track.value.isNotEmpty())
    }

    @Test
    fun userDisconnectIsRespected() = runTest {
        val r = Rig(this, MemPrefs())
        r.link.setForeground(true)
        advanceTimeBy(5000)
        r.link.disconnectByUser()
        advanceTimeBy(10_000) // маячки идут, но мы не переподключаемся
        assertEquals(LinkState.DISCONNECTED, r.repo.link.value)
        r.link.connectNow()
        advanceTimeBy(5000)
        assertEquals(LinkState.CONNECTED, r.repo.link.value)
    }

    @Test
    fun backgroundGoesQuietThenBye() = runTest {
        val r = Rig(this, MemPrefs())
        r.link.setForeground(true)
        advanceTimeBy(5000)
        assertEquals(LinkState.CONNECTED, r.repo.link.value)
        r.link.setForeground(false)
        advanceTimeBy(LinkManager.QUIET_AFTER_MS + 2000)
        // Тихий сеанс: STATUS больше не идут
        val seq = r.repo.stats.value.lastSeq
        advanceTimeBy(10_000)
        assertEquals("в тихом сеансе STATUS не должен идти", seq, r.repo.stats.value.lastSeq)
        assertEquals(LinkState.CONNECTED, r.repo.link.value)
        // Через 2 мин — BYE и отключение; маячки не слушаем — само не переподключается
        advanceTimeBy(LinkManager.BYE_AFTER_MS)
        assertEquals(LinkState.DISCONNECTED, r.repo.link.value)
        // Вернулись — снова подключились и живой сеанс
        r.link.setForeground(true)
        advanceTimeBy(8000)
        assertEquals(LinkState.CONNECTED, r.repo.link.value)
        val s1 = r.repo.stats.value.lastSeq
        advanceTimeBy(3000)
        assertTrue("живой сеанс не возобновился", (r.repo.stats.value.lastSeq ?: 0) > (s1 ?: 0))
    }

    @Test
    fun gadgetSelectedAndAutoConnectOnBeacon() = runTest {
        val prefs = MemPrefs(Source.GADGET, "AA:BB:CC:DD:EE:01", "vabuGEOde")
        val r = Rig(this, prefs)
        advanceTimeBy(100)
        val t = r.transports["AA:BB:CC:DD:EE:01"]!!
        assertSame(t, r.repo.currentTransport)
        r.link.setForeground(true)
        assertTrue(r.ble.scanning.value)
        // Чужой гаджет в эфире — не трогаем
        r.ble.hear("11:22:33:44:55:66")
        advanceTimeBy(100)
        assertEquals(1, t.connects) // setForeground уже попробовал один раз
        t.state.value = LinkState.DISCONNECTED
        r.ble.hear("AA:BB:CC:DD:EE:01")
        advanceTimeBy(100)
        assertEquals("маячок своего гаджета → подключение", 2, t.connects)
        // Свернули приложение — поиск остановлен
        r.link.setForeground(false)
        assertTrue(!r.ble.scanning.value)
    }

    @Test
    fun chooseAndForget() = runTest {
        val prefs = MemPrefs(Source.GADGET)
        val r = Rig(this, prefs)
        advanceTimeBy(100)
        assertSame(NoTransport, r.repo.currentTransport)
        r.ble.hear("AA:BB:CC:DD:EE:02")
        r.link.choose(r.ble.found.value.getValue("AA:BB:CC:DD:EE:02"))
        assertEquals("AA:BB:CC:DD:EE:02", prefs.gadgetId)
        assertEquals(1, r.transports.getValue("AA:BB:CC:DD:EE:02").connects)
        r.link.forget()
        assertSame(NoTransport, r.repo.currentTransport)
        assertEquals(null, prefs.gadgetId)
        // Обратно на эмулятор
        r.link.setSource(Source.EMULATOR)
        assertSame(r.virtual, r.repo.currentTransport)
    }
}
