package com.vabugeode.app.achievements

import com.vabugeode.app.data.*
import com.vabugeode.app.model.BlacklistPoint
import com.vabugeode.app.store.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId

/** Работает только с выбранными реальными походами. Повторная BLE-выгрузка не удваивает статистику. */
class AchievementTracker(private val dao: StoreDao, scope: CoroutineScope,
    blacklist: StateFlow<List<BlacklistPoint>>, stride: StateFlow<Double>,
    private val now: () -> Long = System::currentTimeMillis) {
    val progress=MutableStateFlow<List<AchievementProgress>>(emptyList())
    val ready=MutableStateFlow(false)
    val notices=MutableSharedFlow<AchievementEvent>(extraBufferCapacity=8)
    private val lock=Mutex()
    private var known=emptyMap<String,AchievementUnlock>()
    private val seenNow=mutableSetOf<String>()
    private data class Cached(val key:AchievementKey,val facts:TrailFacts,val lastMarkMs:Long)
    private val cache=mutableMapOf<Pair<String,Long>,Cached>()
    @OptIn(FlowPreview::class)
    val job=scope.launch(Dispatchers.IO) {
        var config:Triple<List<BlacklistPoint>,Double,String>?=null
        var previousKeys=emptyList<AchievementKey>()
        var initialized=false
        combine(dao.achievementKeys(),blacklist,stride){keys,zones,step->Triple(keys,zones,step)}
            .debounce(700).collect { (keys,zones,step) ->
                lock.withLock {
                    val zone=ZoneId.systemDefault()
                    val nextConfig=Triple(zones,step,zone.id)
                    if(config!=nextConfig){cache.clear();config=nextConfig}
                    val latest=keys.groupBy{it.device}.mapValues{it.value.maxOf{key->key.hike}}
                    keys.forEach { key ->
                        val id=key.device to key.hike
                        if(cache[id]?.key!=key || latest[key.device]==key.hike){
                            val raw=dao.loadTrack(key.device,key.hike)
                            val track=excludeBlacklisted(raw,zones)
                            val marks=excludeBlacklistedMarks(dao.loadMarks(key.device,key.hike),zones)
                            cache[id]=Cached(key,TrailAnalyzer.analyze(track,marks,key.hike*1000,zone,step,now()),marks.maxOfOrNull{it.tsUtcMs}?:0L)
                        }
                    }
                    cache.keys.retainAll(keys.map{it.device to it.hike}.toSet())
                    val facts=TrailAnalyzer.aggregate(cache.values.map{it.facts})
                    known=dao.achievementUnlocks().associateBy{it.id}
                    val fresh=AchievementCatalog.all.filter{it.id !in known && (facts[it.metric]?:0.0)+1e-8>=it.target}
                    if(fresh.isNotEmpty()){
                        dao.putAchievementUnlocks(fresh.map{AchievementUnlock(it.id,now())})
                        known=dao.achievementUnlocks().associateBy{it.id}
                    }
                    progress.value=AchievementCatalog.all.map { badge ->
                        val unlocked=known[badge.id]
                        AchievementProgress(badge,maxOf(facts[badge.metric]?:0.0,if(unlocked!=null)badge.target else 0.0),unlocked?.unlockedMs,
                            unlocked!=null&&unlocked.seenMs==0L&&badge.id !in seenNow)
                    }
                    ready.value=true
                    if(fresh.isNotEmpty()){
                        val unchangedSelection=keys.map{it.device to it.hike}.toSet()==previousKeys.map{it.device to it.hike}.toSet()
                        val changed=if(initialized&&unchangedSelection)keys.filter{k->
                            val prev=previousKeys.find{it.device==k.device&&it.hike==k.hike}
                            val point=prev!=null&&k.last>prev.last&&k.last*1000 in (now()-120_000)..(now()+10_000)
                            val mark=prev!=null&&(k.marks>prev.marks||k.lastMark>prev.lastMark)&&
                                (cache[k.device to k.hike]?.lastMarkMs?:0L) in (now()-120_000)..(now()+10_000)
                            point||mark
                        }.map{it.device to it.hike}.toSet() else emptySet()
                        notices.tryEmit(AchievementEvent(fresh,changed))
                    }
                    previousKeys=keys;initialized=true
                }
            }
    }
    suspend fun see(id:String)=lock.withLock {
        seenNow+=id;dao.seeAchievement(id,now())
        progress.value=progress.value.map{if(it.badge.id==id)it.copy(new=false)else it}
    }
}
data class AchievementEvent(val unlocked:List<Achievement>,val liveHikes:Set<Pair<String,Long>>) {
    val text: String get() = if(unlocked.size==1) "Достижение открыто: ${unlocked.first().title}" else "Открыто достижений: ${unlocked.size}"
}
