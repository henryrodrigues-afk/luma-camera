package com.lumacamera.core

import org.junit.Assert.*
import org.junit.Test

class FocusTrackingPolicyTest {
    @Test fun initialPointIsSentThenThrottledAndJitterDoesNotHunt() {
        val policy = FocusTrackingPolicy()
        assertEquals(.4f to .6f, policy.update(.4f to .6f, 0L))
        assertNull(policy.update(.42f to .61f, 250L))
        assertNull(policy.update(.4f to .6f, 1_000L))
        assertNull(policy.update(.41f to .6f, 2_000L))
    }

    @Test fun movingTargetProducesSmoothedSourceCoordinatesAtMostOncePerSecond() {
        val policy = FocusTrackingPolicy()
        policy.update(.3f to .7f, 0L)
        assertNull(policy.update(.35f to .7f, 250L))
        assertNull(policy.update(.4f to .7f, 500L))
        val sent = policy.update(.5f to .7f, 1_000L)!!
        assertTrue(sent.first > .4f && sent.first < .5f)
        assertEquals(.7f, sent.second, .00001f)
    }

    @Test fun jumpCannotAdoptAnotherObjectAndNullStopsFollowing() {
        val policy = FocusTrackingPolicy()
        policy.update(.2f to .2f, 0L)
        assertNull(policy.update(.8f to .8f, 1_000L))
        assertNull(policy.update(.8f to .8f, 2_000L))
        assertNull(policy.update(.8f to .8f, 3_000L))
        assertNull(policy.update(null, 3_500L))
        assertEquals(.8f to .8f, policy.update(.8f to .8f, 4_000L))
    }

    @Test fun staleOrRegressingClockNeedsFreshObservations() {
        val policy = FocusTrackingPolicy()
        policy.update(.2f to .2f, 500L)
        assertNull(policy.update(.3f to .2f, 400L))
        assertNull(policy.update(.7f to .2f, 3_000L))
        assertEquals(.7f to .2f, policy.update(.7f to .2f, 3_250L))
        assertNull(policy.update(Float.NaN to .2f, 4_000L))
        assertNull(policy.update(1.1f to .2f, 4_250L))
    }

    @Test fun intermittentMissingMaskCannotBypassOneSecondLensThrottle() {
        val policy = FocusTrackingPolicy()
        assertNotNull(policy.update(.4f to .4f, 0L))
        assertNull(policy.update(null, 100L))
        assertNull(policy.update(.6f to .4f, 200L))
        assertNull(policy.update(null, 300L))
        assertEquals(.6f to .4f, policy.update(.6f to .4f, 1_000L))
        policy.reset() // An explicit new selection has its own intent, rather than mask flapping.
        assertEquals(.8f to .4f, policy.update(.8f to .4f, 1_050L))
    }
}
