package com.vabugeode.app.data

import com.vabugeode.app.model.LinkState
import com.vabugeode.app.protocol.*
import com.vabugeode.app.transport.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CompassSessionTest {
    private class Gadget(val patch: Int) : Transport {
        override val key = "gadget"
        override val deviceName = "test"
        override val state = MutableStateFlow(LinkState.DISCONNECTED)
        override val incoming = MutableSharedFlow<Packet>(extraBufferCapacity = 100)
        val sent = mutableListOf<Cmd>()
        var code = 3
        var navCode = 14
        override fun connect() { state.value = LinkState.CONNECTED }
        override fun disconnect() { state.value = LinkState.DISCONNECTED }
        private suspend fun info() { incoming.emit(Packet(Channel.EVENT, Codec.encode(
            InfoMsg(0, 1, patch, 1, 10, 100, 90, 0, 1793000000, listOf(CheckMsg(4, if (code == 0) 0 else 1, code, 0x2C)) + if (patch >= 15) listOf(CheckMsg(10, if (navCode == 0) 0 else 1, navCode, 0)) else emptyList()),
        ))) }
        override suspend fun send(data: ByteArray): Boolean {
            delay(1)
            val c = Codec.decodeCmd(data); sent += c
            when (c) {
                Cmd.GetInfo -> info()
                is Cmd.CalibCompass -> { code = if (c.start) 13 else 0; info() }
                else -> {}
            }
            return true
        }
    }
    @Test fun settingsSentOnConnectAndCalibrationStateComesFromDevice() = runTest {
        val g = Gadget(14)
        val r = ProtocolDeviceRepository(backgroundScope, g, emptyList())
        r.setPdrSettings(82, -900, true)
        r.phonePosition = { Triple(60.0, 30.0, 100) }
        r.connect(); advanceTimeBy(1000)
        assertTrue(g.sent.contains(Cmd.PdrSettings(82, -900, true)))
        assertEquals(3, r.compassState.value)
        r.calibrateCompass(true); advanceTimeBy(100)
        assertEquals(13, r.compassState.value)
        r.calibrateCompass(false); advanceTimeBy(100)
        assertEquals(0, r.compassState.value)
        assertTrue(g.sent.last { it is Cmd.PhonePos } == Cmd.PhonePos(60.0, 30.0, 100))
        r.disconnect(); advanceTimeBy(100)
        g.sent.clear(); r.connect(); advanceTimeBy(1000)
        assertTrue(g.sent.contains(Cmd.PdrSettings(82, -900, true)))
    }
    @Test fun automaticNavigationReadinessComesFromNewFirmwareAndClearsOnSourceChange() = runTest {
        val g = Gadget(15)
        val r = ProtocolDeviceRepository(backgroundScope, g, emptyList())
        r.connect(); advanceTimeBy(1000)
        assertEquals(14, r.navigationState.value)
        g.navCode = 0
        r.refreshInfo(); advanceTimeBy(100)
        assertEquals(0, r.navigationState.value)
        r.use(NoTransport)
        assertNull(r.navigationState.value)
    }
    @Test fun oldFirmwareDoesNotReceiveUnsupportedSettings() = runTest {
        val g = Gadget(13)
        val r = ProtocolDeviceRepository(backgroundScope, g, emptyList())
        r.connect(); advanceTimeBy(1000)
        r.setPdrSettings(82, 0, false)
        r.calibrateCompass(true); advanceTimeBy(100)
        assertFalse(g.sent.any { it is Cmd.PdrSettings || it is Cmd.CalibCompass })
    }
}
