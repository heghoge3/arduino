package com.vabugeode.app.store

import androidx.room.Room
import com.vabugeode.app.data.distanceM
import com.vabugeode.app.data.loadMarks
import com.vabugeode.app.data.loadTrack
import com.vabugeode.app.data.summarize
import com.vabugeode.app.demo.DemoDevice
import com.vabugeode.app.protocol.Codec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate

/** Походы в базе: список, сводка, название, удаление только своего похода. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HikesTest {

    private lateinit var db: StoreDb

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), StoreDb::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun close() = db.close()

    /** Демо-поход в базу; возвращает hike_start */
    private suspend fun put(device: String, daysAgo: Long, minutes: Int): Long {
        val d = DemoDevice(CoroutineScope(Dispatchers.Unconfined), autoStart = false)
        d.restart(historyMin = minutes, day = LocalDate.now().minusDays(daysAgo), live = false)
        val hike = d.hikeStartUtcSec
        val (pts, mks) = d.recorded()
        db.dao().putPoints(pts.map { PointRow(device, hike, it.timeUtc, Codec.encode(it)) })
        db.dao().putMarks(mks.map { MarkRow(device, hike, it.id, Codec.encode(it)) })
        return hike
    }

    @Test
    fun listSummaryRenameDelete() = runBlocking {
        val dao = db.dao()
        val old = put("virtual", 5, 90)
        val fresh = put("virtual", 1, 120)

        val keys = dao.hikeKeys().first()
        assertEquals(listOf(fresh, old), keys.map { it.hike })

        val track = dao.loadTrack("virtual", fresh)
        val sum = summarize("virtual", fresh, null, track, dao.loadMarks("virtual", fresh))
        // 2 часа пешком в демо — несколько километров, не ноль и не сотни
        assertTrue("km=${sum.km}", sum.km in 2.0..15.0)
        assertTrue(sum.tempMin <= sum.tempMax)
        assertTrue(sum.marks >= 1)

        dao.putHike(HikeRow("virtual", fresh, "Урбекс"))
        assertEquals("Урбекс", dao.hikeNames().first().single().name)

        dao.deleteHike("virtual", old)
        assertEquals(listOf(fresh), dao.hikeKeys().first().map { it.hike })
        assertEquals(0, dao.loadMarks("virtual", old).size)
    }

    @Test
    fun distanceOneDegreeLatitude() {
        assertEquals(111_195.0, distanceM(60.0, 30.0, 61.0, 30.0), 50.0)
    }
}
