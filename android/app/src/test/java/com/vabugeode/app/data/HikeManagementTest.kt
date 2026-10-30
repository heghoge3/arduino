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
class HikeManagementTest {
    private class Gadget : Transport {
        override val key = "gadget"
        override val deviceName = "test"
        override val state = MutableStateFlow(LinkState.DISCONNECTED)
        override val incoming = MutableSharedFlow<Packet>(extraBufferCapacity = 100)
        var active = 1793000000L
        val files = mutableSetOf(active)
        var dropResult = false
        var failDelete = false
        override fun connect() { state.value = LinkState.CONNECTED }
        override fun disconnect() { state.value = LinkState.DISCONNECTED }
        private suspend fun emit(m: DeviceMsg) { incoming.emit(Packet(Channel.EVENT, Codec.encode(m))) }
        private suspend fun info() = emit(InfoMsg(0, 1, 13, 1, 10, 100, 90, 0, active, emptyList()))
        override suspend fun send(data: ByteArray): Boolean {
            delay(1)
            when (val c = Codec.decodeCmd(data)) {
                Cmd.GetInfo -> info()
                is Cmd.SyncTrack -> emit(SyncDoneMsg(Proto.CMD_SYNC_TRACK, 0))
                is Cmd.SyncMarks -> emit(SyncDoneMsg(Proto.CMD_SYNC_MARKS, 0))
                is Cmd.NewHike -> {
                    val result = if (c.expectedHike != active) Proto.ACK_BUSY else {
                        active++; files += active; Proto.ACK_OK
                    }
                    if (!dropResult) emit(HikeResultMsg(Proto.CMD_NEW_HIKE, result, c.expectedHike))
                    info()
                }
                is Cmd.DeleteHike -> {
                    val result = if (c.hike == active || failDelete) Proto.ACK_BUSY else {
                        files -= c.hike; Proto.ACK_OK
                    }
                    if (!dropResult) emit(HikeResultMsg(Proto.CMD_DELETE_HIKE, result, c.hike))
                }
                else -> {}
            }
            return true
        }
    }

    @Test fun startsNewBeforeDeletingOldAndProtectsActive() = runTest {
        val g = Gadget()
        val r = ProtocolDeviceRepository(backgroundScope, g, emptyList())
        r.connect(); advanceTimeBy(1000)
        val old = g.active
        assertFalse(r.manageHike(old, delete = true))
        assertTrue(g.files.contains(old))
        assertTrue(r.manageHike(old, delete = false))
        advanceTimeBy(1000)
        assertEquals((old + 1) * 1000, r.hikeStartMs.value)
        assertTrue(r.manageHike(old, delete = true))
        assertFalse(g.files.contains(old))
        assertTrue(g.files.contains(g.active))
    }

    @Test fun sdFailureAndMissingConfirmationAreNotSuccess() = runTest {
        val g = Gadget()
        val r = ProtocolDeviceRepository(backgroundScope, g, emptyList())
        r.connect(); advanceTimeBy(1000)
        val old = g.active - 60
        g.files += old
        g.failDelete = true
        assertFalse(r.manageHike(old, delete = true))
        assertTrue(g.files.contains(old))
        g.failDelete = false; g.dropResult = true
        assertFalse(r.manageHike(old, delete = true))
        assertFalse(r.hikeBusy.value)
        g.dropResult = false
        assertTrue(r.manageHike(old, delete = true)) // повтор безопасен, файлы уже удалены
    }
}
