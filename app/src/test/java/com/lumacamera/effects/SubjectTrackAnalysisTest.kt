package com.lumacamera.effects

import org.junit.Assert.*
import org.junit.Test

class SubjectTrackAnalysisTest {
    private fun mask(vararg regions: IntRange): PortraitMaskPolicy.Mask {
        val values = FloatArray(100 * 20)
        for (y in 3..16) for (region in regions) for (x in region) values[y * 100 + x] = .9f
        return PortraitMaskPolicy.Mask(100, 20, values, 0L)
    }
    @Test fun twoPeopleDoNotProduceFocusAtEmptyMidpoint() {
        val point = SubjectTrackAnalysis().validatedCentroid(mask(10..29, 70..89), true, 1f)!!
        assertEquals(.2f, point.first, .001f); assertEquals(.5f, point.second, .001f)
    }
    @Test fun coherentSubjectStaysSelectedEvenWhenOtherPersonGetsLarger() {
        val policy = SubjectTrackAnalysis()
        policy.validatedCentroid(mask(10..29, 70..79), true, 1f)
        val point = policy.validatedCentroid(mask(15..34, 60..94), true, 1f)!!
        assertEquals(.25f, point.first, .001f)
    }
    @Test fun lossDoesNotSilentlySelectNewDistantPersonAndTapCanReselect() {
        val policy = SubjectTrackAnalysis()
        policy.validatedCentroid(mask(10..29), true, 1f)
        assertNull(policy.validatedCentroid(mask(70..89), true, 1f))
        policy.reset(.8f to .5f)
        assertEquals(.8f, policy.validatedCentroid(mask(10..29, 70..89), true, 1f)!!.first, .001f)
    }
    @Test fun missingStaleNonFiniteAndUnboundedMasksCannotDriveAf() {
        val policy = SubjectTrackAnalysis()
        assertNull(policy.validatedCentroid(null, true, 1f))
        assertNull(policy.validatedCentroid(mask(10..29), false, 1f))
        assertNull(policy.validatedCentroid(mask(10..29), true, .79f))
        assertNull(policy.validatedCentroid(mask(10..29), true, Float.NaN))
        assertNull(policy.validatedCentroid(PortraitMaskPolicy.Mask(10, 10, FloatArray(100) { 1f }, 0), true, 1f))
        assertNull(policy.validatedCentroid(PortraitMaskPolicy.Mask(10, 10, FloatArray(100) { Float.NaN }, 0), true, 1f))
    }
    @Test fun hollowObjectTargetsAnActualForegroundPixelRatherThanEmptyCenter() {
        val confidence = FloatArray(100)
        for (y in 2..7) for (x in 2..7) if (x == 2 || x == 7 || y == 2 || y == 7) confidence[y * 10 + x] = .9f
        val ring = PortraitMaskPolicy.Mask(10, 10, confidence, 0L)
        val point = SubjectTrackAnalysis().validatedCentroid(ring, true, 1f)!!
        assertTrue(PortraitMaskPolicy.confidenceAt(ring, point.first, point.second) >= .6f)
    }
    @Test fun hollowSubjectCannotAimAtAnUnrelatedComponentInsideItsCenter() {
        val confidence = FloatArray(100 * 100)
        for (y in 20..79) for (x in 20..79)
            if (x < 25 || x > 74 || y < 25 || y > 74) confidence[y * 100 + x] = .9f
        // A smaller disconnected subject occupies the ring's mathematical centroid.
        for (y in 47..52) for (x in 47..52) confidence[y * 100 + x] = .9f
        val mask = PortraitMaskPolicy.Mask(100, 100, confidence, 0L)
        val point = SubjectTrackAnalysis().validatedCentroid(mask, true, 1f)!!
        assertTrue(PortraitMaskPolicy.confidenceAt(mask, point.first, point.second) >= .6f)
        val x = (point.first * 100).toInt(); val y = (point.second * 100).toInt()
        assertTrue("The larger connected ring remains the optical target", x < 25 || x > 74 || y < 25 || y > 74)
    }
}
