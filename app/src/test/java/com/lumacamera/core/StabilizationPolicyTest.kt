package com.lumacamera.core

import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

class StabilizationPolicyTest {
    private val tuning = StabilizationTuning(intensity = 1f, response = .5f, cropZoom = 1.2f)
    private fun motion(x: Float = 0f, y: Float = 0f) = FrameMotionEstimate(x, y, 1f, true, reason = "tracked")
    private fun initialize(policy: StabilizationPolicy, params: StabilizationTuning = tuning) {
        policy.update(FrameMotionEstimate(reset = true), 0, params)
        policy.transform(0, params)
    }

    @Test fun defaultTransformAndDisabledTuningAreIdentity() {
        assertEquals(StabilizationTransform(), StabilizationPolicy().transform(0, tuning.copy(enabled = false)))
        assertEquals(.25f to .75f, StabilizationTransform().sourcePoint(.25f, .75f))
    }

    @Test fun staticFramesKeepOffsetsAtZeroWhileReservingTheConfiguredCrop() {
        val policy = StabilizationPolicy(); initialize(policy)
        for (time in 100L..1000L step 100L) {
            policy.update(motion(), time, tuning)
            val result = policy.transform(time, tuning)
            assertEquals(1.2f, result.zoom, 0f)
            assertEquals(0f, result.centerOffsetX, 0f); assertEquals(0f, result.centerOffsetY, 0f)
        }
    }

    @Test fun aTranslatedSceneIsCompensatedWithTheSameSignWithoutAnInstantRenderStep() {
        val policy = StabilizationPolicy(); initialize(policy)
        policy.transform(90, tuning)
        policy.update(motion(.02f, -.01f), 100, tuning)
        val first = policy.transform(100, tuning)
        val next = policy.transform(116, tuning)
        assertTrue(first.centerOffsetX > 0f); assertTrue(first.centerOffsetY < 0f)
        assertTrue(next.centerOffsetX > first.centerOffsetX)
        assertTrue(next.centerOffsetX < .02f)
    }

    @Test fun highFrequencyTremorIsReducedByTheCausalTrajectory() {
        val policy = StabilizationPolicy(); initialize(policy)
        var raw = 0f; var rawMovement = 0f; var stabilizedMovement = 0f; var previousOutput = 0f
        for (index in 1..40) {
            val dx = if (index % 2 == 1) .025f else -.025f
            val time = index * 100L
            raw += dx; rawMovement += abs(dx)
            policy.update(motion(dx), time, tuning)
            for (offset in listOf(0L, 16L, 33L, 50L, 66L, 83L)) policy.transform(time + offset, tuning)
            val output = raw - policy.transform(time + 90, tuning).centerOffsetX
            stabilizedMovement += abs(output - previousOutput); previousOutput = output
        }
        assertTrue("raw=$rawMovement stabilized=$stabilizedMovement", stabilizedMovement < rawMovement * .6f)
    }

    @Test fun sustainedPansStayBoundedAndRecenterAfterThePanStops() {
        val policy = StabilizationPolicy(); initialize(policy)
        for (index in 1..100) {
            val time = index * 100L
            policy.update(motion(.025f), time, tuning)
            val result = policy.transform(time, tuning)
            assertTrue(abs(result.centerOffsetX) <= .5f * (1f - 1f / result.zoom) + .000001f)
        }
        for (index in 101..135) {
            val time = index * 100L
            policy.update(motion(), time, tuning); policy.transform(time, tuning)
        }
        assertEquals(0f, policy.transform(13520, tuning).centerOffsetX, .001f)
    }

    @Test fun strongerIntensityPreservesMoreCompensationAndFastResponseFollowsPans() {
        fun correction(params: StabilizationTuning): Float {
            val policy = StabilizationPolicy(); initialize(policy, params)
            for (index in 1..20) {
                policy.update(motion(.012f), index * 100L, params)
                policy.transform(index * 100L, params)
            }
            return policy.transform(2020, params).centerOffsetX
        }
        assertTrue(correction(tuning) > correction(tuning.copy(intensity = .2f)))
        assertTrue(correction(tuning.copy(response = 0f)) > correction(tuning.copy(response = 1f)))
    }

    @Test fun sceneCutsDiscardOldCorrectionButLongGapsReleaseItSmoothly() {
        val policy = StabilizationPolicy(); initialize(policy)
        policy.update(motion(.04f), 100, tuning); policy.transform(100, tuning)
        policy.update(FrameMotionEstimate(reset = true, reason = "scene_cut"), 200, tuning)
        assertEquals(0f, policy.transform(200, tuning).centerOffsetX, 0f)
        assertEquals("scene_cut", policy.transform(216, tuning).reason)
        policy.update(motion(.04f), 300, tuning); policy.transform(300, tuning)
        val beforeGap = policy.transform(390, tuning).centerOffsetX
        val afterGap = policy.transform(1000, tuning)
        assertTrue(afterGap.centerOffsetX > 0f)
        assertTrue(afterGap.centerOffsetX < beforeGap)
        assertFalse(afterGap.tracking)
        assertEquals("frame_gap", afterGap.reason)
    }

    @Test fun everySampleRemainsInsideTheSourceEvenAtCropBounds() {
        val policy = StabilizationPolicy(); initialize(policy)
        for (index in 1..30) {
            policy.update(motion(.1f, -.1f), index * 100L, tuning)
            val result = policy.transform(index * 100L, tuning)
            for (x in listOf(0f, .5f, 1f)) for (y in listOf(0f, .5f, 1f)) {
                val source = result.sourcePoint(x, y)
                assertTrue(source.first in 0f..1f); assertTrue(source.second in 0f..1f)
            }
        }
    }

    @Test fun invalidValuesAlwaysProduceFiniteBoundedTransforms() {
        val policy = StabilizationPolicy()
        val invalid = tuning.copy(intensity = Float.NaN, response = Float.POSITIVE_INFINITY, cropZoom = Float.NaN)
        policy.update(motion(Float.NaN, Float.POSITIVE_INFINITY), 100, invalid)
        val result = policy.transform(110, invalid)
        assertTrue(result.zoom.isFinite()); assertTrue(result.centerOffsetX.isFinite()); assertTrue(result.centerOffsetY.isFinite())
        assertEquals(0f, result.centerOffsetX, 0f)
        assertEquals(.5f to .5f, StabilizationTransform(Float.NaN, Float.NaN, Float.NaN).sourcePoint(Float.NaN, Float.NaN))
    }

    @Test fun disablingResetsHistoryAndCropChangesDoNotReuseTheOldTarget() {
        val policy = StabilizationPolicy(); initialize(policy)
        policy.update(motion(.04f), 100, tuning); policy.transform(100, tuning)
        assertEquals(StabilizationTransform(), policy.transform(116, tuning.copy(enabled = false)))
        assertEquals(0f, policy.transform(200, tuning).centerOffsetX, 0f)
        policy.update(motion(.04f), 300, tuning); policy.transform(300, tuning)
        val changed = policy.transform(316, tuning.copy(cropZoom = 1.05f))
        assertEquals(1.05f, changed.zoom, 0f); assertEquals(0f, changed.centerOffsetX, 0f)
    }
}
