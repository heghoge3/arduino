package com.vabugeode.app.store

import androidx.room.Room
import com.vabugeode.app.data.ProtocolDeviceRepository
import com.vabugeode.app.demo.DemoDevice
import com.vabugeode.app.model.LinkState
import com.vabugeode.app.transport.VirtualTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Локальная база: данные переживают «перезапуск приложения», после него догружается только новое. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StoreTest {

    private lateinit var db: StoreDb

    @Before
    fun open() {
        // Синхронные исполнители: запросы идут сразу, виртуальное время тестов их не обгоняет
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), StoreDb::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
    }

    @After
    fun close() = db.close()

    @Test
    fun dataSurvivesRestartAndResyncIsIncremental() = runTest {
        val dev = DemoDevice(backgroundScope)
        val t1 = VirtualTransport(backgroundScope, dev)
        val repo1 = ProtocolDeviceRepository(backgroundScope, t1, emptyList(), db.dao())
        repo1.connect()
        advanceTimeBy(6_000)
        val synced = repo1.track.value.size
        assertTrue("история не пришла", synced > 500)
        repo1.disconnect()
        advanceTimeBy(1_500)  // дать записаться в базу
        val stored = db.dao().points("virtual", repo1.hikeStartMs.value!! / 1000).size
        assertTrue("в базе $stored из $synced", stored >= synced)

        // «Перезапуск приложения»: новый репозиторий, связи нет — всё видно из базы
        val t2 = VirtualTransport(backgroundScope, dev)
        val repo2 = ProtocolDeviceRepository(backgroundScope, t2, emptyList(), db.dao())
        advanceTimeBy(500)
        assertEquals(LinkState.DISCONNECTED, repo2.link.value)
        assertEquals(stored, repo2.track.value.size)
        assertNotNull("последнее состояние не восстановилось", repo2.status.value)
        assertNotNull(repo2.lastSeenMs.value)
        assertEquals(repo1.hikeStartMs.value, repo2.hikeStartMs.value)

        // Подключились снова — догружаем только недостающее
        advanceTimeBy(20_000)  // гаджет тем временем пишет дальше
        repo2.connect()
        advanceTimeBy(6_000)
        val received = repo2.stats.value.packetsIn
        assertTrue("пришло $received пакетов — это почти вся история заново", received < 200)
        val times = repo2.track.value.map { it.tsUtcMs }
        assertEquals("дубли", times.size, times.toSet().size)
        assertTrue(dev.pointCount - times.size in 0..1)
    }
}
