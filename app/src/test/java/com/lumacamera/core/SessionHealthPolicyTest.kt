package com.lumacamera.core

import org.junit.Assert.*
import org.junit.Test

class SessionHealthPolicyTest {
    @Test fun absentAndInvalidBatteryDataRemainUnknownRatherThanPretendingZero() {
        assertNull(SessionHealthPolicy.batteryPercent(null, 100))
        assertNull(SessionHealthPolicy.batteryPercent(-1, 100))
        assertNull(SessionHealthPolicy.batteryPercent(50, 0))
        assertNull(SessionHealthPolicy.batteryPercent(101, 100))
        assertEquals(0, SessionHealthPolicy.batteryPercent(0, 100))
    }

    @Test fun nonStandardBatteryScaleDoesNotOverflowOrTreatLevelAsPercent() {
        assertEquals(50, SessionHealthPolicy.batteryPercent(50, 100))
        assertEquals(75, SessionHealthPolicy.batteryPercent(3, 4))
        assertEquals(100, SessionHealthPolicy.batteryPercent(Int.MAX_VALUE, Int.MAX_VALUE))
    }

    @Test fun batteryTemperatureUsesTenthsAndRejectsMissingOrAbsurdValues() {
        assertEquals(37.5f, SessionHealthPolicy.batteryTemperature(375)!!, .001f)
        assertNull(SessionHealthPolicy.batteryTemperature(null))
        assertNull(SessionHealthPolicy.batteryTemperature(Int.MIN_VALUE))
        assertNull(SessionHealthPolicy.batteryTemperature(3750))
    }

    @Test fun unknownThermalStatusNeverAppearsHealthy() {
        assertNull(SessionHealthPolicy.thermalStatus(-1))
        assertNull(SessionHealthPolicy.thermalStatus(7))
        assertEquals("Estado térmico indisponível", SessionHealthPolicy.thermalLabel(null))
        assertNull(SessionHealthPolicy.guidance(null))
    }

    @Test fun thermalAdviceDistinguishesNormalMonitoringAndUrgentCooldown() {
        assertNull(SessionHealthPolicy.guidance(0))
        assertNotNull(SessionHealthPolicy.guidance(2))
        assertTrue(SessionHealthPolicy.guidance(3)!!.contains("Finalize"))
        assertTrue(SessionHealthPolicy.guidance(6)!!.contains("esfriar"))
        assertNotEquals(SessionHealthPolicy.thermalLabel(0), SessionHealthPolicy.thermalLabel(6))
    }
}
