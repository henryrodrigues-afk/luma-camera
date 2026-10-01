package com.lumacamera.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExposureAutomationTest {
    private fun next(
        isoEnabled: Boolean = true,
        shutterEnabled: Boolean = false,
        iso: Int = 400,
        exposureNs: Long = 10_000_000L,
        luminance: Float = 0.1f,
        targetLuminance: Float = 0.45f,
        minIso: Int = 100,
        maxIso: Int = 1600,
        minExposureNs: Long = 100_000L,
        maxExposureNs: Long = 100_000_000L,
        frameDurationNs: Long = 33_333_333L,
    ) = ExposureAutomation.next(
        isoEnabled, shutterEnabled, iso, exposureNs, luminance, targetLuminance,
        minIso, maxIso, minExposureNs, maxExposureNs, frameDurationNs,
    )

    @Test fun manualIsoKeepsIsoAndMetersOnlyShutter() {
        val result = next()
        assertEquals(400, result.iso)
        assertEquals(14_000_000L, result.exposureNs)
        val bright = next(luminance = 1f)
        assertEquals(400, bright.iso)
        assertTrue(bright.exposureNs in 7_000_000L until 10_000_000L)
    }

    @Test fun manualShutterKeepsShutterAndMetersOnlyIso() {
        val result = next(isoEnabled = false, shutterEnabled = true)
        assertEquals(560, result.iso)
        assertEquals(10_000_000L, result.exposureNs)
        val bright = next(isoEnabled = false, shutterEnabled = true, luminance = 1f)
        assertTrue(bright.iso in 280 until 400)
        assertEquals(10_000_000L, bright.exposureNs)
    }

    @Test fun bothManualAndBothAutoDoNotMeter() {
        for (enabled in listOf(true, false)) {
            assertEquals(ExposureSolution(400, 10_000_000L), next(enabled, enabled))
            assertEquals(ExposureSolution(400, 10_000_000L),
                next(enabled, enabled, luminance = 1f))
        }
    }

    @Test fun correctLuminanceHoldsExposure() {
        assertEquals(ExposureSolution(400, 10_000_000L), next(luminance = 0.45f))
        assertEquals(ExposureSolution(400, 10_000_000L),
            next(isoEnabled = false, shutterEnabled = true, luminance = 0.45f))
    }

    @Test fun pitchBlackAndSaturatedSamplesUseBoundedSteps() {
        assertEquals(14_000_000L, next(luminance = 0f).exposureNs)
        assertEquals(14_000_000L, next(luminance = -100f).exposureNs)
        assertEquals(7_000_000L,
            next(luminance = 100f, targetLuminance = 0f).exposureNs)
        assertEquals(14_000_000L,
            next(luminance = 0.01f, targetLuminance = 100f).exposureNs)
    }

    @Test fun nonFiniteMeterSamplesHoldAndInvalidTargetDefaults() {
        for (sample in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(ExposureSolution(400, 10_000_000L), next(luminance = sample))
            assertEquals(ExposureSolution(400, 10_000_000L),
                next(isoEnabled = false, shutterEnabled = true, luminance = sample))
        }
        assertEquals(14_000_000L, next(targetLuminance = Float.NaN).exposureNs)
    }

    @Test fun requestedValuesAlwaysRespectSensorAndFrameBounds() {
        for (flags in listOf(true to true, false to false, true to false, false to true)) {
            assertEquals(ExposureSolution(1600, 33_333_333L), next(
                flags.first, flags.second, iso = Int.MAX_VALUE, exposureNs = Long.MAX_VALUE,
                luminance = 0f,
            ))
            assertEquals(ExposureSolution(100, 100_000L), next(
                flags.first, flags.second, iso = Int.MIN_VALUE, exposureNs = Long.MIN_VALUE,
                luminance = 1f, targetLuminance = 0f,
            ))
        }
    }

    @Test fun automaticShutterUsesTighterSensorOrFrameMaximum() {
        assertEquals(20_000_000L,
            next(exposureNs = 19_000_000L, maxExposureNs = 20_000_000L).exposureNs)
        assertEquals(33_333_333L, next(exposureNs = 30_000_000L).exposureNs)
    }

    @Test fun repeatedMeteringConvergesWithoutMovingManualControl() {
        var solution = ExposureSolution(400, 1_000_000L)
        repeat(40) {
            // Synthetic linear sensor response with the target at 10 ms.
            val luma = (solution.exposureNs / 10_000_000.0 * 0.45).toFloat()
            solution = next(iso = solution.iso, exposureNs = solution.exposureNs, luminance = luma)
        }
        assertEquals(400, solution.iso)
        assertTrue(solution.exposureNs in 9_990_000L..10_010_000L)
    }

    @Test fun extremeSensorBoundsDoNotOverflow() {
        val result = next(isoEnabled = false, shutterEnabled = true,
            iso = Int.MAX_VALUE, exposureNs = Long.MAX_VALUE, minIso = 1,
            maxIso = Int.MAX_VALUE, minExposureNs = 1,
            maxExposureNs = Long.MAX_VALUE, frameDurationNs = Long.MAX_VALUE, luminance = 0f)
        assertEquals(ExposureSolution(Int.MAX_VALUE, Long.MAX_VALUE), result)
        assertEquals(Long.MAX_VALUE, next(exposureNs = Long.MAX_VALUE,
            maxExposureNs = Long.MAX_VALUE, frameDurationNs = Long.MAX_VALUE,
            luminance = 0f).exposureNs)
    }

    @Test(expected = IllegalArgumentException::class)
    fun impossibleFrameBudgetIsRejected() { next(minExposureNs = 40_000_000L) }

    @Test(expected = IllegalArgumentException::class)
    fun invertedIsoRangeIsRejected() { next(minIso = 2000) }

    @Test(expected = IllegalArgumentException::class)
    fun invertedExposureRangeIsRejected() { next(maxExposureNs = 1) }

    @Test(expected = IllegalArgumentException::class)
    fun nonPositiveFrameDurationIsRejected() { next(frameDurationNs = 0) }
}
