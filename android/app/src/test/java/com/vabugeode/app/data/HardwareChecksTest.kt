package com.vabugeode.app.data

import com.vabugeode.app.model.CheckLevel
import com.vabugeode.app.model.PowerState
import com.vabugeode.app.protocol.CheckMsg
import com.vabugeode.app.protocol.InfoMsg
import org.junit.Assert.*
import org.junit.Test

class HardwareChecksTest {
    private val info = InfoMsg(0, 1, 1, 1, 1L, 7600L, 7500L, 0, null, emptyList())

    @Test fun identifiesCompassVariantWithoutPromisingCalibration() {
        val c = ProtocolDeviceRepository.checkText(CheckMsg(4, 1, 3, 0x2C), null, info)
        assertEquals("Компас QMC5883P", c.name)
        assertEquals("0x2C", c.address)
        assertEquals(CheckLevel.WARN, c.level)
        assertTrue(c.detail.contains("пока не поддерживаются"))
    }

    @Test fun automaticNavigationDoesNotRequireCompassCalibration() {
        val latest = info.copy(fwPatch = 15)
        val compass = ProtocolDeviceRepository.checkText(CheckMsg(4, 1, 3, 0x2C), null, latest)
        assertTrue(compass.detail.contains("работает без него"))
        val waiting = ProtocolDeviceRepository.checkText(CheckMsg(10, 1, 14, 0), null, latest)
        assertEquals("Приблизительный маршрут", waiting.name)
        assertTrue(waiting.detail.contains("GPS"))
        val ready = ProtocolDeviceRepository.checkText(CheckMsg(10, 0, 0, 0), null, latest)
        assertEquals(CheckLevel.OK, ready.level)
        assertTrue(ready.detail.contains("автоматически"))
    }

    @Test fun missingBatteryIsUnknownInsteadOfDepleted() {
        val power = PowerState(0.0, 0.0, 0, false, 0.0, 0.0)
        assertFalse(power.batteryKnown)
        assertTrue(power.copy(voltage = 3.3).batteryKnown)
        assertFalse(power.copy(voltage = Double.NaN).batteryKnown)
        val c = ProtocolDeviceRepository.checkText(CheckMsg(8, 1, 12, 0), null, info)
        assertEquals(CheckLevel.WARN, c.level)
        assertTrue(c.detail.contains("не измеряется"))
        assertFalse(c.detail.contains("Низкий заряд"))
    }
}
