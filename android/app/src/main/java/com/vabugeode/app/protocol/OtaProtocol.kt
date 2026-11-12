package com.vabugeode.app.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.zip.CRC32

object OtaWire {
 const val QUERY=0x30;const val BEGIN=0x31;const val DATA=0x32;const val FINISH=0x33;const val ABORT=0x34;const val REBOOT=0x35
 const val MANIFEST=136;const val CHUNK=224;const val STATUS=143
 fun u32(b:ByteArray,at:Int)=ByteBuffer.wrap(b,at,4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
 fun packet(code:Int,request:Long,body:ByteArray=byteArrayOf())=ByteBuffer.allocate(6+body.size).order(ByteOrder.LITTLE_ENDIAN)
  .put(2).put(code.toByte()).putInt(request.toInt()).put(body).array()
 fun data(op:Long,offset:Int,bytes:ByteArray):ByteArray {
  require(bytes.size in 1..CHUNK)
  val crc=CRC32().apply{update(bytes)}.value
  return packet(DATA,op,ByteBuffer.allocate(8+bytes.size).order(ByteOrder.LITTLE_ENDIAN).putInt(offset).putInt(crc.toInt()).put(bytes).array())
 }
 fun decode(b:ByteArray):OtaStatus {
  require(b.size==STATUS && b[0].toInt()==2 && b[1].toInt()==QUERY){"Неверный OTA STATUS"}
  require(b[10].toInt() in 0..5 && b[11].toInt() in 0..11)
  return OtaStatus(u32(b,2),u32(b,6),b[10].toInt(),b[11].toInt(),(b[12].toInt() and 255) or ((b[13].toInt() and 255) shl 8),
   b.copyOfRange(14,38),u32(b,38),u32(b,42),u32(b,46),u32(b,50),b.copyOfRange(54,86),b.copyOfRange(86,118),
   b.copyOfRange(118,126).hex(),b.copyOfRange(126,134),u32(b,138),b[142].toInt() and 255)
 }
 fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes)
 fun ByteArray.hex()=joinToString(""){"%02x".format(it.toInt() and 255)}
 fun signatureDer(raw:ByteArray):ByteArray {
  require(raw.size==64)
  fun number(bytes:ByteArray):ByteArray {val value=bytes.dropWhile{it==0.toByte()}.toByteArray().let{if(it.isEmpty())byteArrayOf(0) else it}
   val positive=if(value[0].toInt()<0)byteArrayOf(0)+value else value
   return byteArrayOf(2,positive.size.toByte())+positive}
  val body=number(raw.copyOfRange(0,32))+number(raw.copyOfRange(32,64))
  return byteArrayOf(0x30,body.size.toByte())+body
 }
}
data class OtaStatus(val request:Long,val operation:Long,val phase:Int,val result:Int,val flags:Int,val metadata:ByteArray,
 val slotSize:Long,val flashSize:Long,val offset:Long,val imageSize:Long,val imageHash:ByteArray,val pendingHash:ByteArray,
 val deviceId:String,val keyId:ByteArray,val targetRelease:Long,val bootState:Int){
 val release get()=OtaWire.u32(metadata,16)
 val version get()="${metadata[20].toInt() and 255}.${metadata[21].toInt() and 255}.${metadata[22].toInt() and 255}"
 val confirmed get()=bootState==2&&flags and 32!=0
 val supported get()=flags and 1!=0
 val commissioned get()=flags and 2!=0
 val eligible get()=flags and 4!=0
 fun compatible(bundle:OtaBundle)=supported&&commissioned&&metadata.copyOfRange(8,16).contentEquals(bundle.metadata().copyOfRange(8,16))&&keyId.contentEquals(bundle.manifest.copyOfRange(64,72))&&slotSize>=bundle.image.size
}
class OtaBundle(val manifest:ByteArray,val image:ByteArray,publicKey:ByteArray){
 val release get()=OtaWire.u32(manifest,16)
 val version get()="${manifest[20].toInt() and 255}.${manifest[21].toInt() and 255}.${manifest[22].toInt() and 255}"
 val hash get()=manifest.copyOfRange(32,64)
 init {
  require(manifest.size==136&&manifest.copyOfRange(0,4).contentEquals("VBO1".toByteArray()))
  require(manifest[4].toInt()==1&&manifest[5].toInt()==1&&manifest[6].toInt()==1&&manifest[7].toInt()==1&&OtaWire.u32(manifest,8)==1L)
  require(manifest[12].toInt()==2&&manifest[13].toInt()==2&&manifest[14].toInt()==1&&manifest[15].toInt()==0&&manifest[23].toInt()==0)
  require(image.size in 1..0x140000&&OtaWire.u32(manifest,24)==image.size.toLong()&&hash.contentEquals(OtaWire.sha(image)))
  require(release==((manifest[20].toLong() and 255) shl 16 or ((manifest[21].toLong() and 255) shl 8) or (manifest[22].toLong() and 255)))
  require(OtaWire.sha(publicKey).copyOf(8).contentEquals(manifest.copyOfRange(64,72)))
  val key=KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKey))
  require(Signature.getInstance("SHA256withECDSA").run{initVerify(key);update(manifest,0,72);verify(OtaWire.signatureDer(manifest.copyOfRange(72,136)))}){"Подпись прошивки недействительна"}
 }
 fun metadata():ByteArray=ByteArray(24).also {m->"VBMETA1\u0000".toByteArray().copyInto(m);manifest.copyInto(m,8,5,8);m[11]=2;manifest.copyInto(m,12,8,12);manifest.copyInto(m,16,16,20);manifest.copyInto(m,20,20,23)}
}
