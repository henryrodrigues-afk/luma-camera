package com.lumacamera.core

import org.junit.Assert.*
import org.junit.Test

class ProfessionalCaptureToolsTest {
    @Test fun halfTurnTracksFrameRateWithoutChangingIso() {
        assertEquals(16_666_666L, ProfessionalCaptureTools.exposureForAngle(180f, 30, 1000, 1_000_000_000))
        assertEquals(20_833_333L, ProfessionalCaptureTools.exposureForAngle(180f, 24, 1000, 1_000_000_000))
        assertEquals(8_333_333L, ProfessionalCaptureTools.exposureForAngle(180f, 60, 1000, 1_000_000_000))
    }
    @Test fun sensorAndFrameDurationBoundTheRequestedAngle() {
        assertEquals(1_000_000L, ProfessionalCaptureTools.exposureForAngle(1f, 30, 1_000_000, 100_000_000))
        assertEquals(10_000_000L, ProfessionalCaptureTools.exposureForAngle(360f, 30, 1000, 10_000_000))
        assertEquals(33_333_333L, ProfessionalCaptureTools.exposureForAngle(360f, 30, 1000, 1_000_000_000))
        assertNull(ProfessionalCaptureTools.exposureForAngle(180f, 30, 40_000_000, 100_000_000))
    }
    @Test fun invalidInputsNeverProduceARequest() {
        for (angle in listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, -1f, 361f))
            assertNull(ProfessionalCaptureTools.exposureForAngle(angle, 30, 1000, 1_000_000_000))
        assertNull(ProfessionalCaptureTools.exposureForAngle(180f, 0, 1000, 1_000_000_000))
        assertNull(ProfessionalCaptureTools.exposureForAngle(180f, 30, 1000, 1))
    }
    @Test fun displayedAngleUsesTheClampedValue() {
        assertEquals(108f, ProfessionalCaptureTools.angleForExposure(10_000_000, 30)!!, .0001f)
        assertNull(ProfessionalCaptureTools.angleForExposure(0, 30))
    }
}
