package com.vabugeode.app.data

import com.vabugeode.app.model.*
import com.vabugeode.app.protocol.*
import com.vabugeode.app.transport.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EconomySettingsTest {
    private class Gadget(val patch:Int=16):Transport {
        override val key="economy"
        override val deviceName="test"
        override val state=MutableStateFlow(LinkState.DISCONNECTED)
        override val incoming=MutableSharedFlow<Packet>(extraBufferCapacity=100)
        var offset=-350
        val commands=mutableListOf<Cmd>()
        override fun connect(){state.value=LinkState.CONNECTED}
        override fun disconnect(){state.value=LinkState.DISCONNECTED}
        suspend fun emit(m:DeviceMsg){incoming.emit(Packet(Channel.EVENT,Codec.encode(m)))}
        override suspend fun send(data:ByteArray):Boolean {
            delay(1)
            val c=Codec.decodeCmd(data);commands+=c
            when(c) {
                Cmd.GetInfo -> emit(InfoMsg(0,1,patch,1,20,7600,7500,0,1793000000,emptyList(),tempOffset100=if(patch>=16)offset else null,strideCm=if(patch>=16)75 else null))
                is Cmd.TemperatureOffset -> {offset=c.hundredths;emit(AckMsg(Proto.CMD_TEMP_OFFSET,Proto.ACK_OK))}
                is Cmd.SetStart -> emit(AckMsg(Proto.CMD_SET_START,Proto.ACK_OK))
                else -> {}
            }
            return true
        }
    }
    @Test fun readsCorrectionFromDeviceAndChangesOnlyAfterConfirmation()=runTest {
        val g=Gadget();val r=ProtocolDeviceRepository(backgroundScope,g,emptyList())
        r.connect();advanceTimeBy(1000)
        assertEquals(-350,r.temperatureOffset100.value)
        assertTrue(r.setTemperatureOffset(-420))
        assertEquals(-420,g.offset)
        r.disconnect();advanceTimeBy(50);r.connect();advanceTimeBy(1000)
        assertEquals(-420,r.temperatureOffset100.value)
        assertTrue(g.commands.any {it is Cmd.Blacklist && it.explicitRadius})
        assertTrue(r.setStart(60.0,30.0))
        assertTrue(g.commands.contains(Cmd.SetStart(1793000000,60.0,30.0)))
    }
    @Test fun oldFirmwareDoesNotReceiveNewConfiguration()=runTest {
        val g=Gadget(15);val r=ProtocolDeviceRepository(backgroundScope,g,emptyList())
        r.connect();advanceTimeBy(1000)
        assertFalse(r.setTemperatureOffset(-420))
        assertFalse(r.setStart(60.0,30.0))
        assertEquals(-350,g.offset)
    }
    @Test fun delayedSyncCannotOverwriteManualStartAtTheSameSecond()=runTest {
        val g=Gadget();val r=ProtocolDeviceRepository(backgroundScope,g,emptyList())
        r.connect();advanceTimeBy(1000)
        val anchor=PointMsg(1793000000,60.0,30.0,0,false,0,null,null,20.0,20.0,50.0,1000.0,0.0,null,4000,80,0,null,0,Double.NaN,0.0,true)
        g.emit(anchor);advanceTimeBy(200)
        assertEquals(PositionSource.START,r.track.value.single().src)
        g.emit(anchor.copy(startAnchor=false));advanceTimeBy(200)
        assertEquals(PositionSource.START,r.track.value.single().src)
    }
}
