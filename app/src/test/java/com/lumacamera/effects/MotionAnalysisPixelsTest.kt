package com.lumacamera.effects

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class MotionAnalysisPixelsTest {
    @Test fun cpuLumaUsesRgbSignalAndPreservesReadbackPosition() {
        val rgba = ByteBuffer.wrap(byteArrayOf(255.toByte(), 0, 0, 17, 0, 255.toByte(), 0, 0))
        rgba.position(3)
        val destination = FloatArray(2)
        assertSame(destination, MotionAnalysisPixels.luma(rgba, 2, 1, destination))
        assertEquals(.2126f, destination[0], .00001f); assertEquals(.7152f, destination[1], .00001f)
        assertEquals(3, rgba.position())
    }
    @Test fun ownedResampledMaskDoesNotRetainMutableInputArray() {
        val source = floatArrayOf(0f, 1f, 1f, 0f)
        val mask = PortraitMaskPolicy.Mask(2, 2, source, 900)
        val own = MotionAnalysisPixels.maskSnapshot(mask, 2, 2, 1_000, FloatArray(4))!!
        source.fill(0f)
        assertArrayEquals(floatArrayOf(0f, 1f, 1f, 0f), own.confidence, 0f)
        assertEquals(900L, own.capturedAtMs)
    }
    @Test fun missingInvalidFutureAndExpiredMasksDoNotGuideMotion() {
        val destination = FloatArray(4)
        assertNull(MotionAnalysisPixels.maskSnapshot(null, 2, 2, 1_000, destination))
        assertNull(MotionAnalysisPixels.maskSnapshot(PortraitMaskPolicy.Mask(2, 2, FloatArray(3), 900), 2, 2, 1_000, destination))
        assertNull(MotionAnalysisPixels.maskSnapshot(PortraitMaskPolicy.Mask(2, 2, FloatArray(4), 1_100), 2, 2, 1_000, destination))
        assertNull(MotionAnalysisPixels.maskSnapshot(PortraitMaskPolicy.Mask(2, 2, FloatArray(4), 399), 2, 2, 1_000, destination))
    }
    @Test fun invalidReadbackAndDestinationDimensionsFailBeforeIndexing() {
        assertThrows(IllegalArgumentException::class.java) { MotionAnalysisPixels.luma(ByteBuffer.allocate(3), 1, 1, FloatArray(1)) }
        assertThrows(IllegalArgumentException::class.java) { MotionAnalysisPixels.luma(ByteBuffer.allocate(4), 2, 2, FloatArray(1)) }
    }
}
