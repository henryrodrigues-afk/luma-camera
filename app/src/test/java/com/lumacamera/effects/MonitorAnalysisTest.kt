package com.lumacamera.effects

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class MonitorAnalysisTest {
    private fun pixels(vararg rgb: IntArray) = ByteBuffer.allocate(rgb.size * 4).apply {
        rgb.forEach { color -> put(color[0].toByte()); put(color[1].toByte()); put(color[2].toByte()); put(255.toByte()) }
        rewind()
    }

    @Test fun blackWhiteGrayAndRedProduceWeightedLumaAndClippingFractions() {
        val histogram = MonitorAnalysis.histogram(pixels(intArrayOf(0, 0, 0), intArrayOf(255, 255, 255),
            intArrayOf(128, 128, 128), intArrayOf(255, 0, 0)), 4, 1)
        assertEquals(64, histogram.bins.size)
        assertEquals(4, histogram.bins.sum())
        assertEquals(1, histogram.bins[0]); assertEquals(1, histogram.bins[63])
        assertEquals(1, histogram.bins[32]); assertEquals(1, histogram.bins[13])
        assertEquals(.25f, histogram.shadowClip, .00001f)
        assertEquals(.25f, histogram.highlightClip, .00001f)
        assertEquals((1f + 128f / 255f + .2126f) / 4f, histogram.mean, .00001f)
    }

    @Test fun encodedLogLevelsStayEncodedAndDoNotReportSensorClipping() {
        val histogram = MonitorAnalysis.histogram(pixels(intArrayOf(24, 24, 24), intArrayOf(239, 239, 239)), 2, 1)
        assertEquals(1, histogram.bins[6]); assertEquals(1, histogram.bins[59])
        assertEquals(0f, histogram.shadowClip, 0f)
        assertEquals(0f, histogram.highlightClip, 0f)
        assertEquals(263f / 510f, histogram.mean, .00001f)
    }

    @Test fun binEdgesClampWhiteIntoLastBinAndIgnoreAlpha() {
        val buffer = pixels(intArrayOf(255, 255, 255), intArrayOf(0, 0, 0))
        buffer.put(3, 0); buffer.put(7, 17)
        val histogram = MonitorAnalysis.histogram(buffer, 1, 2)
        assertEquals(1, histogram.bins[63]); assertEquals(1, histogram.bins[0])
        assertEquals(.5f, histogram.mean, .00001f)
    }

    @Test fun readbackPositionIsPreservedAndEachSnapshotOwnsItsBins() {
        val buffer = pixels(intArrayOf(128, 128, 128))
        buffer.position(3)
        val first = MonitorAnalysis.histogram(buffer, 1, 1)
        assertEquals(3, buffer.position())
        buffer.put(0, 0); buffer.put(1, 0); buffer.put(2, 0)
        val second = MonitorAnalysis.histogram(buffer, 1, 1)
        assertNotSame(first.bins, second.bins)
        assertEquals(1, first.bins[32]); assertEquals(1, second.bins[0])
        assertEquals(128f / 255f, first.mean, .00001f)
    }

    @Test fun nearEndpointLevelsUseOnePercentThreshold() {
        val histogram = MonitorAnalysis.histogram(pixels(intArrayOf(2, 2, 2), intArrayOf(3, 3, 3),
            intArrayOf(252, 252, 252), intArrayOf(253, 253, 253)), 4, 1)
        assertEquals(.25f, histogram.shadowClip, .00001f)
        assertEquals(.25f, histogram.highlightClip, .00001f)
    }

    @Test(expected = IllegalArgumentException::class) fun insufficientBufferIsRejected() {
        MonitorAnalysis.histogram(ByteBuffer.allocate(3), 1, 1)
    }

    @Test(expected = IllegalArgumentException::class) fun zeroDimensionsAreRejected() {
        MonitorAnalysis.histogram(ByteBuffer.allocate(4), 0, 1)
    }

    @Test(expected = IllegalArgumentException::class) fun overflowDimensionsAreRejectedBeforeIndexing() {
        MonitorAnalysis.histogram(ByteBuffer.allocate(4), Int.MAX_VALUE, Int.MAX_VALUE)
    }

    @Test fun waveformPreservesHorizontalPositionAndRecordedLuma() {
        val scopes = MonitorAnalysis.scopes(pixels(intArrayOf(0, 0, 0), intArrayOf(255, 255, 255)),
            2, 1, true, false, false)
        assertEquals(1, scopes.waveform[0]); assertEquals(1, scopes.waveform[63 * 64 + 32])
        assertEquals(2, scopes.waveform.sum()); assertEquals(2, scopes.histogram.bins.sum())
        assertTrue(scopes.rgbParade.isEmpty()); assertTrue(scopes.vectorscope.isEmpty())
    }
    @Test fun rgbParadeSeparatesColorChannelsRatherThanLuma() {
        val scopes = MonitorAnalysis.scopes(pixels(intArrayOf(255, 0, 128)), 1, 1, false, true, false)
        val slice = 64 * 64
        assertEquals(1, scopes.rgbParade[63 * 64])
        assertEquals(1, scopes.rgbParade[slice])
        assertEquals(1, scopes.rgbParade[2 * slice + 32 * 64])
        assertEquals(3, scopes.rgbParade.sum())
    }
    @Test fun vectorscopeNeutralHasNoChromaAndPrimaryColorsSeparate() {
        val scopes = MonitorAnalysis.scopes(pixels(intArrayOf(128, 128, 128), intArrayOf(255, 0, 0),
            intArrayOf(0, 0, 255)), 3, 1, false, false, true)
        assertEquals(1, scopes.vectorscope[31 * 64 + 31])
        assertEquals(3, scopes.vectorscope.sum())
        assertEquals(1, scopes.vectorscope[63 * 64 + 24])
        assertEquals(1, scopes.vectorscope[28 * 64 + 63])
    }
    @Test fun disabledScopesAllocateNoMapsAndSnapshotsPreserveInputPosition() {
        val buffer = pixels(intArrayOf(24, 24, 24), intArrayOf(239, 239, 239))
        buffer.position(2)
        val first = MonitorAnalysis.scopes(buffer, 2, 1, true, true, true)
        assertEquals(2, buffer.position())
        val off = MonitorAnalysis.scopes(buffer, 2, 1, false, false, false)
        assertTrue(off.waveform.isEmpty()); assertTrue(off.rgbParade.isEmpty()); assertTrue(off.vectorscope.isEmpty())
        assertEquals(1, first.histogram.bins[6]); assertEquals(1, first.histogram.bins[59])
        val second = MonitorAnalysis.scopes(buffer, 2, 1, true, true, true)
        assertNotSame(first.waveform, second.waveform)
        assertNotSame(first.rgbParade, second.rgbParade); assertNotSame(first.vectorscope, second.vectorscope)
    }
}
