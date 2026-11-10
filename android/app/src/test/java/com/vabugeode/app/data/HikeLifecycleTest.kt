package com.vabugeode.app.data

import androidx.room.Room
import com.vabugeode.app.model.LinkState
import com.vabugeode.app.protocol.*
import com.vabugeode.app.store.*
import com.vabugeode.app.transport.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class HikeLifecycleTest {
    class Gadget : Transport {
        override val key="lifecycle"
        override val deviceName="test"
        override val state=MutableStateFlow(LinkState.DISCONNECTED)
        override val incoming=MutableSharedFlow<Packet>(extraBufferCapacity=100)
        var life=HikeStateMsg(HikeStateMsg.ACTIVE,255,7,100,0,0)
        var failSave=false
        var loseReply=false
        var actions=0
        var endedAt=200L
        override fun connect() { state.value=LinkState.CONNECTED }
        override fun disconnect() { state.value=LinkState.DISCONNECTED }
        suspend fun emit(m: DeviceMsg) { incoming.emit(Packet(Channel.EVENT,Codec.encode(m))) }
        suspend fun snapshot() {
            emit(InfoMsg(0,1,21,1,10,100,90,0,life.hike,emptyList()))
            emit(life.copy(result=255))
            val hex=Regex("\"status\": \"([0-9a-f]+)\"").find(File("../../docs/protocol_vectors.json").readText())!!.groupValues[1]
            val status=Codec.decodeMsg(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()) as StatusMsg
            emit(status.copy(flags=if(life.phase==1) Proto.F_RECORDING else 0))
        }
        override suspend fun send(data: ByteArray): Boolean {
            when (val c=Codec.decodeCmd(data)) {
                Cmd.GetInfo -> snapshot()
                is Cmd.SyncTrack -> emit(SyncDoneMsg(Proto.CMD_SYNC_TRACK,0))
                is Cmd.SyncMarks -> emit(SyncDoneMsg(Proto.CMD_SYNC_MARKS,0))
                is Cmd.SyncLog -> emit(SyncDoneMsg(Proto.CMD_SYNC_LOG,0))
                is Cmd.HikeAction -> {
                    actions++
                    val ok=!failSave && c.expectedHike==life.hike && c.revision==life.revision
                    if (ok) life=life.copy(phase=if(c.action==2) 3 else 1,
                        hike=if(c.action==1) life.hike+1 else life.hike,
                        revision=life.revision+1,endedAt=if(c.action==2) endedAt else 0,operation=c.operation)
                    if (!loseReply) {
                        snapshot() // the device sends INFO and a snapshot before the correlated result
                        emit(life.copy(result=if(ok) Proto.ACK_OK else Proto.ACK_BUSY,operation=c.operation))
                    }
                }
                else -> {}
            }
            return true
        }
    }

    @Test fun finishIsConfirmedAndDoesNotCreateANewHike()=runTest {
        val g=Gadget(); val r=ProtocolDeviceRepository(backgroundScope,g,emptyList(),elapsedMs={testScheduler.currentTime})
        runCurrent(); r.connect(); runCurrent()
        assertTrue(r.changeHike(HikeStateMsg.FINISH)); runCurrent()
        assertEquals(100L,r.lifecycle.value!!.hike)
        assertEquals(HikeStateMsg.ENDED,r.lifecycle.value!!.phase)
        assertFalse(r.lifecyclePending.value)
        r.disconnect(); runCurrent(); r.connect(); runCurrent()
        assertEquals(HikeStateMsg.ENDED,r.lifecycle.value!!.phase)
        assertEquals(1,g.actions)
        assertTrue(r.changeHike(HikeStateMsg.START)); runCurrent()
        assertEquals(101L,r.lifecycle.value!!.hike)
    }

    @Test fun saveFailureAndStaleDialogNeverReportCompletion()=runTest {
        val g=Gadget(); val r=ProtocolDeviceRepository(backgroundScope,g,emptyList(),elapsedMs={testScheduler.currentTime})
        runCurrent(); r.connect(); runCurrent()
        val old=r.lifecycle.value
        g.failSave=true
        assertFalse(r.changeHike(HikeStateMsg.FINISH))
        assertEquals(HikeStateMsg.ACTIVE,r.lifecycle.value!!.phase)
        g.life=g.life.copy(hike=101,revision=9); g.snapshot(); runCurrent()
        val count=g.actions
        assertFalse(r.changeHike(HikeStateMsg.FINISH,old))
        assertEquals(count,g.actions)
        // Corrupted storage cannot report its former revision; the error must still be visible.
        g.emit(HikeStateMsg(HikeStateMsg.ERROR,255,1,0,0,0)); runCurrent()
        assertEquals(HikeStateMsg.ERROR,r.lifecycle.value!!.phase)
    }

    @Test fun lostReplySurvivesAppRestartAndIsReconciledWithoutResend()=runTest {
        val db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),StoreDb::class.java)
            .allowMainThreadQueries().setQueryExecutor {it.run()}.setTransactionExecutor {it.run()}.build()
        try {
            val g=Gadget(); val r=ProtocolDeviceRepository(backgroundScope,g,emptyList(),db.dao(),elapsedMs={testScheduler.currentTime})
            runCurrent(); r.connect(); runCurrent(); g.loseReply=true
            assertFalse(r.changeHike(HikeStateMsg.FINISH))
            assertTrue(r.lifecyclePending.value)
            assertNotNull(db.dao().hikePending(g.key))
            assertEquals(HikeStateMsg.ENDED,g.life.phase) // committed device result; phone does not assume it
            r.disconnect(); runCurrent()
            val t=Gadget().also { it.life=g.life }
            val restored=ProtocolDeviceRepository(backgroundScope,t,emptyList(),db.dao(),elapsedMs={testScheduler.currentTime})
            runCurrent(); assertTrue(restored.lifecyclePending.value)
            restored.connect(); runCurrent()
            assertEquals(HikeStateMsg.ENDED,restored.lifecycle.value!!.phase)
            assertFalse(restored.lifecyclePending.value)
            assertNull(db.dao().hikePending(t.key)); assertEquals(0,t.actions)
            restored.disconnect(); runCurrent()
            val offline=ProtocolDeviceRepository(backgroundScope,Gadget(),emptyList(),db.dao())
            runCurrent(); assertEquals(HikeStateMsg.ENDED,offline.lifecycle.value!!.phase)
            assertFalse(offline.lifecycleFresh.value)
        } finally { db.close() }
    }

    @Test fun archivedCompletionDoesNotReplaceCurrentDeviceState()=runTest {
        val g=Gadget(); val r=ProtocolDeviceRepository(backgroundScope,g,emptyList(),elapsedMs={testScheduler.currentTime})
        runCurrent(); r.connect(); runCurrent()
        g.emit(HikeStateMsg(3,254,3,99,120,12)); runCurrent()
        assertEquals(100L,r.lifecycle.value!!.hike)
        assertEquals(HikeStateMsg.ACTIVE,r.lifecycle.value!!.phase)
    }

    @Test fun firmwareAndAndroidUseTheSameWireBytes() {
        val command=Cmd.HikeAction(2,100,7,44)
        val expected="021c0264000000070000002c000000".chunked(2).map {it.toInt(16).toByte()}.toByteArray()
        assertArrayEquals(expected,Codec.encode(command)); assertEquals(command,Codec.decodeCmd(expected))
        val message=HikeStateMsg(3,255,8,100,200,44)
        val bytes="020b03ff0800000064000000c80000002c000000".chunked(2).map {it.toInt(16).toByte()}.toByteArray()
        assertArrayEquals(bytes,Codec.encode(message)); assertEquals(message,Codec.decodeMsg(bytes))
        for (n in 2 until bytes.size) {
            try { Codec.decodeMsg(bytes.copyOf(n)); fail("accepted truncated packet") }
            catch (_:ProtocolException) {}
        }
    }
}
