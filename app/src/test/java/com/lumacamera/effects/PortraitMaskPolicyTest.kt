package com.lumacamera.effects

import org.junit.Assert.*
import org.junit.Test

class PortraitMaskPolicyTest {
    @Test fun masksStayAlignedWithTheOriginalSourceAtEverySensorRotation() {
        val source = intArrayOf(0, 1, 2, 3, 4, 5)
        val values = floatArrayOf(.1f, .2f, .3f, .4f, .5f, .6f)
        for (rotation in listOf(0, 90, 180, 270)) {
            val upright = PortraitMaskPolicy.rotateTopLeftArgb(source, 3, 2, rotation)
            val mask = PortraitMaskPolicy.alignToSource(FloatArray(6) { values[upright.argb[it]] },
                upright.width, upright.height, 3, 2, rotation, 123L)
            assertArrayEquals(floatArrayOf(.4f, .5f, .6f, .1f, .2f, .3f), mask.confidence, .0001f)
        }
    }

    @Test fun clockwiseRotationPreservesTheImageAspectAndPixelOrder() {
        val result = PortraitMaskPolicy.rotateTopLeftArgb(intArrayOf(1, 2, 3, 4, 5, 6), 3, 2, 90)
        assertEquals(2, result.width); assertEquals(3, result.height)
        assertArrayEquals(intArrayOf(4, 1, 5, 2, 6, 3), result.argb)
    }

    @Test fun oldMasksFadeOutInsteadOfBlurringAStaleSilhouette() {
        assertEquals(1f, PortraitMaskPolicy.freshness(250L, 0L), 0f)
        assertEquals(.5f, PortraitMaskPolicy.freshness(425L, 0L), 0f)
        assertEquals(0f, PortraitMaskPolicy.freshness(600L, 0L), 0f)
        assertEquals(0f, PortraitMaskPolicy.freshness(0L, -1L), 0f)
        assertEquals(0f, PortraitMaskPolicy.freshness(0L, 1L), 0f)
    }

    @Test fun nativeMaskAlignmentDoesNotExpandTheGridAndMatchesEveryRotation() {
        val source = intArrayOf(0, 1, 2, 3, 4, 5)
        val values = floatArrayOf(.1f, .2f, .3f, .4f, .5f, .6f)
        for (rotation in listOf(0, 90, 180, 270)) {
            val upright = PortraitMaskPolicy.rotateTopLeftArgb(source, 3, 2, rotation)
            val result = PortraitMaskPolicy.alignRawToSource(FloatArray(6) { values[upright.argb[it]] },
                upright.width, upright.height, rotation, 123L)
            assertEquals(3, result.width); assertEquals(2, result.height)
            assertEquals(123L, result.capturedAtMs)
            assertArrayEquals(floatArrayOf(.4f, .5f, .6f, .1f, .2f, .3f), result.confidence, .0001f)
        }
    }

    @Test fun aLatePersonMaskCannotDriveTrackingAfterItsBlurDeadline() {
        val mask = PortraitMaskPolicy.Mask(20, 20, FloatArray(400) {
            if (it % 20 in 5..14 && it / 20 in 3..16) .9f else 0f
        }, 0L)
        val tracker = SubjectTrackAnalysis()
        assertNotNull(tracker.validatedCentroid(mask, true, PortraitMaskPolicy.freshness(200L, 0L)))
        assertNull(tracker.validatedCentroid(mask, true, PortraitMaskPolicy.freshness(425L, 0L)))
        assertNull(tracker.validatedCentroid(mask, true, PortraitMaskPolicy.freshness(601L, 0L)))
    }

    @Test fun uncertainEdgesDoNotOscillateTheFocusTarget() {
        assertFalse(PortraitMaskPolicy.targetBackground(.8f, true))
        assertTrue(PortraitMaskPolicy.targetBackground(.2f, false))
        assertFalse(PortraitMaskPolicy.targetBackground(.5f, false))
        assertTrue(PortraitMaskPolicy.targetBackground(.5f, true))
    }

    @Test fun rackFocusRespectsTheDurationAndClampsAtTheDestination() {
        assertEquals(.25f, PortraitMaskPolicy.advanceFocus(0f, true, .5f, 2f), 0f)
        assertEquals(.75f, PortraitMaskPolicy.advanceFocus(1f, false, .5f, 2f), 0f)
        assertEquals(1f, PortraitMaskPolicy.advanceFocus(.9f, true, 2f, 1f), 0f)
        assertEquals(0f, PortraitMaskPolicy.advanceFocus(.1f, false, 2f, 1f), 0f)
    }

    @Test fun emptySegmentationCannotEnableFullFramePortraitBlur() {
        assertFalse(PortraitMaskPolicy.hasPerson(PortraitMaskPolicy.Mask(4, 4, FloatArray(16), 0L)))
        assertTrue(PortraitMaskPolicy.hasPerson(PortraitMaskPolicy.Mask(4, 4, FloatArray(16) { .8f }, 0L)))
    }

    @Test fun touchSamplingUsesTheSameGlCoordinatesAsTheTexture() {
        val mask = PortraitMaskPolicy.Mask(2, 2, floatArrayOf(0f, 1f, .2f, .8f), 0L)
        assertEquals(0f, PortraitMaskPolicy.confidenceAt(mask, .25f, .25f), .0001f)
        assertEquals(.8f, PortraitMaskPolicy.confidenceAt(mask, .75f, .75f), .0001f)
        assertEquals(.5f, PortraitMaskPolicy.confidenceAt(mask, .5f, .5f), .0001f)
    }

    @Test fun masksAreSmoothedOnlyAcrossNearbyFramesWithTheSameSize() {
        val previous = PortraitMaskPolicy.Mask(1, 1, floatArrayOf(0f), 0L)
        val next = PortraitMaskPolicy.Mask(1, 1, floatArrayOf(1f), 200L)
        assertEquals(.85f, PortraitMaskPolicy.smooth(previous, next).confidence[0], 0f)
        assertEquals(1f, PortraitMaskPolicy.smooth(previous, next.copy(capturedAtMs = 1_000L)).confidence[0], 0f)
    }
}
