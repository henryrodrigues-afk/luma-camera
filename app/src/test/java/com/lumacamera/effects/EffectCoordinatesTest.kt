package com.lumacamera.effects

import org.junit.Assert.assertEquals
import org.junit.Test

class EffectCoordinatesTest {
    @Test fun topLeftScreenUsesBottomLeftGlOriginWithoutRotation() {
        assertEquals(0f to 1f, EffectCoordinates.sourceCenter(0f, 0f, 0, false))
    }
    @Test fun portraitClockwiseTopRightPointsAtOriginalTopLeft() {
        assertEquals(0f to 1f, EffectCoordinates.sourceCenter(1f, 0f, 90, false))
    }
    @Test fun oppositePortraitTopLeftPointsAtOriginalTopRight() {
        assertEquals(1f to 1f, EffectCoordinates.sourceCenter(0f, 0f, 270, false))
    }
    @Test fun mirroredPreviewSelectsTheSamePhysicalAreaInUnmirroredVideo() {
        assertEquals(EffectCoordinates.sourceCenter(.8f, .3f, 90, false),
            EffectCoordinates.sourceCenter(.2f, .3f, 90, true))
    }
}
