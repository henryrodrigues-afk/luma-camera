package com.lumacamera.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusMeteringPolicyTest {
    private val sensor = FocusBounds(0, 0, 4000, 3000)

    @Test fun sixteenByNinePreviewExcludesSensorTopAndBottom() {
        assertEquals(FocusBounds(0, 375, 4000, 2625), FocusMeteringPolicy.visibleBounds(sensor, 1920, 1080))
    }

    @Test fun squarePreviewExcludesSensorSides() {
        assertEquals(FocusBounds(500, 0, 3500, 3000), FocusMeteringPolicy.visibleBounds(sensor, 1080, 1080))
    }

    @Test fun matchingAspectKeepsTheWholeSensor() {
        assertEquals(sensor, FocusMeteringPolicy.visibleBounds(sensor, 640, 480))
    }

    @Test fun zoomCropAndOffsetRemainInSensorCoordinates() {
        val crop = FocusBounds(1000, 600, 3000, 2100)
        assertEquals(FocusBounds(1000, 787, 3000, 1912), FocusMeteringPolicy.visibleBounds(sensor, 1920, 1080, crop))
        val offset = FocusBounds(50, 30, 4050, 3030)
        assertEquals(FocusBounds(50, 405, 4050, 2655), FocusMeteringPolicy.visibleBounds(offset, 1920, 1080))
    }

    @Test fun partialCropIsIntersectedAndDisjointCropFallsBack() {
        assertEquals(FocusBounds(0, 0, 2000, 1500), FocusMeteringPolicy.visibleBounds(sensor, 4, 3,
            FocusBounds(-1000, -1000, 2000, 1500)))
        assertEquals(sensor, FocusMeteringPolicy.visibleBounds(sensor, 4, 3,
            FocusBounds(5000, 5000, 6000, 6000)))
    }

    @Test fun tapsAtAllEdgesProduceValidRectanglesInsideVisibleCrop() {
        val visible = FocusMeteringPolicy.visibleBounds(sensor, 1920, 1080)
        for (x in listOf(-100f, 0f, .5f, 1f, 100f)) for (y in listOf(-100f, 0f, .5f, 1f, 100f)) {
            val region = FocusMeteringPolicy.region(visible, x, y)
            assertEquals(480, region.width); assertEquals(270, region.height)
            assertTrue(region.left >= visible.left && region.right <= visible.right)
            assertTrue(region.top >= visible.top && region.bottom <= visible.bottom)
        }
    }

    @Test fun centerAndCornersMapToExpectedTargetBounds() {
        val visible = FocusBounds(0, 375, 4000, 2625)
        assertEquals(FocusBounds(1760, 1365, 2240, 1635), FocusMeteringPolicy.region(visible, .5f, .5f))
        assertEquals(FocusBounds(0, 375, 480, 645), FocusMeteringPolicy.region(visible, 0f, 0f))
        assertEquals(FocusBounds(3520, 2355, 4000, 2625), FocusMeteringPolicy.region(visible, 1f, 1f))
    }

    @Test fun invalidInputFallsBackToCenterAndFiniteInputClamps() {
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(.5f to .5f, FocusMeteringPolicy.normalizedPoint(value, value))
            assertEquals(FocusMeteringPolicy.region(sensor, .5f, .5f), FocusMeteringPolicy.region(sensor, value, value))
        }
        assertEquals(0f to 1f, FocusMeteringPolicy.normalizedPoint(-100f, 100f))
    }

    @Test fun tinySensorsAndInvalidFractionsStillHavePositiveRegion() {
        val tiny = FocusBounds(10, 20, 11, 21)
        for (fraction in listOf(0f, -1f, Float.NaN, 5f)) {
            assertEquals(tiny, FocusMeteringPolicy.region(tiny, 1f, 1f, fraction))
        }
        assertEquals(sensor, FocusMeteringPolicy.region(sensor, .5f, .5f, 2f))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidStreamSizeIsRejected() { FocusMeteringPolicy.visibleBounds(sensor, 0, 1080) }

    @Test(expected = IllegalArgumentException::class)
    fun emptySensorIsRejected() { FocusBounds(0, 0, 0, 3000) }
}
