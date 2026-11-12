package com.vabugeode.app.ota

import com.vabugeode.app.protocol.*
import com.vabugeode.app.protocol.OtaWire.hex
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.security.SecureRandom

interface OtaJournal {fun read(device:String):String?;fun write(device:String,value:String?)}
data class OtaUi(val busy:Boolean=false,val progress:Int=0,val text:String="Подключите прибор и проверьте обновление",val caps:OtaStatus?=null,val canUpdate:Boolean=false,val target:String?=null)
class OtaClient(private val scope:CoroutineScope,private val send:suspend(ByteArray)->Boolean,
 private val connected:()->Boolean,private val ready:()->Boolean){
 val state=MutableStateFlow(OtaUi())
 var bundle:OtaBundle?=null
 var bundleError:String?=null
 var journal:OtaJournal?=null
 private var device="";private var pending:String?=null
 private var job:Job?=null
 private var waiter:Triple<Long,(OtaStatus)->Boolean,CompletableDeferred<OtaStatus>>?=null
 private var selected=false
 private var sequence=SecureRandom().nextInt().toLong() and 0xffffffffL
 val busy get()=state.value.busy
 private fun id():Long {sequence=(sequence+1) and 0xffffffffL;return sequence.takeIf{it!=0L}?:id()}
 fun deviceChanged(key:String){interrupt();device=key;pending=journal?.read(key);state.value=OtaUi(target=bundle?.version)}
 fun receive(p:OtaStatus){val w=waiter;if(w!=null&&w.first==p.request&&(p.result!=0||w.second(p)))w.third.complete(p)}
 fun unsupported(){waiter?.third?.completeExceptionally(IllegalStateException("Нужна однократная подготовка прибора по USB"))}
 fun connection(on:Boolean){
  if(!on){waiter?.third?.completeExceptionally(IllegalStateException("Связь потеряна"));if(busy)interrupt();state.value=state.value.copy(caps=null,canUpdate=false)}
 }
 fun background(){if(busy)interrupt()}
 fun interrupt(){job?.cancel();waiter?.third?.cancel();state.value=state.value.copy(busy=false,canUpdate=false,text="Передача прервана. Подключитесь и проверьте результат")}
 private fun remember(value:String?){pending=value;journal?.write(device,value)}
 private fun failure(result:Int)=when(result){
  1->"Прибор отклонил формат обновления"
  2->"Прибор занят. Завершите поход или дождитесь проверки предыдущего обновления"
  3->"Подпись прошивки не прошла проверку на приборе"
  4->"Прошивка не подходит прибору или её версия уже устарела"
  5->"Ошибка записи памяти прибора. Обновление не подтверждено"
  6->"Питание не подходит для обновления. Зарядите прибор и оставьте ползунок в ON"
  7->"Операция прервана. Подключитесь и проверьте результат"
  8->"Нарушен порядок передачи. Повторите обновление с начала"
  9->"Записанная прошивка не прошла проверку. Повторите передачу с начала"
  10->"Прибор не получил данные вовремя. Повторите передачу с начала"
  else->"Обновление прервано. Подключитесь и проверьте результат"
 }
 private suspend fun rpc(packet:ByteArray,predicate:(OtaStatus)->Boolean={true}):OtaStatus {
  val request=OtaWire.u32(packet,2)
  repeat(3){
   val d=CompletableDeferred<OtaStatus>();waiter=Triple(request,predicate,d)
   try {
    if(!connected()||!send(packet))throw IllegalStateException("Нет связи с прибором")
    val reply=withTimeoutOrNull(5000){d.await()}
    if(reply!=null){check(reply.result==0){failure(reply.result)};return reply}
   }finally{if(waiter?.third===d)waiter=null}
   delay(200L*(it+1))
  }
  throw IllegalStateException("Прибор не подтвердил запись. Повторите с начала после проверки")
 }
 fun check(){
  if(busy||!connected()||job?.isCompleted==false)return
  job=scope.launch {
   try{
    val end=System.nanoTime()+60_000_000_000L
    do{
     inspect(rpc(OtaWire.packet(OtaWire.QUERY,id())))
     if(pending==null||state.value.caps?.phase !in listOf(2,3)||System.nanoTime()>=end)break
     delay(1500)
    }while(connected())
   }
   catch(e:CancellationException){throw e}
   catch(e:Exception){state.value=state.value.copy(text=e.message?:"Проверка недоступна",canUpdate=false)}
  }
 }
 private fun inspect(c:OtaStatus){
  val b=bundle;val record=pending?:journal?.read(device)
  val parts=record?.split('|')
  if(parts!=null&&parts.size==5){
   check(c.deviceId==parts[3]){"Подключён другой прибор; результат обновления не подтверждён"}
   if(c.confirmed&&c.release==parts[2].toLong()&&c.imageHash.hex()==parts[1]){
    remember(null);state.value=OtaUi(text="Обновление подтверждено прибором: ${c.version}",caps=c,target=b?.version);return
   }
   if(c.phase==2&&c.operation==parts[0].toLong()){
    state.value=OtaUi(text="Образ записан. Выполните перезапуск прибора и проверьте результат",caps=c,target=b?.version);return
   }
   if(c.phase==3){state.value=OtaUi(text="Прибор проверяет первый запуск. Повторите проверку",caps=c,target=b?.version);return}
   if(parts[4]=="selected"&&c.phase!=2){
    val retry=c.confirmed&&b!=null&&c.compatible(b)&&c.eligible&&c.release<b.release&&ready()
    if(c.confirmed&&c.release!=parts[2].toLong())remember(null)
    state.value=OtaUi(text="Новая версия не подтверждена. Фактически загружена ${c.version}; возможен откат",caps=c,target=b?.version,canUpdate=retry);return
   }
  }
  val allowed=b!=null&&c.compatible(b)&&c.eligible&&c.release<b.release&&OtaWire.u32(b.manifest,28)<=c.release&&ready()
  val text=when{
   !c.supported||!c.commissioned->"Нужна однократная подготовка V2 по USB"
   c.phase==3->"Прибор проверяет первый запуск новой прошивки"
   b==null->bundleError?:"В APK нет образа прошивки"
   !c.compatible(b)->"Образ APK не подходит этому прибору"
   c.release>=b.release->"Установлена версия ${c.version}. Обновление не требуется"
   !c.eligible||!ready()->"Завершите поход и синхронизацию; проверьте питание (от 50%, 3,8 В) и повторите проверку"
   else->"Доступна прошивка ${b.version}. Установлена ${c.version}"
  }
  state.value=OtaUi(text=text,caps=c,canUpdate=allowed,target=b?.version)
 }
 fun start(){
  val b=bundle?:return;val c=state.value.caps?:return
  if(busy||!state.value.canUpdate||!ready())return
  job?.cancel();selected=false
  job=scope.launch {
   val op=id();val expectedDevice=device
   state.value=state.value.copy(busy=true,progress=0,canUpdate=false,text="Подготовка обновления")
   try{
    remember("$op|${b.hash.hex()}|${b.release}|${c.deviceId}|receiving")
    val fresh=rpc(OtaWire.packet(OtaWire.QUERY,id()))
    check(device==expectedDevice&&fresh.deviceId==c.deviceId&&fresh.compatible(b)&&fresh.eligible&&ready()){"Условия обновления изменились"}
    rpc(OtaWire.packet(OtaWire.BEGIN,op,b.manifest)){it.operation==op&&it.phase==1&&it.offset==0L}
    var offset=0
    while(offset<b.image.size){
     // Fresh lifecycle/STATUS is required before BEGIN. During flash erase/write,
     // OTA ACKs are authoritative; foreground loss cancels this job via background().
     currentCoroutineContext().ensureActive()
     check(connected()){"Связь потеряна. Повторите передачу с начала"}
     val end=minOf(offset+OtaWire.CHUNK,b.image.size)
     rpc(OtaWire.data(op,offset,b.image.copyOfRange(offset,end))){it.operation==op&&it.phase==1&&it.offset==end.toLong()}
     offset=end;state.value=state.value.copy(progress=(offset.toLong()*100/b.image.size).toInt(),text="Передача прошивки")
    }
    state.value=state.value.copy(text="Проверка образа на приборе")
    // FINISH may select the slot even if its answer is lost. Persist intent before sending.
    remember("$op|${b.hash.hex()}|${b.release}|${c.deviceId}|selected");selected=true
    rpc(OtaWire.packet(OtaWire.FINISH,op)){it.operation==op&&it.phase==2&&it.pendingHash.contentEquals(b.hash)}
    state.value=state.value.copy(text="Перезапуск. Ожидаем подтверждение прибора")
    rpc(OtaWire.packet(OtaWire.REBOOT,op)){it.operation==op&&it.phase==2}
    // Reconnect is owned by the existing LinkManager; no second GATT client.
    withTimeout(60000){
     while(true){delay(1500);if(!connected())continue
      val status=try{rpc(OtaWire.packet(OtaWire.QUERY,id()))}catch(e:CancellationException){throw e}catch(_:Exception){continue}
      if(status.deviceId!=c.deviceId)throw IllegalStateException("Подключён другой прибор")
      if(status.confirmed&&status.release==b.release&&status.imageHash.contentEquals(b.hash)){inspect(status);break}
      if(status.phase==5)throw IllegalStateException("Прибор сообщил о неудачной проверке. Загружена ${status.version}")
     }
    }
   }catch(e:CancellationException){
    if(device==expectedDevice)state.value=state.value.copy(text="Результат не подтверждён. Включите прибор, подключитесь и проверьте")
    throw e
   }catch(e:Exception){if(device==expectedDevice)state.value=state.value.copy(text=e.message?:"Обновление не подтверждено")}
   finally{
    if(!selected&&connected()&&device==expectedDevice)withContext(NonCancellable){withTimeoutOrNull(2000){send(OtaWire.packet(OtaWire.ABORT,op))}}
    if(device==expectedDevice)state.value=state.value.copy(busy=false,canUpdate=false)
   }
  }
 }
}
