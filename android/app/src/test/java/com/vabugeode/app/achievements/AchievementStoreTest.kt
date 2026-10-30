package com.vabugeode.app.achievements

import androidx.room.*
import com.vabugeode.app.store.*
import com.vabugeode.app.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class AchievementStoreTest {
    private lateinit var db:StoreDb
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    private val base=1793340000L
    private fun points(device:String,hike:Long=base)= (0..20).map{n->
        val p=PointMsg(hike+n*60,null,null,0,false,0,null,null,10.0,10.0,50.0,1013.0,0.0,null,4000,80,0,null,n*100L,Double.NaN,0.0)
        PointRow(device,hike,p.timeUtc,Codec.encode(p))
    }
    @Before fun open(){db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),StoreDb::class.java).build()}
    @After fun close(){scope.cancel();db.close()}
    private fun tracker()=AchievementTracker(db.dao(),scope,MutableStateFlow(emptyList()),MutableStateFlow(.75),{(base+86400)*1000})
    @Test fun defaultOffAndVirtualCannotEarnAchievements()=runBlocking {
        val dao=db.dao();dao.putPoints(points("tracker"));dao.putPoints(points("virtual"));dao.includeAchievementHike("virtual",base)
        val t=tracker();withTimeout(7000){t.ready.first{it}}
        assertTrue(t.progress.value.none{it.unlocked})
        assertTrue(dao.achievementKeys().first().isEmpty())
    }
    @Test fun includedHikeCannotBeDeletedAndResyncDoesNotDoubleProgress()=runBlocking {
        val dao=db.dao();dao.putPoints(points("tracker"));dao.includeAchievementHike("tracker",base)
        dao.includeAchievementHike("tracker",base)
        assertEquals(1,dao.achievementSelections().first().size)
        dao.deleteHike("tracker",base)
        assertEquals(21,dao.points("tracker",base).size)
        val t=tracker();withTimeout(7000){t.progress.first{all->all.any{it.badge.id=="r01"&&it.unlocked}}}
        val before=t.progress.value.first{it.badge.id=="w01"}.value
        dao.putPoints(points("tracker"))
        delay(1100)
        assertEquals(before,t.progress.value.first{it.badge.id=="w01"}.value,.001)
        val id="r01";t.see(id);assertTrue(dao.achievementUnlocks().first{it.id==id}.seenMs>0)
        t.job.cancelAndJoin()
        val restarted=tracker();withTimeout(7000){restarted.ready.first{it}}
        assertTrue(restarted.progress.value.first{it.badge.id==id}.unlocked)
        assertFalse(restarted.progress.value.first{it.badge.id==id}.new)
        assertEquals(before,restarted.progress.value.first{it.badge.id=="w01"}.value,.001)
    }
    @Test fun unselectedHikeCanStillBeDeleted()=runBlocking {
        val dao=db.dao();dao.putPoints(points("tracker"));dao.deleteHike("tracker",base)
        assertTrue(dao.points("tracker",base).isEmpty())
    }
    @Test fun v3MigrationKeepsHikesAndStartsWithAllFlagsOff()=runBlocking {
        val context=RuntimeEnvironment.getApplication();val name="achievement-migration.db"
        context.deleteDatabase(name)
        val old=Room.databaseBuilder(context,LegacyAchievementDb::class.java,name).build()
        val rows=points("tracker");old.dao().putPoints(rows);old.dao().putHike(HikeRow("tracker",base,"Архив"));old.close()
        val migrated=Room.databaseBuilder(context,StoreDb::class.java,name).addMigrations(MIGRATION_3_4).build()
        assertEquals(rows.size,migrated.dao().points("tracker",base).size)
        assertEquals("Архив",migrated.dao().hikeNames().first().single().name)
        assertTrue(migrated.dao().achievementSelections().first().isEmpty())
        migrated.dao().includeAchievementHike("tracker",base)
        migrated.dao().putAchievementUnlocks(listOf(AchievementUnlock("fixture",1)))
        assertTrue(migrated.dao().isAchievementHike("tracker",base))
        migrated.close();context.deleteDatabase(name);Unit
    }
}
@Dao
interface LegacyAchievementDao {
    @Insert suspend fun putPoints(rows:List<PointRow>)
    @Insert suspend fun putHike(row:HikeRow)
}
@Database(entities=[PointRow::class,MarkRow::class,GadgetRow::class,HikeRow::class,GadgetLogRow::class,AppLogRow::class],version=3,exportSchema=false)
abstract class LegacyAchievementDb:RoomDatabase(){abstract fun dao():LegacyAchievementDao}
