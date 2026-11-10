package com.vabugeode.app.demo

import com.vabugeode.app.data.ProtocolDeviceRepository
import com.vabugeode.app.protocol.*
import com.vabugeode.app.transport.VirtualTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class V2EmulatorTest {
    private class Disk: EmulatorStorage {
        var bytes:ByteArray?=null
        var fail=false
        override fun load()=bytes?.copyOf()
        override fun save(bytes:ByteArray):Boolean {if(fail)return false;this.bytes=bytes.copyOf();return true}
    }
    private class Rig(val scope:TestScope,val disk:Disk=Disk()) {
        val device=DemoDevice(scope.backgroundScope,storage=disk)
        val transport=VirtualTransport(scope.backgroundScope,device)
        val repo=ProtocolDeviceRepository(scope.backgroundScope,transport,emptyList(),elapsedMs={scope.testScheduler.currentTime})
        fun connect(){repo.connect();scope.advanceTimeBy(5000);scope.runCurrent()}
        suspend fun start(){assertTrue(repo.changeHike(HikeStateMsg.START));scope.advanceTimeBy(3000);scope.runCurrent()}
    }
    @Test fun idleExplicitStartFinishAndNextStartUseRealProtocol()=runTest {
        val r=Rig(this);r.connect()
        assertEquals(0,r.repo.lifecycle.value!!.phase);assertEquals(0,r.device.pointCount)
        r.start();val id=r.device.hikeStartUtcSec
        advanceTimeBy(5000);runCurrent();assertTrue(r.device.pointCount>0)
        assertTrue(r.repo.changeHike(HikeStateMsg.FINISH));val n=r.device.pointCount
        advanceTimeBy(5000);runCurrent()
        assertEquals(n,r.device.pointCount);assertEquals(id,r.device.hikeStartUtcSec)
        assertEquals(3,r.repo.lifecycle.value!!.phase)
        r.start();assertTrue(r.device.hikeStartUtcSec>id)
        assertEquals(3,r.device.archivedEnd(id)!!.phase)
    }
    @Test fun rebootAndSdErrorDoNotSilentlyCloseOrStart()=runTest {
        val r=Rig(this);r.connect();r.start();val id=r.device.hikeStartUtcSec
        r.device.reboot();advanceTimeBy(5000);runCurrent()
        assertEquals(2,r.repo.lifecycle.value!!.phase);assertEquals(id,r.device.hikeStartUtcSec)
        val n=r.device.pointCount;advanceTimeBy(3000);runCurrent();assertEquals(n,r.device.pointCount)
        r.device.setSdFailure(true)
        assertFalse(r.repo.changeHike(HikeStateMsg.FINISH));assertEquals(2,r.device.lifecycle.value.phase)
        r.device.setSdFailure(false);advanceTimeBy(2000);runCurrent()
        assertTrue(r.repo.changeHike(HikeStateMsg.RESUME));assertEquals(id,r.device.hikeStartUtcSec)
    }
    @Test fun lostReplyIsReconciledWithoutAnotherTransition()=runTest {
        val r=Rig(this);r.connect();r.start()
        r.device.loseNextReply.value=true
        assertFalse(r.repo.changeHike(HikeStateMsg.FINISH))
        assertTrue(r.repo.lifecyclePending.value);assertEquals(3,r.device.lifecycle.value.phase)
        val revision=r.device.lifecycle.value.revision
        r.repo.refreshInfo();advanceTimeBy(1000);runCurrent()
        assertFalse(r.repo.lifecyclePending.value);assertEquals(3,r.repo.lifecycle.value!!.phase)
        assertEquals(revision,r.device.lifecycle.value.revision)
    }
    @Test fun persistentDeviceSurvivesPhoneRestartAndRefusesCorruption()=runTest {
        val disk=Disk();val d=DemoDevice(backgroundScope,storage=disk)
        assertEquals(Proto.ACK_OK,d.handle(Cmd.HikeAction(1,0,d.lifecycle.value.revision,22)))
        val id=d.hikeStartUtcSec
        val restored=DemoDevice(backgroundScope,storage=disk)
        assertEquals(id,restored.hikeStartUtcSec);assertEquals(1,restored.lifecycle.value.phase)
        restored.reboot();assertEquals(2,restored.lifecycle.value.phase)
        disk.bytes!![8]=(disk.bytes!![8].toInt() xor 1).toByte()
        val broken=DemoDevice(backgroundScope,storage=disk)
        assertEquals(4,broken.lifecycle.value.phase)
        assertEquals(Proto.ACK_BUSY,broken.handle(Cmd.HikeAction(1,0,1,33)))
    }
    @Test fun persistenceFailureAndRepeatedCommandNeverCreateSecondHike()=runTest {
        val disk=Disk();val d=DemoDevice(backgroundScope,storage=disk)
        val command=Cmd.HikeAction(1,0,1,9)
        disk.fail=true;assertEquals(Proto.ACK_BUSY,d.handle(command));assertEquals(4,d.lifecycle.value.phase)
        disk.fail=false;d.reboot();assertEquals(Proto.ACK_OK,d.handle(command));val id=d.hikeStartUtcSec
        assertEquals(Proto.ACK_OK,d.handle(command));assertEquals(id,d.hikeStartUtcSec)
        assertEquals(Proto.ACK_BUSY,d.handle(command.copy(operation=10)))
    }
    @Test fun checkpointPreservesRecordedWirePackets()=runTest {
        val disk=Disk();val d=DemoDevice(backgroundScope,storage=disk)
        d.restart(historyMin=30,live=false);assertTrue(d.checkpoint())
        val saved=d.recorded()
        val restored=DemoDevice(backgroundScope,storage=disk).recorded()
        assertEquals(saved.first.size,restored.first.size)
        for((a,b) in saved.first.zip(restored.first))assertArrayEquals(Codec.encode(a),Codec.encode(b))
        for((a,b) in saved.second.zip(restored.second))assertArrayEquals(Codec.encode(a),Codec.encode(b))
    }
    @Test fun shutdownWithAndWithoutFinishPreservesCorrectState()=runTest {
        val d=DemoDevice(backgroundScope)
        d.handle(Cmd.HikeAction(1,0,1,9));val id=d.hikeStartUtcSec
        assertTrue(d.shutdown(false));assertFalse(d.powered.value);d.reboot()
        assertEquals(2,d.lifecycle.value.phase);assertEquals(id,d.hikeStartUtcSec)
        assertTrue(d.shutdown(true));d.reboot();assertEquals(3,d.lifecycle.value.phase)
    }
}
