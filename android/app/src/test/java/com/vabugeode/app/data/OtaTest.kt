package com.vabugeode.app.data
import com.vabugeode.app.protocol.*
import com.vabugeode.app.ota.*
import org.junit.Test
import org.junit.Assert.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import java.io.File
import java.io.ByteArrayOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
class OtaTest {
 private val vectors=File("../../docs/ota-vectors.txt").readLines().map{it.chunked(2).map{b->b.toInt(16).toByte()}.toByteArray()}
 private fun bundle()=OtaBundle(vectors[1],vectors[2],vectors[0])
 @Test fun signatureHashAndGoldenStatusAgree(){
  val b=bundle();val s=OtaWire.decode(vectors[3]);assertEquals("0.1.29",s.version);assertEquals("0.1.30",b.version)
  assertTrue(s.compatible(b));assertTrue(s.eligible);assertEquals(0x11eL,b.release)
  for(part in 0..2){val m=vectors[1].copyOf();val image=vectors[2].copyOf();val key=vectors[0].copyOf()
   when(part){0->m[135]=(m[135].toInt() xor 1).toByte();1->image[20]=(image[20].toInt() xor 1).toByte();else->key[key.lastIndex]=(key.last().toInt() xor 1).toByte()}
   assertTrue(runCatching{OtaBundle(m,image,key)}.isFailure)
  }
 }
 @Test fun ao3401AssetRequiresMatchingWiringRevision(){
  val folder=File("src/main/assets/firmware")
  val b=OtaBundle(File(folder,"manifest.bin").readBytes(),File(folder,"app.bin").readBytes(),File(folder,"public.der").readBytes())
  assertEquals(2,b.manifest[6].toInt())
  val old=OtaWire.decode(vectors[3]).copy(keyId=b.manifest.copyOfRange(64,72))
  assertFalse(old.compatible(b))
  val ao=old.copy(metadata=old.metadata.copyOf().also{it[9]=2})
  assertTrue(ao.compatible(b))
  val previous=bundle()
  assertFalse(ao.copy(keyId=previous.manifest.copyOfRange(64,72)).compatible(previous))
 }
 @Test fun wireDataCrcOffsetAndBounds(){
  val data=OtaWire.data(42,123,byteArrayOf(1,2,3));assertEquals(42,OtaWire.u32(data,2));assertEquals(123,OtaWire.u32(data,6));assertEquals(0x55bc801d,OtaWire.u32(data,10))
  assertTrue(runCatching{OtaWire.data(1,0,ByteArray(225))}.isFailure)
  assertTrue(runCatching{OtaWire.decode(vectors[3].copyOf(142))}.isFailure)
 }
 private inner class Peer(val scope:TestScope){
  lateinit var client:OtaClient
  var connected=true;var eligible=true;var confirm=true;var dropDataAck=false;var backgroundAtData=false
  var ready=true;var expireStatusAtData=false
  var release=0x11dL;var phase=4;var op=0L;var offset=0;var beginCount=0;var abortCount=0
  var currentHash=ByteArray(32){0x42};val writes=ByteArrayOutputStream();val journal=mutableMapOf<String,String?>()
  private fun caps(request:Long):OtaStatus{
   val base=OtaWire.decode(vectors[3]);val meta=base.metadata.copyOf();java.nio.ByteBuffer.wrap(meta).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(16,release.toInt());meta[22]=(release and 255).toByte()
   return base.copy(request=request,operation=op,phase=phase,flags=if(eligible)63 else 59,metadata=meta,offset=offset.toLong(),imageHash=currentHash,
     pendingHash=if(phase==2||phase==3)bundle().hash else ByteArray(32),bootState=if(phase==3)1 else 2)
  }
  fun newClient():OtaClient=OtaClient(scope.backgroundScope,send={send(it)},connected={connected},ready={ready}).also{
   client=it;it.bundle=bundle();it.journal=object:OtaJournal{override fun read(device:String)=journal[device];override fun write(device:String,value:String?){journal[device]=value}}
   it.deviceChanged("test-device")
  }
  suspend fun send(packet:ByteArray):Boolean{
   val code=packet[1].toInt();val request=OtaWire.u32(packet,2)
   when(code){
    OtaWire.BEGIN->{op=request;phase=1;offset=0;writes.reset();beginCount++}
    OtaWire.DATA->{val at=OtaWire.u32(packet,6).toInt();if(at==offset){writes.write(packet,14,packet.size-14);offset+=packet.size-14}
     if(expireStatusAtData)ready=false
     if(backgroundAtData){client.background();return true}
     if(dropDataAck){dropDataAck=false;return true}}
    OtaWire.FINISH->phase=2
    OtaWire.ABORT->{phase=4;abortCount++}
   }
   client.receive(caps(request))
   if(code==OtaWire.REBOOT){phase=3;if(confirm){release=bundle().release;phase=4;currentHash=bundle().hash}}
   return true
  }
 }
 @Test fun successfulUpdateRequiresActualValidHash()=runTest {
  val peer=Peer(this);val client=peer.newClient();client.check();runCurrent();assertTrue(client.state.value.canUpdate)
  client.start();runCurrent();assertEquals(100,client.state.value.progress);assertTrue(client.state.value.busy)
  advanceTimeBy(1600);runCurrent();assertTrue(client.state.value.text.startsWith("Обновление подтверждено"));assertFalse(client.busy)
  assertArrayEquals(bundle().image,peer.writes.toByteArray());assertNull(peer.journal["test-device"])
 }
 @Test fun lostDataAckRetriesIdenticalChunk()=runTest {
  val peer=Peer(this);peer.dropDataAck=true;val c=peer.newClient();c.check();runCurrent();c.start();runCurrent()
  advanceTimeBy(5500);runCurrent();advanceTimeBy(2000);runCurrent()
  assertArrayEquals(bundle().image,peer.writes.toByteArray());assertEquals(1,peer.beginCount)
 }
 @Test fun backgroundCancelsPartialTransferWithoutSuccess()=runTest {
  val peer=Peer(this);peer.backgroundAtData=true;val c=peer.newClient();c.check();runCurrent();c.start();runCurrent()
  assertFalse(c.busy);assertFalse(c.state.value.text.startsWith("Обновление подтверждено"));assertEquals(1,peer.abortCount);assertEquals(0x11dL,peer.release)
 }
 @Test fun freshOtaAcksKeepTransferAliveIfOrdinaryStatusExpires()=runTest {
  val peer=Peer(this);peer.expireStatusAtData=true;val c=peer.newClient();c.check();runCurrent();c.start();runCurrent()
  advanceTimeBy(1600);runCurrent()
  assertTrue(c.state.value.text.startsWith("Обновление подтверждено"));assertArrayEquals(bundle().image,peer.writes.toByteArray())
 }
 @Test fun pendingFirstBootCannotBeReportedAsSuccess()=runTest {
  val peer=Peer(this);peer.confirm=false;val c=peer.newClient();c.check();runCurrent();c.start();runCurrent();advanceTimeBy(3000);runCurrent()
  assertFalse(c.state.value.text.startsWith("Обновление подтверждено"));assertNotNull(peer.journal["test-device"]);c.interrupt();runCurrent()
 }
 @Test fun restartRecoversJournalAndUsesDeviceConfirmation()=runTest {
  val peer=Peer(this);peer.confirm=false;var c=peer.newClient();c.check();runCurrent();c.start();runCurrent();c.interrupt();runCurrent()
  peer.phase=4;peer.release=bundle().release;peer.currentHash=bundle().hash
  c=peer.newClient();c.check();runCurrent();assertTrue(c.state.value.text.startsWith("Обновление подтверждено"));assertNull(peer.journal["test-device"])
 }
 @Test fun ineligibleDeviceCannotStart()=runTest {
  val peer=Peer(this);peer.eligible=false;val c=peer.newClient();c.check();runCurrent();assertFalse(c.state.value.canUpdate);c.start();runCurrent();assertEquals(0,peer.beginCount)
 }
 @Test fun journalFailurePreventsBegin()=runTest {
  val peer=Peer(this);val c=peer.newClient();c.check();runCurrent()
  c.journal=object:OtaJournal{override fun read(device:String):String?=null;override fun write(device:String,value:String?){error("Storage unavailable")}}
  c.start();runCurrent();assertFalse(c.busy);assertEquals(0,peer.beginCount);assertFalse(c.state.value.text.startsWith("Обновление подтверждено"))
 }
 @Test fun confirmedRollbackAllowsExplicitRetry()=runTest {
  val peer=Peer(this);peer.confirm=false;var c=peer.newClient();c.check();runCurrent();c.start();runCurrent();c.interrupt();runCurrent()
  peer.phase=5;c=peer.newClient();c.check();runCurrent()
  assertTrue(c.state.value.canUpdate);assertFalse(c.state.value.text.startsWith("Обновление подтверждено"));assertNull(peer.journal["test-device"])
 }
 @Test fun matchingVersionWithoutMatchingHashIsNotSuccess()=runTest {
  val peer=Peer(this);peer.confirm=false;var c=peer.newClient();c.check();runCurrent();c.start();runCurrent();c.interrupt();runCurrent()
  peer.phase=4;peer.release=bundle().release;c=peer.newClient();c.check();runCurrent()
  assertFalse(c.state.value.text.startsWith("Обновление подтверждено"));assertNotNull(peer.journal["test-device"])
 }

}
