package com.lumacamera.core

import org.junit.Assert.*
import org.junit.Test

class CompositionPolicyTest {
    @Test fun everyGuideFitsAndCentersInPortraitAndLandscape() {
        for ((width, height) in listOf(360f to 780f, 780f to 360f, 1080f to 1920f, 1920f to 1080f)) {
            for (mode in 0..4) {
                val bounds = CompositionPolicy.guideBounds(width, height, mode)
                assertTrue(bounds.left >= 0f && bounds.top >= 0f && bounds.right <= width && bounds.bottom <= height)
                assertEquals(width * .5f, bounds.centerX, .0001f); assertEquals(height * .5f, bounds.centerY, .0001f)
                val ratio = when (mode) { 1 -> 1f; 2 -> 9f / 16f; 3 -> 16f / 9f; 4 -> 2.39f; else -> width / height }
                assertEquals(ratio, bounds.width / bounds.height, .00001f)
                assertTrue(bounds.width == width || bounds.height == height)
            }
        }
    }

    @Test fun safeAreaIsNinetyPercentOfTheActiveGuideAndSharesItsCenter() {
        for (mode in 0..4) {
            val guide = CompositionPolicy.guideBounds(360f, 780f, mode)
            val safe = CompositionPolicy.safeBounds(guide)
            assertEquals(guide.width * .9f, safe.width, .0001f); assertEquals(guide.height * .9f, safe.height, .0001f)
            assertEquals(guide.centerX, safe.centerX, .0001f); assertEquals(guide.centerY, safe.centerY, .0001f)
            assertTrue(safe.left >= guide.left && safe.right <= guide.right && safe.top >= guide.top && safe.bottom <= guide.bottom)
        }
    }

    @Test fun invalidDimensionsAndModesCannotProduceInfiniteGeometry() {
        for (dimension in listOf(Float.NaN, Float.POSITIVE_INFINITY, -10f, 0f, Float.MAX_VALUE)) {
            for (mode in listOf(-1, 0, 1, 2, 3, 4, 99)) {
                val guide = CompositionPolicy.guideBounds(dimension, 780f, mode)
                val safe = CompositionPolicy.safeBounds(guide, Float.NaN)
                for (value in listOf(guide.left, guide.top, guide.right, guide.bottom, safe.left, safe.top, safe.right, safe.bottom))
                    assertTrue(value.isFinite() && value >= 0f)
            }
        }
        assertEquals(0, CompositionPolicy.normalizeFrameGuide(99)); assertEquals(0, CompositionPolicy.normalizeGridMode(-1))
    }

    @Test fun gridModesHaveTheExpectedSymmetricFractionsAndDefensiveArrays() {
        assertArrayEquals(floatArrayOf(1f / 3f, 2f / 3f), CompositionPolicy.gridFractions(0), .000001f)
        assertArrayEquals(floatArrayOf(.5f), CompositionPolicy.gridFractions(1), 0f)
        val golden = CompositionPolicy.gridFractions(2)
        assertEquals(1f, golden.sum(), .000001f); assertEquals(.381966f, golden[0], .000001f)
        golden[0] = 0f
        assertTrue(CompositionPolicy.gridFractions(2)[0] > .38f)
    }

    @Test fun safeAreaFractionIsBoundedAndInvalidBoundsAreNeutral() {
        val bounds = CompositionPolicy.Bounds(20f, 30f, 100f, 200f)
        assertEquals(bounds, CompositionPolicy.safeBounds(bounds, 5f))
        val empty = CompositionPolicy.safeBounds(bounds, -1f)
        assertEquals(0f, empty.width, 0f); assertEquals(0f, empty.height, 0f)
        assertEquals(CompositionPolicy.Bounds(0f, 0f, 0f, 0f),
            CompositionPolicy.safeBounds(CompositionPolicy.Bounds(Float.NaN, 0f, 1f, 1f)))
    }

    @Test fun levelNormalizationPreservesSignedTiltAndTreatsUpsideDownAsHorizontal() {
        assertEquals(10f, CompositionPolicy.normalizeRoll(190f), .0001f)
        assertEquals(-10f, CompositionPolicy.normalizeRoll(-190f), .0001f)
        assertEquals(0f, CompositionPolicy.normalizeRoll(180f), 0f)
        assertTrue(CompositionPolicy.isLevel(1.5f)); assertFalse(CompositionPolicy.isLevel(2f))
        assertFalse(CompositionPolicy.isLevel(Float.NaN)); assertTrue(CompositionPolicy.normalizeRoll(Float.POSITIVE_INFINITY).isNaN())
    }

    @Test fun matrixGravityProjectionProducesLevelInEveryDisplayRotation() {
        for ((turn, vector) in listOf(0 to (0f to 1f), 90 to (-1f to 0f), 180 to (0f to -1f), 270 to (1f to 0f)))
            assertEquals(0f, CompositionPolicy.horizonRoll(vector.first, vector.second, turn)!!, .00001f)
        assertEquals(-30f, CompositionPolicy.horizonRoll(-.5f, .8660254f)!!, .0001f)
        assertEquals(30f, CompositionPolicy.horizonRoll(.5f, .8660254f)!!, .0001f)
    }

    @Test fun aimingAtFloorOrSkyAndInvalidSensorValuesDoNotClaimALevelHorizon() {
        assertNull(CompositionPolicy.horizonRoll(0f, 0f)); assertNull(CompositionPolicy.horizonRoll(.01f, .02f))
        assertNull(CompositionPolicy.horizonRoll(Float.NaN, 1f)); assertNull(CompositionPolicy.horizonRoll(1f, 1f))
        assertNull(CompositionPolicy.horizonRoll(0f, 1f, 45))
    }
}
