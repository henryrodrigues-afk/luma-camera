package com.lumacamera.effects

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.floor

class FrameMotionEstimatorTest {
    private fun texture(width: Int, height: Int, seed: Int = 1): FloatArray {
        var state = seed
        return FloatArray(width * height) {
            state = state * 1_664_525 + 1_013_904_223
            .2f + ((state ushr 8) and 0xffff) / 65535f * .6f
        }
    }

    private fun translated(source: FloatArray, width: Int, height: Int, dx: Int, dy: Int) =
        FloatArray(source.size) { index ->
            val x = index % width - dx; val y = index / width - dy
            if (x in 0 until width && y in 0 until height) source[y * width + x] else .5f
        }

    /** Smooth deterministic texture avoids testing rotation against aliasing of single-pixel white noise. */
    private fun smoothTexture(width: Int, height: Int): FloatArray {
        val noise = texture(width, height, 73)
        return FloatArray(noise.size) { index ->
            val x = index % width; val y = index / width
            var sum = 0f
            for (dy in -1..1) for (dx in -1..1)
                sum += noise[(y + dy).coerceIn(0, height - 1) * width + (x + dx).coerceIn(0, width - 1)]
            // A wider contrast range, below clipping for the exposure test.
            ((sum / 9f - .5f) * 2.8f + .5f).coerceIn(.08f, .82f)
        }
    }

    private fun transformed(source: FloatArray, width: Int, height: Int,
        angle: Float, dx: Float = 0f, dy: Float = 0f, scale: Float = 1f): FloatArray {
        val c = cos(angle); val s = sin(angle)
        return FloatArray(source.size) { index ->
            val x = index % width + .5f - width * .5f - dx
            val y = index / width + .5f - height * .5f - dy
            val sx = (c * x + s * y) / scale + width * .5f - .5f
            val sy = (-s * x + c * y) / scale + height * .5f - .5f
            val ix = floor(sx).toInt(); val iy = floor(sy).toInt()
            if (ix < 0 || iy < 0 || ix + 1 >= width || iy + 1 >= height) .5f else {
                val fx = sx - ix; val fy = sy - iy
                val a = source[iy * width + ix] * (1f - fx) + source[iy * width + ix + 1] * fx
                val b = source[(iy + 1) * width + ix] * (1f - fx) + source[(iy + 1) * width + ix + 1] * fx
                a * (1f - fy) + b * fy
            }
        }
    }

    @Test fun firstFrameStartsANewReferenceWithoutInventingMotion() {
        val result = FrameMotionEstimator().consume(texture(64, 36), 64, 36, 0)
        assertFalse(result.valid); assertTrue(result.reset); assertEquals("first_frame", result.reason)
    }

    @Test fun aStaticTexturedFrameHasZeroDisplacementAndStrongConsensus() {
        val estimator = FrameMotionEstimator(); val source = texture(96, 54)
        estimator.consume(source, 96, 54, 0)
        val result = estimator.consume(source, 96, 54, 100)
        assertTrue(result.valid); assertTrue(result.confidence > .9f)
        assertEquals(0f, result.deltaX, .00001f); assertEquals(0f, result.deltaY, .00001f)
    }

    @Test fun translationsHaveTheSamplerCompensationSignAndNormalizeEachAxis() {
        for ((width, height) in listOf(64 to 36, 96 to 54, 128 to 72)) {
            for ((dx, dy) in listOf(2 to -2, -3 to 1, 0 to 3)) {
                val estimator = FrameMotionEstimator(); val source = texture(width, height)
                estimator.consume(source, width, height, 0)
                val result = estimator.consume(translated(source, width, height, dx, dy), width, height, 100)
                assertTrue("${width}x$height ($dx,$dy): $result", result.valid)
                assertEquals(dx / width.toFloat(), result.deltaX, .00001f)
                assertEquals(dy / height.toFloat(), result.deltaY, .00001f)
            }
        }
    }

    @Test fun blockMeanNormalizationToleratesGlobalExposureChanges() {
        val estimator = FrameMotionEstimator(); val source = texture(96, 54)
        estimator.consume(source, 96, 54, 0)
        val changed = translated(source, 96, 54, 2, 1).map { it + .08f }.toFloatArray()
        val result = estimator.consume(changed, 96, 54, 100)
        assertTrue(result.valid)
        assertEquals(2f / 96, result.deltaX, .0001f); assertEquals(1f / 54, result.deltaY, .0001f)
    }

    @Test fun aSmallMovingForegroundDoesNotMoveTheStaticBackgroundEstimate() {
        val estimator = FrameMotionEstimator(); val source = texture(96, 54)
        val changed = source.copyOf()
        for (y in 21..33) for (x in 38..57) changed[y * 96 + x] = source[y * 96 + x - 3]
        estimator.consume(source, 96, 54, 0)
        val result = estimator.consume(changed, 96, 54, 100)
        assertTrue(result.valid)
        assertEquals(0f, result.deltaX, .00001f); assertEquals(0f, result.deltaY, .00001f)
    }

    @Test fun anActiveAiMaskExcludesTheMovingSubjectAndKeepsBackgroundMotion() {
        val estimator = FrameMotionEstimator(); val source = texture(128, 72)
        val changed = translated(source, 128, 72, 2, 1)
        val confidence = FloatArray(source.size)
        for (y in 23..48) for (x in 49..77) {
            changed[y * 128 + x] = source[y * 128 + x + 3]
            confidence[y * 128 + x] = 1f
        }
        estimator.consume(source, 128, 72, 0)
        val result = estimator.consume(changed, 128, 72, 100, PortraitMaskPolicy.Mask(128, 72, confidence, 100))
        assertTrue(result.toString(), result.valid)
        assertEquals(2f / 128, result.deltaX, .00001f); assertEquals(1f / 72, result.deltaY, .00001f)
    }

    @Test fun aMaskCoveringTheWholeSceneCannotInventBackgroundMotion() {
        val estimator = FrameMotionEstimator(); val source = texture(96, 54)
        estimator.consume(source, 96, 54, 0)
        val result = estimator.consume(source, 96, 54, 100,
            PortraitMaskPolicy.Mask(96, 54, FloatArray(source.size) { 1f }, 100))
        assertFalse(result.valid); assertEquals("low_texture", result.reason)
    }

    @Test fun anUnrelatedSceneResetsInsteadOfChoosingTheLeastBadTranslation() {
        for (seed in listOf(998, 123, 17)) {
            val estimator = FrameMotionEstimator()
            estimator.consume(texture(96, 54, 1), 96, 54, 0)
            val result = estimator.consume(texture(96, 54, seed), 96, 54, 100)
            assertFalse(result.valid); assertTrue(result.toString(), result.reset); assertEquals("scene_cut", result.reason)
        }
    }

    @Test fun darkAndFlatFramesDoNotProduceAMotionEstimate() {
        val estimator = FrameMotionEstimator()
        assertEquals("dark", estimator.consume(FloatArray(64 * 36) { .02f }, 64, 36, 0).reason)
        estimator.consume(FloatArray(64 * 36) { .5f }, 64, 36, 100)
        val result = estimator.consume(FloatArray(64 * 36) { .5f }, 64, 36, 200)
        assertFalse(result.valid); assertEquals("low_texture", result.reason)
    }

    @Test fun gapsResizeAndNonFiniteInputDiscardThePreviousScene() {
        val estimator = FrameMotionEstimator(); val source = texture(64, 36)
        estimator.consume(source, 64, 36, 0)
        assertEquals("frame_gap", estimator.consume(source, 64, 36, 900).reason)
        assertEquals("first_frame", estimator.consume(texture(96, 54), 96, 54, 1000).reason)
        val invalid = source.copyOf().apply { this[0] = Float.NaN }
        assertEquals("invalid_frame", estimator.consume(invalid, 64, 36, 1100).reason)
        assertEquals("first_frame", estimator.consume(source, 64, 36, 1200).reason)
    }

    @Test fun reusingTheCallersBufferDoesNotOverwriteTheOwnedReference() {
        val source = texture(96, 54); val original = source.copyOf()
        val estimator = FrameMotionEstimator(); estimator.consume(source, 96, 54, 0)
        source.fill(.5f)
        val result = estimator.consume(translated(original, 96, 54, 2, 0), 96, 54, 100)
        assertTrue(result.valid); assertEquals(2f / 96, result.deltaX, .00001f)
    }

    @Test fun aTranslationBeyondTheOldSixPixelWindowIsRecoveredAtMultipleScales() {
        val source = texture(128, 72)
        for ((dx, dy) in listOf(11 to -7, -12 to 6, 14 to 0)) {
            val estimator = FrameMotionEstimator(); estimator.consume(source, 128, 72, 0)
            val result = estimator.consume(translated(source, 128, 72, dx, dy), 128, 72, 100)
            assertTrue("($dx,$dy): $result", result.valid)
            assertEquals(dx / 128f, result.deltaX, .0001f)
            assertEquals(dy / 72f, result.deltaY, .0001f)
            assertEquals(0f, result.rotationRadians, .002f)
        }
    }

    @Test fun rotationHasPhysicalAspectAndTheSceneCounterclockwiseSign() {
        for ((width, height) in listOf(128 to 72, 72 to 128)) for (angle in listOf(.04f, -.04f)) {
            val source = smoothTexture(width, height)
            val estimator = FrameMotionEstimator(); estimator.consume(source, width, height, 0)
            val result = estimator.consume(transformed(source, width, height, angle), width, height, 100)
            assertTrue("${width}x$height/$angle: $result", result.valid)
            assertEquals(angle, result.rotationRadians, .007f)
            assertEquals(0f, result.deltaX, .004f); assertEquals(0f, result.deltaY, .004f)
        }
    }

    @Test fun rotationAndTranslationAreSeparatedDespiteAnExposureChange() {
        val source = smoothTexture(128, 72)
        val changed = transformed(source, 128, 72, .035f, 4f, -2f).map { it * 1.12f + .04f }.toFloatArray()
        val estimator = FrameMotionEstimator(); estimator.consume(source, 128, 72, 0)
        val result = estimator.consume(changed, 128, 72, 100)
        assertTrue(result.toString(), result.valid)
        assertEquals(.035f, result.rotationRadians, .007f)
        assertEquals(4f / 128, result.deltaX, .004f); assertEquals(-2f / 72, result.deltaY, .004f)
    }

    @Test fun aMaskedIndependentSubjectCannotSupplyTheCameraRotation() {
        val width = 128; val height = 72; val source = smoothTexture(width, height)
        val changed = transformed(source, width, height, -.035f, 3f, 1f)
        val independentlyMoved = transformed(source, width, height, .07f, -8f, -2f)
        val confidence = FloatArray(source.size)
        for (y in 20..51) for (x in 46..82) {
            changed[y * width + x] = independentlyMoved[y * width + x]
            confidence[y * width + x] = 1f
        }
        val estimator = FrameMotionEstimator(); estimator.consume(source, width, height, 0)
        val result = estimator.consume(changed, width, height, 100,
            PortraitMaskPolicy.Mask(width, height, confidence, 100))
        assertTrue(result.toString(), result.valid)
        assertEquals(-.035f, result.rotationRadians, .008f)
        assertEquals(3f / width, result.deltaX, .004f); assertEquals(1f / height, result.deltaY, .006f)
    }

    @Test fun twoParallaxPlanesWithoutARigidMajorityAreRejected() {
        val width = 128; val height = 72; val source = texture(width, height)
        val left = translated(source, width, height, 4, 0)
        val right = translated(source, width, height, -4, 0)
        val next = FloatArray(source.size) { if (it % width < width / 2) left[it] else right[it] }
        val estimator = FrameMotionEstimator(); estimator.consume(source, width, height, 0)
        val result = estimator.consume(next, width, height, 100)
        assertFalse(result.toString(), result.valid)
    }

    @Test fun periodicTextureDoesNotCreateAnAccidentalLargeDisplacement() {
        val width = 128; val height = 72
        val source = FloatArray(width * height) { if ((it % width / 4 + it / width / 4) % 2 == 0) .2f else .8f }
        val estimator = FrameMotionEstimator(); estimator.consume(source, width, height, 0)
        val result = estimator.consume(translated(source, width, height, 8, 0), width, height, 100)
        assertFalse(result.toString(), result.valid)
    }

    @Test fun aStaleMaskCannotHideTheEntireCurrentBackground() {
        val source = texture(96, 54)
        val estimator = FrameMotionEstimator(); estimator.consume(source, 96, 54, 600)
        val result = estimator.consume(source, 96, 54, 1000,
            PortraitMaskPolicy.Mask(96, 54, FloatArray(source.size) { 1f }, 0))
        assertTrue(result.toString(), result.valid)
        assertEquals(0f, result.deltaX, .00001f)
    }

    @Test fun motionOutsideTheSearchBudgetIsRejectedRatherThanInvented() {
        val source = texture(128, 72)
        val estimator = FrameMotionEstimator(); estimator.consume(source, 128, 72, 0)
        val result = estimator.consume(translated(source, 128, 72, 31, 0), 128, 72, 100)
        assertFalse(result.toString(), result.valid)
    }

    @Test fun zoomIsNotMisidentifiedAsCameraRotation() {
        for ((width, height, scale) in listOf(Triple(128, 72, 1.10f), Triple(96, 54, 1.04f))) {
            val source = smoothTexture(width, height)
            val estimator = FrameMotionEstimator(); estimator.consume(source, width, height, 0)
            val result = estimator.consume(transformed(source, width, height, 0f, scale = scale), width, height, 100)
            assertFalse("scale=$scale: $result", result.valid)
        }
    }
}
