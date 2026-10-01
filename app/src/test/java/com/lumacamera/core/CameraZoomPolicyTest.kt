package com.lumacamera.core

import org.junit.Assert.*
import org.junit.Test

class CameraZoomPolicyTest {
    @Test fun cropUsesSensorOriginsAndPreservesAspect() {
        assertEquals(FocusBounds(1100, 850, 3100, 2350),
            CameraZoomPolicy.crop(FocusBounds(100, 100, 4100, 3100), 2f, 4f))
    }
    @Test fun invalidAndUnsupportedZoomReturnFullSensor() {
        val sensor = FocusBounds(100, 100, 4100, 3100)
        assertEquals(sensor, CameraZoomPolicy.crop(sensor, Float.NaN, 4f))
        assertEquals(sensor, CameraZoomPolicy.crop(sensor, 10f, 1f))
        assertEquals(4f, CameraZoomPolicy.ratio(20f, 4f), 0f)
    }
    @Test fun trackingIsRateLimitedAndIgnoresNoise() {
        assertTrue(CameraZoomPolicy.shouldTrack(1000, null, .5f to .5f, null))
        assertFalse(CameraZoomPolicy.shouldTrack(1999, 1000, .8f to .8f, .5f to .5f))
        assertFalse(CameraZoomPolicy.shouldTrack(2000, 1000, .51f to .5f, .5f to .5f))
        assertTrue(CameraZoomPolicy.shouldTrack(2000, 1000, .8f to .8f, .5f to .5f))
        assertFalse(CameraZoomPolicy.shouldTrack(900, 1000, .8f to .8f, .5f to .5f))
    }
}
