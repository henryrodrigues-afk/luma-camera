package com.lumacamera.core

import org.junit.Assert.*
import org.junit.Test

class CameraMotionPolicyTest {
    @Test fun endpointsAndMidpointFollowElapsedTime() {
        assertEquals(1f, CameraMotionPolicy.sample(1f, 3f, -1L, 2_000L, 1f, 4f)!!, 0f)
        assertEquals(2f, CameraMotionPolicy.sample(1f, 3f, 1_000L, 2_000L, 1f, 4f)!!, 0f)
        assertEquals(3f, CameraMotionPolicy.sample(1f, 3f, Long.MAX_VALUE, 2_000L, 1f, 4f)!!, 0f)
        assertFalse(CameraMotionPolicy.completed(1_999L, 2_000L))
        assertTrue(CameraMotionPolicy.completed(2_000L, 2_000L))
    }

    @Test fun easingStartsAndEndsGentlyAndSupportsReverseFocusPull() {
        val first = CameraMotionPolicy.sample(4f, 0f, 100L, 2_000L, 0f, 5f)!!
        val middle = CameraMotionPolicy.sample(4f, 0f, 1_000L, 2_000L, 0f, 5f)!!
        val last = CameraMotionPolicy.sample(4f, 0f, 1_900L, 2_000L, 0f, 5f)!!
        assertTrue(first > 3.9f); assertEquals(2f, middle, 0f); assertTrue(last < .1f)
    }

    @Test fun limitsClampEndpointsAndInvalidValuesCancelInsteadOfPoisoningCameraRequests() {
        assertEquals(2f, CameraMotionPolicy.sample(-5f, 9f, 500L, 1_000L, 1f, 3f)!!, 0f)
        assertNull(CameraMotionPolicy.sample(Float.NaN, 3f, 500L, 1_000L, 1f, 3f))
        assertNull(CameraMotionPolicy.sample(1f, 3f, 500L, 0L, 1f, 3f))
        assertNull(CameraMotionPolicy.sample(1f, 3f, 500L, 1_000L, 3f, 1f))
        assertNull(CameraMotionPolicy.durationMs(Float.POSITIVE_INFINITY))
        assertNull(CameraMotionPolicy.durationMs(.01f))
        assertEquals(2_000L, CameraMotionPolicy.durationMs(2f))
    }

    @Test fun skippedUiFramesDoNotChangeTheMotionPath() {
        val sparse = CameraMotionPolicy.sample(1f, 4f, 1_375L, 3_000L, 1f, 4f)
        for (time in 0L..1_350L step 25L) CameraMotionPolicy.sample(1f, 4f, time, 3_000L, 1f, 4f)
        assertEquals(sparse, CameraMotionPolicy.sample(1f, 4f, 1_375L, 3_000L, 1f, 4f))
    }
}
