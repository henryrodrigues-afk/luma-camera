package com.lumacamera.core

import org.junit.Assert.*
import org.junit.Test

class HistogramDisplayPolicyTest {
    @Test fun snapshotOwnsItsCountsAndScalesEachBarToThePeak() {
        val input = IntArray(64).apply { this[0] = 10; this[32] = 20 }
        val result = HistogramDisplayPolicy.snapshot(input, .2f, .05f, .4f)
        input.fill(0)
        assertTrue(result.hasData); assertEquals(30L, result.totalCount); assertEquals(20, result.peakCount)
        assertEquals(.5f, result.heightAt(0), 0f); assertEquals(1f, result.heightAt(32), 0f)
        assertEquals(.2f, result.shadowClip, 0f); assertEquals(.05f, result.highlightClip, 0f); assertEquals(.4f, result.mean, 0f)
    }

    @Test fun emptyOrWrongSizedInputHasNoDataAndNoDivisionByZero() {
        for (input in listOf(IntArray(0), IntArray(63) { 1 }, IntArray(65) { 1 }, IntArray(64))) {
            val result = HistogramDisplayPolicy.snapshot(input)
            assertFalse(result.hasData); assertEquals(0f, result.heightAt(0), 0f)
            assertEquals(0f, result.heightAt(-1), 0f); assertEquals(0f, result.heightAt(64), 0f)
        }
    }

    @Test fun negativeCountsAndNonFiniteMetadataAreSanitized() {
        val result = HistogramDisplayPolicy.snapshot(IntArray(64) { -7 }, Float.NaN, Float.POSITIVE_INFINITY, Float.NaN)
        assertFalse(result.hasData); assertEquals(0f, result.shadowClip, 0f); assertEquals(0f, result.highlightClip, 0f)
        assertEquals(.5f, result.mean, 0f)
        val bounded = HistogramDisplayPolicy.snapshot(IntArray(64) { 1 }, -1f, 3f, 2f)
        assertEquals(0f, bounded.shadowClip, 0f); assertEquals(1f, bounded.highlightClip, 0f); assertEquals(1f, bounded.mean, 0f)
    }

    @Test fun largeCountsDoNotOverflowTheTotalOrBarHeights() {
        val result = HistogramDisplayPolicy.snapshot(IntArray(64) { Int.MAX_VALUE })
        assertEquals(Int.MAX_VALUE.toLong() * 64, result.totalCount)
        for (index in 0 until 64) assertEquals(1f, result.heightAt(index), 0f)
    }
}
