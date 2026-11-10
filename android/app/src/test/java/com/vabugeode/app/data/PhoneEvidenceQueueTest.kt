package com.vabugeode.app.data
import com.vabugeode.app.protocol.*
import com.vabugeode.app.transport.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
@OptIn(ExperimentalCoroutinesApi::class)
class PhoneEvidenceQueueTest {
 @Test fun locationThatAgesInQueueIsNotSentAsFresh()=runTest {
   val gadget=HikeLifecycleTest.Gadget()
   val sent=mutableListOf<Cmd>()
   val wire=object:Transport by gadget {
     override suspend fun send(data:ByteArray):Boolean {
       val cmd=Codec.decodeCmd(data)
       if(cmd==Cmd.Find)delay(10000)
       sent+=cmd;return gadget.send(data)
     }
   }
   val r=ProtocolDeviceRepository(backgroundScope,wire,emptyList(),elapsedMs={testScheduler.currentTime})
   runCurrent();r.connect();runCurrent()
   gadget.emit(InfoMsg(0,1,25,1,1,100,90,0,100,emptyList()));runCurrent()
   r.phonePosition={PhoneLocation.Fix(60.0,30.0,20,25000,1)}
   r.findDevice();runCurrent();r.setRecording(true)
   advanceTimeBy(11000);runCurrent()
   assertTrue(sent.none{it is Cmd.PhoneEvidence})
 }
}
