package com.lumacamera.core

import org.junit.Assert.*
import org.junit.Test

class TrackedPreviewGeometryTest {
    @Test fun subjectReticleFollowsSameRotationAndMirrorAsCameraTouch() {
        for (rotation in listOf(0, 90, 180, 270)) for (mirrored in listOf(false, true)) {
            for (source in listOf(.1f to .2f, .3f to .9f, 0f to 1f, .5f to .5f)) {
                val display = FrameGeometry.outputGlAt(source.first, source.second, rotation, mirrored)!!
                val inverse = FrameGeometry.sourceGlAt(display.first, display.second, rotation, mirrored)
                assertEquals(source.first, inverse.first, .00001f)
                assertEquals(source.second, inverse.second, .00001f)
            }
        }
    }
    @Test fun invalidOrOffscreenSubjectHasNoReticle() {
        assertNull(FrameGeometry.outputGlAt(Float.NaN, .5f, 0, false))
        assertNull(FrameGeometry.outputGlAt(.5f, Float.POSITIVE_INFINITY, 90, true))
        assertNull(FrameGeometry.outputGlAt(-.01f, .5f, 270, true))
        assertNull(FrameGeometry.outputGlAt(.5f, 1.01f, 180, false))
    }
}
