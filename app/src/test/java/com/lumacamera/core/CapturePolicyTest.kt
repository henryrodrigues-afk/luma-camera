package com.lumacamera.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class CapturePolicyTest {
    private val encoder = EncoderLimits(320, 1920, 240, 1080, 16, 8, 20_000_000)

    @Test fun bitrateUsesPixelBudgetAndRespectsBothCaps() {
        assertEquals(8_709_120, CapturePolicy.boundedBitrate(1920, 1080, 30, 20_000_000))
        assertEquals(2_000_000, CapturePolicy.boundedBitrate(320, 240, 24, 20_000_000))
        assertEquals(800_000, CapturePolicy.boundedBitrate(320, 240, 24, 800_000))
        assertEquals(40_000_000, CapturePolicy.boundedBitrate(7680, 4320, 30, 80_000_000))
        assertEquals(12_000_000, CapturePolicy.boundedBitrate(7680, 4320, 30, 12_000_000))
        assertEquals(40_000_000, CapturePolicy.boundedBitrate(Int.MAX_VALUE, Int.MAX_VALUE, 30, Int.MAX_VALUE))
    }

    @Test fun exposureFitsSensorAndFrameBudget() {
        assertEquals(33_333_333L, CapturePolicy.frameDurationNs(30))
        assertEquals(41_666_666L, CapturePolicy.frameDurationNs(24))
        assertEquals(33_333_333L, CapturePolicy.clampExposureNs(100_000_000, 100_000, 200_000_000, 30))
        assertEquals(100_000L, CapturePolicy.clampExposureNs(1, 100_000, 200_000_000, 30))
        assertEquals(20_000_000L, CapturePolicy.clampExposureNs(100_000_000, 100_000, 20_000_000, 30))
        assertEquals(10_000_000L, CapturePolicy.clampExposureNs(10_000_000, 100_000, 200_000_000, 30))
    }

    @Test(expected = IllegalArgumentException::class)
    fun impossibleSensorMinimumIsRejected() {
        CapturePolicy.clampExposureNs(50_000_000, 40_000_000, 100_000_000, 30)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invertedSensorBoundsAreRejected() {
        CapturePolicy.clampExposureNs(10, 20, 10, 30)
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroFrameRateIsRejected() {
        CapturePolicy.frameDurationNs(0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonPositiveBitrateCeilingIsRejected() {
        CapturePolicy.boundedBitrate(1920, 1080, 30, 0)
    }

    @Test fun negotiationPreservesCameraSizesFiltersEncoderAndDeduplicates() {
        val modes = CapturePolicy.compatibleModes(
            listOf(1280 to 720, 1920 to 1080, 1920 to 1080, 3840 to 2160,
                1919 to 1080, 1920 to 1079, 160 to 120, 1080 to 1920),
            listOf(15..30, 24..30), encoder,
        )
        assertEquals(listOf(1920 to 1080, 1920 to 1080, 1280 to 720, 1280 to 720),
            modes.map { it.width to it.height })
        assertEquals(listOf(30, 24, 30, 24), modes.map { it.fps })
        assertTrue(modes.all { it.bitrate <= encoder.maxBitrate })
    }

    @Test fun negotiationUsesRangeMembershipAndMaximumFrameRate() {
        assertEquals(listOf(24), CapturePolicy.compatibleModes(
            listOf(1280 to 720), listOf(15..30), encoder, maxFps = 24,
        ).map { it.fps })
        assertEquals(listOf(30), CapturePolicy.compatibleModes(
            listOf(1280 to 720), listOf(30..30), encoder,
        ).map { it.fps })
        assertTrue(CapturePolicy.compatibleModes(
            listOf(1280 to 720), listOf(15..23, 25..29), encoder,
        ).isEmpty())
        assertTrue(CapturePolicy.compatibleModes(
            emptyList(), listOf(15..30), encoder,
        ).isEmpty())
    }

    @Test fun bitrateQualityPresetsStayIndependentOfVideoGeometry() {
        assertEquals(4_800_000, CapturePolicy.scaledBitrate(8_000_000, .6f))
        assertEquals(8_000_000, CapturePolicy.scaledBitrate(8_000_000, 1f))
        assertEquals(12_000_000, CapturePolicy.scaledBitrate(8_000_000, 1.5f))
        assertEquals(4_000_000, CapturePolicy.scaledBitrate(8_000_000, -10f))
        assertEquals(16_000_000, CapturePolicy.scaledBitrate(8_000_000, 10f))
        assertEquals(8_000_000, CapturePolicy.scaledBitrate(8_000_000, Float.NaN))
        assertEquals(8_000_000, CapturePolicy.scaledBitrate(8_000_000, Float.POSITIVE_INFINITY))
    }

    @Test fun finalEncoderRangeClampsBothEndsWithoutIntegerOverflow() {
        assertEquals(10_000_000, CapturePolicy.scaledBitrate(8_000_000, 1.5f, 1_000_000, 10_000_000))
        assertEquals(6_000_000, CapturePolicy.scaledBitrate(8_000_000, .5f, 6_000_000, 10_000_000))
        assertEquals(Int.MAX_VALUE, CapturePolicy.scaledBitrate(Int.MAX_VALUE, 2f))
        assertEquals(6_000_000, CapturePolicy.boundedBitrate(320, 240, 24, 10_000_000, 6_000_000))
        val constrained = encoder.copy(minBitrate = 10_000_000, maxBitrate = 12_000_000)
        assertTrue(CapturePolicy.compatibleModes(listOf(1280 to 720), listOf(24..30), constrained)
            .all { it.bitrate in constrained.minBitrate..constrained.maxBitrate })
    }

    @Test(expected = IllegalArgumentException::class)
    fun invertedFinalEncoderBitrateRangeIsRejected() {
        CapturePolicy.scaledBitrate(8_000_000, 1f, 10_000_000, 9_000_000)
    }

    @Test fun exposureAndWhiteBalanceLocksOnlyOwnSupportedAutomaticControllers() {
        assertTrue(CapturePolicy.autoLock(requested = true, supported = true, automatic = true))
        assertFalse(CapturePolicy.autoLock(requested = false, supported = true, automatic = true))
        assertFalse(CapturePolicy.autoLock(requested = true, supported = false, automatic = true))
        // AE_OFF for ISO/shutter, or a selected non-AUTO white balance, must not become locked.
        assertFalse(CapturePolicy.autoLock(requested = true, supported = true, automatic = false))
        val ae = CapturePolicy.autoLock(true, true, false)
        val awb = CapturePolicy.autoLock(true, true, true)
        assertFalse(ae); assertTrue(awb)
    }

    @Test fun audioPeakIsLinearBoundedAndNeverPretendsToMeasureDecibels() {
        assertEquals(0f, CapturePolicy.audioPeak(-10), 0f)
        assertEquals(0f, CapturePolicy.audioPeak(0), 0f)
        assertEquals(.5f, CapturePolicy.audioPeak(16_384), .0001f)
        assertEquals(1f, CapturePolicy.audioPeak(32_767), 0f)
        assertEquals(1f, CapturePolicy.audioPeak(Int.MAX_VALUE), 0f)
    }
}
