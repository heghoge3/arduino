package com.vabugeode.app.data

import com.vabugeode.app.model.LinkState
import com.vabugeode.app.model.MarkType
import com.vabugeode.app.protocol.*
import com.vabugeode.app.transport.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class StatusFreshnessTest {
    private class Gadget(override val key: String = "freshness") : Transport {
        override val deviceName = "test"
        override val state = MutableStateFlow(LinkState.DISCONNECTED)
        override val incoming = MutableSharedFlow<Packet>(extraBufferCapacity = 10)
        val sent = mutableListOf<Cmd>()
        var gate: CompletableDeferred<Unit>? = null
        override fun connect() { state.value = LinkState.CONNECTED }
        override fun disconnect() { state.value = LinkState.DISCONNECTED }
        override suspend fun send(data: ByteArray): Boolean {
            gate?.await()
            sent += Codec.decodeCmd(data)
            return true
        }
        suspend fun packet(name: String) {
            val json = File("../../docs/protocol_vectors.json").readText()
            val hex = Regex("\"$name\": \"([0-9a-f]+)\"").find(json)!!.groupValues[1]
            incoming.emit(Packet(if (name.startsWith("status")) Channel.STATUS else Channel.EVENT,
                hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()))
        }
        fun controls() = sent.filter { it is Cmd.Record || it is Cmd.Mark || it == Cmd.Clap }
    }

    @Test fun connectionAndHistoryCannotMakeCachedStatusLive() = runTest {
        val g = Gadget()
        val r = ProtocolDeviceRepository(backgroundScope, g, emptyList(), elapsedMs = { testScheduler.currentTime })
        runCurrent(); r.connect(); runCurrent()
        assertFalse(r.statusFresh.value)
        g.packet("status"); runCurrent()
        assertTrue(r.statusFresh.value)
        r.disconnect(); runCurrent()
        assertFalse(r.statusFresh.value)
        assertNotNull(r.status.value) // readings remain available offline
        r.addMark(MarkType.FOUND, "offline"); r.clap(); r.setRecording(false); runCurrent()
        assertTrue(g.controls().isEmpty())
        r.connect(); runCurrent()
        g.packet("info"); g.packet("point_spoof"); runCurrent()
        assertFalse(r.statusFresh.value)
        r.setRecording(false); runCurrent()
        assertTrue(g.controls().isEmpty())
        g.packet("status"); runCurrent()
        r.setRecording(false); r.clap(); r.addMark(MarkType.FOUND, "live"); runCurrent()
        assertEquals(3, g.controls().size)
    }

    @Test fun statusExpiresDespiteOtherPacketsAndBecomesFreshAgain() = runTest {
        val g = Gadget()
        var elapsed = 100L
        val r = ProtocolDeviceRepository(backgroundScope, g, emptyList(), elapsedMs = { elapsed })
        runCurrent(); r.connect(); runCurrent(); g.packet("status"); runCurrent()
        elapsed += ProtocolDeviceRepository.STATUS_FRESH_MS
        g.packet("info"); runCurrent()
        // Command guard uses the clock directly, even before the UI's next tick.
        r.clap(); runCurrent(); assertTrue(g.controls().isEmpty())
        advanceTimeBy(1000); runCurrent(); assertFalse(r.statusFresh.value)
        g.packet("status"); runCurrent(); assertTrue(r.statusFresh.value)
        r.clap(); runCurrent(); assertEquals(1, g.controls().size)
        r.use(Gadget("other")); runCurrent()
        assertFalse(r.statusFresh.value); assertNull(r.status.value)
    }

    @Test fun queuedActionDoesNotCrossReconnect() = runTest {
        val g = Gadget()
        val r = ProtocolDeviceRepository(backgroundScope, g, emptyList(), elapsedMs = { testScheduler.currentTime })
        runCurrent(); r.connect(); runCurrent(); g.packet("status"); runCurrent()
        val gate = CompletableDeferred<Unit>()
        g.gate = gate
        r.refreshInfo(); runCurrent() // hold sendLock with a harmless query
        r.clap(); runCurrent()
        r.disconnect(); runCurrent(); r.connect(); runCurrent()
        g.packet("status"); runCurrent()
        gate.complete(Unit); runCurrent()
        assertTrue(g.controls().isEmpty())
    }
}
