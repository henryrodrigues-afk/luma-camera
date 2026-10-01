package com.lumacamera.effects

import com.lumacamera.core.StabilizationTransform
import org.junit.Assert.*
import org.junit.Test

class SubjectTrackDisplayPolicyTest {
    @Test fun croppedOffsetSourceTargetReturnsMatchingPreviewLocation() {
        val correction = StabilizationTransform(zoom = 1.25f, centerOffsetX = .05f, centerOffsetY = -.04f)
        val point = SubjectTrackDisplayPolicy.point(.55f, .46f, correction)!!
        assertEquals(.5f, point.first, .00001f); assertEquals(.5f, point.second, .00001f)
        val left = SubjectTrackDisplayPolicy.point(.15f, .46f, correction)!!
        assertEquals(0f, left.first, .00001f)
    }
    @Test fun croppedOutAndInvalidTargetsAreHiddenRatherThanClampedToBorder() {
        assertNull(SubjectTrackDisplayPolicy.point(0f, .5f, StabilizationTransform(zoom = 1.25f)))
        assertNull(SubjectTrackDisplayPolicy.point(Float.NaN, .5f, StabilizationTransform()))
        assertNull(SubjectTrackDisplayPolicy.point(1.1f, .5f, StabilizationTransform()))
    }
    @Test fun neutralAndInvalidStabilizationRemainSafe() {
        val neutral = SubjectTrackDisplayPolicy.point(.2f, .7f, StabilizationTransform())!!
        assertEquals(.2f, neutral.first, .00001f); assertEquals(.7f, neutral.second, .00001f)
        val invalid = SubjectTrackDisplayPolicy.point(.2f, .7f,
            StabilizationTransform(zoom = Float.NaN, centerOffsetX = Float.NaN, centerOffsetY = Float.NaN))!!
        assertEquals(neutral.first, invalid.first, .00001f); assertEquals(neutral.second, invalid.second, .00001f)
    }
    @Test fun reticleFollowsExactRollCropAndPhysicalAspectSampler() {
        val correction = StabilizationTransform(zoom = 1.25f, centerOffsetX = .02f, centerOffsetY = -.01f,
            rotationRadians = .025f, aspectRatio = 16f / 9f)
        for (point in listOf(.15f to .25f, .5f to .5f, .85f to .75f)) {
            val source = correction.sourcePoint(point.first, point.second)
            val shown = SubjectTrackDisplayPolicy.point(source.first, source.second, correction)!!
            assertEquals(point.first, shown.first, .00001f); assertEquals(point.second, shown.second, .00001f)
        }
    }
}
