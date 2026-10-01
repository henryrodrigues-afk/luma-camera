package com.lumacamera.core

import org.junit.Assert.*
import org.junit.Test

class FrameGeometryTest {
    @Test fun sensorAndDisplayRotationsHaveOneClockwiseContractForBothLenses() {
        assertEquals(90, FrameGeometry.relativeRotation(90, 0, false))
        assertEquals(0, FrameGeometry.relativeRotation(90, 90, false))
        assertEquals(180, FrameGeometry.relativeRotation(90, 270, false))
        assertEquals(270, FrameGeometry.relativeRotation(270, 0, true))
        assertEquals(0, FrameGeometry.relativeRotation(270, 90, true))
        assertEquals(180, FrameGeometry.relativeRotation(270, 270, true))
    }

    @Test fun physicallyOrientedPortraitVideoSwapsTheEncoderDimensions() {
        assertEquals(FrameGeometry.Dimensions(720, 1280), FrameGeometry.orientedSize(1280, 720, 90))
        assertEquals(FrameGeometry.Dimensions(720, 1280), FrameGeometry.orientedSize(1280, 720, 270))
        assertEquals(FrameGeometry.Dimensions(1280, 720), FrameGeometry.orientedSize(1280, 720, 180))
    }

    @Test fun aPortraitImageFitsASquareWindowWithoutStretching() {
        val frame = FrameGeometry.fitViewport(1920, 1080, 90, 1000, 1000)
        assertEquals(FrameGeometry.Viewport(218, 0, 563, 1000), frame)
        assertEquals(1080f / 1920f, frame.width.toFloat() / frame.height, .001f)
    }

    @Test fun aLandscapeImageFitsAPortraitWindowWithBarsRatherThanHiddenCrop() {
        val frame = FrameGeometry.fitViewport(1920, 1080, 0, 720, 1280)
        assertEquals(FrameGeometry.Viewport(0, 437, 720, 405), frame)
        assertEquals(1920f / 1080f, frame.width.toFloat() / frame.height, .001f)
    }

    @Test fun aMatchingPortraitSurfaceUsesItsEntireFrame() {
        assertEquals(FrameGeometry.Viewport(0, 0, 720, 1280),
            FrameGeometry.fitViewport(1280, 720, 90, 720, 1280))
    }

    @Test fun everyAsymmetricCornerHasTheCorrectClockwiseMapping() {
        // Destination order is GL bottom-left, bottom-right, top-left, top-right.
        val expected = mapOf(
            0 to listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f),
            90 to listOf(1f to 0f, 1f to 1f, 0f to 0f, 0f to 1f),
            180 to listOf(1f to 1f, 0f to 1f, 1f to 0f, 0f to 0f),
            270 to listOf(0f to 1f, 0f to 0f, 1f to 1f, 1f to 0f)
        )
        val corners = listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f)
        expected.forEach { (rotation, sourceCorners) ->
            corners.forEachIndexed { index, corner ->
                assertEquals(sourceCorners[index], FrameGeometry.sourceGlAt(corner.first, corner.second, rotation, false))
            }
        }
    }

    @Test fun shaderMatricesMatchTouchCoordinatesForEveryRotationAndPreviewMirror() {
        for (rotation in listOf(0, 90, 180, 270)) for (mirror in listOf(false, true)) {
            val matrix = FrameGeometry.uvTransform(rotation, mirror)
            for ((u, v) in listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f, .2f to .7f)) {
                val expected = FrameGeometry.sourceGlAt(u, v, rotation, mirror)
                assertEquals(expected.first, matrix[0] * u + matrix[3] * v + matrix[6], .0001f)
                assertEquals(expected.second, matrix[1] * u + matrix[4] * v + matrix[7], .0001f)
            }
        }
    }

    @Test fun frontMirrorChangesOnlyThePreviewWhileRecordedCoordinatesStayUnmirrored() {
        val recorded = FrameGeometry.sourceGlAt(.2f, .3f, 270, false)
        val preview = FrameGeometry.sourceGlAt(.8f, .3f, 270, true)
        assertEquals(recorded, preview)
        assertNotEquals(recorded, FrameGeometry.sourceGlAt(.2f, .3f, 270, true))
    }

    @Test fun touchOnBlackBarsHasNoFocusTarget() {
        assertNull(FrameGeometry.sourcePoint(10f, 100f, 1920, 1080, 90, false, 1000, 1000))
        assertNull(FrameGeometry.sourcePoint(900f, 100f, 1920, 1080, 90, false, 1000, 1000))
    }

    @Test fun aTouchInsideFittedContentUsesItsOwnOriginAndAspect() {
        val result = FrameGeometry.sourcePoint(218f + 563f * .2f, 1000f * .3f,
            1920, 1080, 90, false, 1000, 1000)
        assertNotNull(result)
        assertEquals(.3f, result!!.first, .0001f)
        assertEquals(.2f, result.second, .0001f)
    }

    @Test fun invalidTouchAndUnmeasuredViewNeverGenerateAnInvalidSensorRegion() {
        assertNull(FrameGeometry.sourcePoint(Float.NaN, 0f, 1280, 720, 0, false, 1000, 1000))
        assertNull(FrameGeometry.sourcePoint(10f, 10f, 1280, 720, 0, false, 0, 0))
    }

    @Test fun resizingKeepsAspectAndRecentersTheImage() {
        val first = FrameGeometry.fitViewport(1280, 720, 0, 1280, 720)
        val second = FrameGeometry.fitViewport(1280, 720, 0, 2000, 720)
        assertEquals(first.width, second.width); assertEquals(first.height, second.height)
        assertEquals(360, second.x)
    }

    @Test fun allObservedHalRotationsAndFrontMirrorsNormalizeToUnrotatedUnmirroredSourcePixels() {
        for (rotation in listOf(0, 90, 180, 270)) for (mirror in listOf(false, true)) {
            val native = nativeCameraMatrix(rotation, mirror, 0f, 0f, 1f, 1f)
            val canonical = FloatArray(16)
            assertTrue(FrameGeometry.canonicalCameraTransform(native, canonical))
            for ((u, v) in listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f, .2f to .7f)) {
                assertEquals(u, canonical[0] * u + canonical[4] * v + canonical[12], .0001f)
                assertEquals(1f - v, canonical[1] * u + canonical[5] * v + canonical[13], .0001f)
            }
        }
    }

    @Test fun aProducerWithoutAutomaticSensorRotationRetainsItsYFlipExactly() {
        val native = nativeCameraMatrix(0, false, 0f, 0f, 1f, 1f)
        val canonical = FloatArray(16)
        assertTrue(FrameGeometry.canonicalCameraTransform(native, canonical))
        assertArrayEquals(native, canonical, 0f)
    }

    @Test fun cropBoundsAndYFlipSurviveNativeOrientationRemovalForAllLensOrientations() {
        for (rotation in listOf(0, 90, 180, 270)) for (mirror in listOf(false, true)) {
            val native = nativeCameraMatrix(rotation, mirror, .08f, .1f, .84f, .78f)
            val canonical = FloatArray(16)
            assertTrue(FrameGeometry.canonicalCameraTransform(native, canonical))
            assertEquals(.08f, canonical[12], .0001f)
            assertEquals(.88f, canonical[13], .0001f)
            assertEquals(.84f, canonical[0], .0001f)
            assertEquals(-.78f, canonical[5], .0001f)
            assertEquals(.08f + .84f * .2f, canonical[0] * .2f + canonical[12], .0001f)
            assertEquals(.1f + .78f * (1f - .7f), canonical[5] * .7f + canonical[13], .0001f)
        }
    }

    @Test fun aNonOrthogonalProducerTransformIsPreservedRatherThanGuessedOrRejected() {
        val native = nativeCameraMatrix(0, false, 0f, 0f, 1f, 1f).also { it[4] = .2f }
        val canonical = FloatArray(16)
        assertFalse(FrameGeometry.canonicalCameraTransform(native, canonical))
        assertArrayEquals(native, canonical, 0f)
    }

    /** Independent camera/GL prediction: crop * Y-flip * quarter-turn * native front mirror. */
    private fun nativeCameraMatrix(rotation: Int, mirror: Boolean,
        left: Float, top: Float, scaleX: Float, scaleY: Float): FloatArray {
        fun sample(u: Float, v: Float): Pair<Float, Float> {
            val x = if (mirror) 1f - u else u
            val (rx, ry) = when (rotation) {
                90 -> (1f - v) to x
                180 -> (1f - x) to (1f - v)
                270 -> v to (1f - x)
                else -> x to v
            }
            return (left + scaleX * rx) to (top + scaleY * (1f - ry))
        }
        val origin = sample(0f, 0f)
        val horizontal = sample(1f, 0f)
        val vertical = sample(0f, 1f)
        return floatArrayOf(horizontal.first - origin.first, horizontal.second - origin.second, 0f, 0f,
            vertical.first - origin.first, vertical.second - origin.second, 0f, 0f,
            0f, 0f, 1f, 0f, origin.first, origin.second, 0f, 1f)
    }
}
