package com.lumacamera.core

import com.lumacamera.effects.PortraitMaskPolicy
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Test

class ObjectTrackingPolicyTest {
    private val width = 64
    private val height = 48
    private val seed = ObjectMaskPolicy.Point(25.5f / width, 22.5f / height)
    private fun frame(dx: Int = 0, dy: Int = 0, time: Long = 100L, gain: Float = 0f) =
        ObjectTrackingPolicy.Frame(FloatArray(width * height) { index ->
            val x = index % width - dx; val y = index / width - dy
            val value = if (x in 17..33 && y in 14..31)
                .2f + ((x * 19 + y * 37 + x * y * 13) % 101) / 100f * .7f else .1f
            (value + gain).coerceIn(0f, 1f)
        }, width, height, time)
    private fun mask(dx: Int = 0, dy: Int = 0) = PortraitMaskPolicy.Mask(width, height,
        FloatArray(width * height) { index ->
            if (index % width - dx in 17..33 && index / width - dy in 14..31) .95f else 0f
        }, 100L)

    @Test fun followsTranslatedObjectBeforeMaskInferenceAndPreservesGlDirection() {
        val policy = ObjectTrackingPolicy()
        val anchor = policy.confirm(frame(), seed, mask())!!
        val prediction = policy.predict(frame(4, -3, 500L))
        assertEquals("tracked", prediction.reason)
        assertTrue(prediction.confidence > .5f)
        val point = requireNotNull(prediction.point)
        assertEquals(anchor.x + 4f / width, point.x, .00001f)
        assertEquals(anchor.y - 3f / height, point.y, .00001f)
    }

    @Test fun maskConfirmationCommitsReferenceForContinuedMotion() {
        val policy = ObjectTrackingPolicy()
        policy.confirm(frame(), seed, mask())
        val moved = frame(4, 0, 500L)
        val predicted = policy.predict(moved).point!!
        assertNotNull(policy.confirm(moved, predicted, mask(4)))
        assertNotNull(policy.predict(frame(8, 0, 900L)).point)
        assertNull(policy.confirm(frame(8, 0, 900L), predicted,
            PortraitMaskPolicy.Mask(width, height, FloatArray(width * height), 900L)))
    }

    @Test fun exposureChangeDoesNotReverseObjectMovement() {
        val policy = ObjectTrackingPolicy()
        val anchor = policy.confirm(frame(), seed, mask())!!
        val predicted = policy.predict(frame(-3, 2, 500L, .08f)).point!!
        assertEquals(anchor.x - 3f / width, predicted.x, .00001f)
        assertEquals(anchor.y + 2f / height, predicted.y, .00001f)
    }

    @Test fun occlusionAndLargeOrUnboundedMovementRejectObsoleteSeed() {
        val policy = ObjectTrackingPolicy()
        policy.confirm(frame(), seed, mask())
        val occluded = ObjectTrackingPolicy.Frame(FloatArray(width * height) { .1f }, width, height, 500L)
        assertEquals("appearance_changed", policy.predict(occluded).reason)
        assertNull(policy.predict(frame(7, 0, 500L)).point)
        assertNull(policy.predict(frame(16, 0, 500L)).point)
    }

    @Test fun twoIdenticalNearbyCandidatesAreAmbiguousRatherThanAReplacementObject() {
        val policy = ObjectTrackingPolicy()
        val old = frame()
        val anchor = policy.confirm(old, seed, mask())!!
        val cx = (anchor.x * width - .5f).roundToInt()
        val cy = (anchor.y * height - .5f).roundToInt()
        val next = FloatArray(width * height) { .1f }
        for (offset in listOf(-5, 5)) for (y in -3..3) for (x in -3..3)
            next[(cy + y) * width + cx + offset + x] = old.luma[(cy + y) * width + cx + x]
        val rejected = policy.predict(ObjectTrackingPolicy.Frame(next, width, height, 500L))
        assertNull(rejected.point)
        assertEquals("ambiguous", rejected.reason)
    }

    @Test fun gapsRegressingTimestampsAndInvalidPixelsCannotRestartTracking() {
        val policy = ObjectTrackingPolicy()
        policy.confirm(frame(), seed, mask())
        assertEquals("frame_gap", policy.predict(frame(time = 2_601L)).reason)
        assertEquals("frame_gap", policy.predict(frame(time = 99L)).reason)
        val invalid = frame(time = 500L).also { it.luma[3] = Float.NaN }
        assertEquals("invalid_frame", policy.predict(invalid).reason)
        policy.reset()
        assertFalse(policy.initialized)
        assertEquals("uninitialized", policy.predict(frame(time = 500L)).reason)
    }

    @Test fun referenceOwnsPixelsAndAnchorStaysInSelectedForeground() {
        val policy = ObjectTrackingPolicy()
        val old = frame()
        val anchor = policy.confirm(old, seed, mask())!!
        assertTrue(PortraitMaskPolicy.confidenceAt(mask(), anchor.x, anchor.y) >= .7f)
        old.luma.fill(0f)
        assertNotNull(policy.predict(frame(2, 0, 500L)).point)
        assertNull(policy.confirm(frame(), seed,
            PortraitMaskPolicy.Mask(width, height, FloatArray(width * height) { 1f }, 100L)))
    }

    @Test fun aSmoothObjectCannotBorrowItsTrackingTextureFromTheBackground() {
        val foreground = mask()
        val pixels = FloatArray(width * height) { index ->
            val x = index % width; val y = index / width
            if (x in 17..33 && y in 14..31) .5f
            else if ((x + y) % 2 == 0) .1f else .9f
        }
        val policy = ObjectTrackingPolicy()
        assertNull(policy.confirm(ObjectTrackingPolicy.Frame(pixels, width, height, 100L), seed, foreground))
        assertFalse(policy.initialized)
    }

    @Test fun aBoundaryTapUsesAnInteriorPatchOfTheSameTexturedObject() {
        val policy = ObjectTrackingPolicy()
        val foreground = mask()
        val boundary = ObjectMaskPolicy.Point(17.5f / width, 22.5f / height)
        val anchor = policy.confirm(frame(), boundary, foreground)!!
        val cx = (anchor.x * width - .5f).roundToInt()
        val cy = (anchor.y * height - .5f).roundToInt()
        for (dy in -3..3) for (dx in -3..3)
            assertTrue(PortraitMaskPolicy.confidenceAt(foreground,
                (cx + dx + .5f) / width, (cy + dy + .5f) / height) >= .6f)
        val prediction = policy.predict(frame(3, 0, 500L)).point!!
        assertEquals(anchor.x + 3f / width, prediction.x, .00001f)
    }

    @Test fun downsampleKeepsSourceBottomLeftAndLuminanceChannels() {
        val rgba = byteArrayOf(-1, 0, 0, -1, 0, -1, 0, -1, 0, 0, -1, -1, -1, -1, -1, -1)
        val frame = ObjectTrackingPolicy.frame(rgba, 2, 2, 123L)
        assertEquals(.2126f, frame.luma[0], .00001f)
        assertEquals(.7152f, frame.luma[1], .00001f)
        assertEquals(.0722f, frame.luma[2], .00001f)
        assertEquals(1f, frame.luma[3], .00001f)
        val large = ObjectTrackingPolicy.frame(ByteArray(256 * 144 * 4), 256, 144, 100L)
        assertEquals(96, large.width); assertEquals(54, large.height)
    }
}
