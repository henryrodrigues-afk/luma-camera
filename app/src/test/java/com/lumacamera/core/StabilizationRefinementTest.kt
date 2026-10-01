package com.lumacamera.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class StabilizationRefinementTest {
    private val parameters = StabilizationTuning(intensity = 1f, response = .5f, cropZoom = 1.2f)
    private fun observation(x: Float = 0f, y: Float = 0f, angle: Float = 0f) =
        FrameMotionEstimate(x, y, 1f, true, reason = "tracked", rotationRadians = angle)
    private fun initialize(policy: StabilizationPolicy, tuning: StabilizationTuning = parameters) {
        policy.update(FrameMotionEstimate(reset = true), 0L, tuning)
        policy.transform(0L, tuning)
    }

    /** Check the shader equation directly. UI coordinate clamps must not conceal an unsafe sampler. */
    private fun assertCornersInside(result: StabilizationTransform) {
        val c = cos(result.rotationRadians.toDouble())
        val s = sin(result.rotationRadians.toDouble())
        for (u in listOf(0.0, 1.0)) for (v in listOf(0.0, 1.0)) {
            val x = .5 + (c * (u - .5) - s * (v - .5) / result.aspectRatio) / result.zoom + result.centerOffsetX
            val y = .5 + (s * (u - .5) * result.aspectRatio + c * (v - .5)) / result.zoom + result.centerOffsetY
            assertTrue("Unsafe X=$x $result", x >= -.000001 && x <= 1.000001)
            assertTrue("Unsafe Y=$y $result", y >= -.000001 && y <= 1.000001)
        }
    }

    @Test fun fixedCropContainsRawCornersDuringTranslationAndRollAtWideAndTallAspects() {
        for (aspect in listOf(16f / 9f, 9f / 16f)) for (zoom in listOf(1.04f, 1.25f)) {
            val tuning = parameters.copy(aspectRatio = aspect, cropZoom = zoom)
            val policy = StabilizationPolicy(); initialize(policy, tuning)
            var sawRotation = false
            for (index in 1..80) {
                val time = index * 100L
                policy.update(observation(if (index < 40) .2f else -.2f, -.15f,
                    if (index % 8 < 4) .05f else -.05f), time, tuning)
                for (offset in listOf(0L, 16L, 33L, 50L, 83L)) {
                    val result = policy.transform(time + offset, tuning)
                    assertEquals(zoom, result.zoom, 0f)
                    assertEquals(aspect, result.aspectRatio, 0f)
                    assertCornersInside(result)
                    sawRotation = sawRotation || abs(result.rotationRadians) > .00001f
                }
            }
            assertTrue("Roll was never applied at aspect=$aspect zoom=$zoom", sawRotation)
        }
    }

    @Test fun samplerAndInverseRoundTripCornersAndInteriorForBothPhysicalAspects() {
        for (aspect in listOf(16f / 9f, 9f / 16f)) for (zoom in listOf(1.04f, 1.25f)) {
            val transform = StabilizationTransform(zoom = zoom, centerOffsetX = .003f, centerOffsetY = -.004f,
                rotationRadians = .008f, aspectRatio = aspect)
            for (u in listOf(0f, .2f, .5f, .8f, 1f)) for (v in listOf(0f, .2f, .5f, .8f, 1f)) {
                val source = transform.sourcePoint(u, v)
                val output = transform.outputPoint(source.first, source.second)!!
                assertEquals(u, output.first, .00001f)
                assertEquals(v, output.second, .00001f)
            }
            assertNull(transform.outputPoint(0f, .5f))
            assertNull(transform.outputPoint(Float.NaN, .5f))
        }
    }

    @Test fun positiveSceneRollUsesPositivePhysicalSamplerRotation() {
        val angle = .01f
        val aspect = 16f / 9f
        val transform = StabilizationTransform(zoom = 1.2f, rotationRadians = angle, aspectRatio = aspect)
        val point = transform.sourcePoint(.6f, .5f)
        assertEquals(.5f + cos(angle) * .1f / 1.2f, point.first, .000001f)
        assertEquals(.5f + sin(angle) * .1f * aspect / 1.2f, point.second, .000001f)
        assertTrue(point.second > .5f)
    }

    @Test fun zeroIntensityImmediatelyRemovesEveryCorrectionWithoutChangingCrop() {
        val policy = StabilizationPolicy(); initialize(policy)
        for (index in 1..10) {
            policy.update(observation(.015f, -.01f, .005f), index * 100L, parameters)
            policy.transform(index * 100L, parameters)
        }
        val active = policy.transform(1090, parameters)
        assertTrue(active.centerOffsetX > 0f)
        assertTrue(active.rotationRadians > 0f)
        val neutral = policy.transform(1100, parameters.copy(intensity = 0f))
        assertEquals(1.2f, neutral.zoom, 0f)
        assertEquals(0f, neutral.centerOffsetX, 0f)
        assertEquals(0f, neutral.centerOffsetY, 0f)
        assertEquals(0f, neutral.rotationRadians, 0f)
    }

    @Test fun lossAndReacquisitionDoNotReplaceTheDisplayedTransformInstantly() {
        val policy = StabilizationPolicy(); initialize(policy)
        for (index in 1..20) {
            policy.update(observation(.012f, 0f, .002f), index * 100L, parameters)
            policy.transform(index * 100L, parameters)
        }
        val before = policy.transform(2100, parameters)
        policy.update(FrameMotionEstimate(reset = true, reason = "dark"), 2100, parameters)
        val lost = policy.transform(2100, parameters)
        assertEquals(before.centerOffsetX, lost.centerOffsetX, 0f)
        assertEquals(before.rotationRadians, lost.rotationRadians, 0f)
        assertFalse(lost.tracking)
        for (time in 2116L..2700L step 16L) policy.transform(time, parameters)
        policy.update(observation(), 2700, parameters) // Old analysis interval is rejected first.
        val held = policy.transform(2800, parameters)
        policy.update(observation(-.05f, .02f, -.015f), 2800, parameters)
        val reacquired = policy.transform(2800, parameters)
        assertEquals(held.centerOffsetX, reacquired.centerOffsetX, 0f)
        assertEquals(held.rotationRadians, reacquired.rotationRadians, 0f)
        assertTrue(reacquired.tracking)
        assertEquals("recovering", reacquired.reason)
        val next = policy.transform(2816, parameters)
        assertTrue(abs(next.centerOffsetX - reacquired.centerOffsetX) < .012f)
        assertCornersInside(next)
    }

    @Test fun analysisCadenceDoesNotChangeTheSettledPanCorrection() {
        fun simulate(step: Long): Float {
            val policy = StabilizationPolicy(); initialize(policy)
            var analysisAt = step
            var analyzedAt = 0L
            var renderAt = 16L
            while (analyzedAt < 6_000L || renderAt <= 6_000L) {
                val next = minOf(analysisAt, renderAt, 6_000L)
                if (next == analysisAt || (next == 6_000L && analyzedAt < 6_000L)) {
                    policy.update(observation(.08f * (next - analyzedAt) / 1_000f), next, parameters)
                    analyzedAt = next
                    analysisAt = if (next == 6_000L) Long.MAX_VALUE else minOf(next + step, 6_000L)
                }
                if (next == renderAt || next == 6_000L) {
                    policy.transform(next, parameters)
                    renderAt = if (next == 6_000L) Long.MAX_VALUE else next + 16L
                }
            }
            return policy.transform(6_016L, parameters).centerOffsetX
        }
        val rapid = simulate(33L)
        assertEquals(rapid, simulate(67L), .001f)
        assertEquals(rapid, simulate(100L), .001f)
        assertEquals(rapid, simulate(150L), .001f)
    }

    @Test fun purposefulPanTravelsWithTheSceneInsteadOfFreezingAtTheCropEdge() {
        val policy = StabilizationPolicy(); initialize(policy)
        for (index in 1..100) {
            policy.update(observation(.005f), index * 100L, parameters)
            policy.transform(index * 100L, parameters)
        }
        val result = policy.transform(10016L, parameters)
        assertEquals("pan", result.reason)
        assertTrue(result.centerOffsetX > 0f && result.centerOffsetX < .02f)
        assertTrue(.5f - result.centerOffsetX > .48f) // More than 96% of a half-frame pan remains.
    }

    @Test fun stationaryAndMovementModesHaveDifferentIntentionalPanLag() {
        fun result(mode: Int): Float {
            val tuning = parameters.copy(mode = mode)
            val policy = StabilizationPolicy(); initialize(policy, tuning)
            for (index in 1..60) {
                policy.update(observation(.01f), index * 100L, tuning)
                policy.transform(index * 100L, tuning)
            }
            return policy.transform(6016L, tuning).centerOffsetX
        }
        assertTrue(result(1) > result(2) * 1.5f)
    }

    @Test fun nearCropLimitDoesNotAccumulateAnInvisibleLongReturnTrajectory() {
        val policy = StabilizationPolicy(); initialize(policy)
        for (index in 1..40) {
            policy.update(observation(.2f, -.2f, .08f), index * 100L, parameters)
            assertCornersInside(policy.transform(index * 100L, parameters))
        }
        val limit = policy.transform(4090L, parameters)
        assertEquals("crop_limit", limit.reason)
        for (index in 41..55) {
            policy.update(observation(), index * 100L, parameters)
            assertCornersInside(policy.transform(index * 100L, parameters))
        }
        val settled = policy.transform(5590L, parameters)
        assertTrue(abs(settled.centerOffsetX) < abs(limit.centerOffsetX) * .20f)
        assertTrue(abs(settled.rotationRadians) < abs(limit.rotationRadians) * .20f)
    }

    @Test fun turningOffRollReleasesTranslationReserveWhileKeepingTheSameCrop() {
        fun result(roll: Boolean): StabilizationTransform {
            val tuning = parameters.copy(horizonCorrection = roll, aspectRatio = 16f / 9f)
            val policy = StabilizationPolicy(); initialize(policy, tuning)
            for (index in 1..40) {
                policy.update(observation(y = .2f), index * 100L, tuning)
                policy.transform(index * 100L, tuning)
            }
            return policy.transform(4090, tuning)
        }
        val withRoll = result(true)
        val withoutRoll = result(false)
        assertEquals(withRoll.zoom, withoutRoll.zoom, 0f)
        assertEquals(0f, withoutRoll.rotationRadians, 0f)
        assertTrue(withoutRoll.centerOffsetY > withRoll.centerOffsetY * 1.2f)
        assertCornersInside(withRoll); assertCornersInside(withoutRoll)
    }

    @Test fun intensityAndResponseChangesPreserveTheCurrentDisplayUntilTimeAdvances() {
        val policy = StabilizationPolicy(); initialize(policy)
        for (index in 1..20) {
            policy.update(observation(.015f, 0f, .003f), index * 100L, parameters)
            policy.transform(index * 100L, parameters)
        }
        val before = policy.transform(2090, parameters)
        val changed = policy.transform(2090, parameters.copy(intensity = .35f, response = 1f))
        assertEquals(before.centerOffsetX, changed.centerOffsetX, 0f)
        assertEquals(before.rotationRadians, changed.rotationRadians, 0f)
        val next = policy.transform(2106, parameters.copy(intensity = .35f, response = 1f))
        assertTrue(abs(next.centerOffsetX - changed.centerOffsetX) < .012f)
        assertCornersInside(next)
    }
}
