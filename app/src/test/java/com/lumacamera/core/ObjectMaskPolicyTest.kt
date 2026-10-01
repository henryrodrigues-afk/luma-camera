package com.lumacamera.core

import com.lumacamera.effects.PortraitMaskPolicy
import org.junit.Assert.*
import org.junit.Test

class ObjectMaskPolicyTest {
    private fun mask(width: Int = 20, height: Int = 20, fill: (Int, Int) -> Float) =
        PortraitMaskPolicy.Mask(width, height, FloatArray(width * height) { fill(it % width, it / width) }, 100L)

    @Test fun promptKeepsSourcePointAlignedForAllQuarterTurns() {
        val expected = listOf(.2f to .7f, .3f to .2f, .8f to .3f, .7f to .8f)
        listOf(0, 90, 180, 270).forEachIndexed { index, rotation ->
            val point = requireNotNull(ObjectMaskPolicy.uprightPoint(.2f, .3f, rotation))
            assertEquals(expected[index].first, point.x, .00001f)
            assertEquals(expected[index].second, point.y, .00001f)
        }
        assertEquals(ObjectMaskPolicy.uprightPoint(.2f, .3f, 270),
            ObjectMaskPolicy.uprightPoint(.2f, .3f, -90))
    }

    @Test fun promptRejectsNonFiniteAndOutsidePoints() {
        assertNull(ObjectMaskPolicy.uprightPoint(Float.NaN, .5f, 0))
        assertNull(ObjectMaskPolicy.uprightPoint(.5f, Float.POSITIVE_INFINITY, 90))
        assertNull(ObjectMaskPolicy.uprightPoint(-.01f, .5f, 0))
        assertNull(ObjectMaskPolicy.uprightPoint(.5f, 1.01f, 0))
    }

    @Test fun promptAndMaskUndoRotationAgreeAtPixelCenters() {
        val width = 7
        val height = 5
        val sourceX = 2
        val sourceY = 1 // Top-left bitmap row.
        val pixels = IntArray(width * height) { if (it == sourceY * width + sourceX) 1 else 0 }
        listOf(0, 90, 180, 270).forEach { rotation ->
            val upright = PortraitMaskPolicy.rotateTopLeftArgb(pixels, width, height, rotation)
            val point = requireNotNull(ObjectMaskPolicy.uprightPoint(
                (sourceX + .5f) / width, 1f - (sourceY + .5f) / height, rotation))
            val sampleIndex = (point.y * upright.height).toInt() * upright.width +
                (point.x * upright.width).toInt()
            assertEquals(1, upright.argb[sampleIndex])
            val aligned = PortraitMaskPolicy.alignToSource(
                FloatArray(upright.argb.size) { upright.argb[it].toFloat() },
                upright.width, upright.height, width, height, rotation, 100L)
            assertEquals(1f, aligned.confidence[(height - 1 - sourceY) * width + sourceX], .00001f)
        }
    }

    @Test fun legacySigmoidAndTwoChannelLayoutsChooseForeground() {
        assertEquals(0, ObjectMaskPolicy.foregroundMaskIndex(1))
        assertEquals(1, ObjectMaskPolicy.foregroundMaskIndex(2))
    }

    @Test(expected = IllegalStateException::class) fun unexpectedLayoutFailsExplicitly() {
        ObjectMaskPolicy.foregroundMaskIndex(3)
    }

    @Test fun emptyNoiseAndWholeBackgroundDoNotEnableBlur() {
        assertFalse(ObjectMaskPolicy.hasObject(mask { _, _ -> 0f }))
        assertFalse(ObjectMaskPolicy.hasObject(mask { _, _ -> Float.NaN }))
        assertFalse(ObjectMaskPolicy.hasObject(mask { x, y -> if (x == 1 && y == 1) 1f else 0f }))
        assertFalse(ObjectMaskPolicy.hasObject(mask { _, _ -> 1f }))
        assertTrue(ObjectMaskPolicy.hasObject(mask { x, y -> if (x in 4..12 && y in 4..12) .9f else 0f }))
    }

    @Test fun componentKeepsSelectedObjectAndItsSoftEdge() {
        val source = mask { x, y -> when {
            x in 3..6 && y in 7..11 -> .9f
            x == 7 && y in 7..11 -> .3f
            x in 13..16 && y in 7..11 -> 1f
            else -> 0f
        } }
        val selected = ObjectMaskPolicy.selectedComponent(source, ObjectMaskPolicy.Point(.225f, .475f))
        assertTrue(ObjectMaskPolicy.hasObject(selected))
        assertEquals(.9f, selected.confidence[9 * 20 + 4], 0f)
        assertEquals(.3f, selected.confidence[9 * 20 + 7], 0f)
        assertEquals(0f, selected.confidence[9 * 20 + 14], 0f)
    }

    @Test fun backgroundTapCannotJumpToDistantObject() {
        val source = mask { x, y -> if (x in 3..6 && y in 7..11) 1f else 0f }
        val selected = ObjectMaskPolicy.selectedComponent(source, ObjectMaskPolicy.Point(.9f, .5f))
        assertFalse(ObjectMaskPolicy.hasObject(selected))
        assertTrue(selected.confidence.all { it == 0f })
    }

    @Test fun hollowObjectReseedsInsideForegroundRatherThanItsHole() {
        val source = mask { x, y -> if ((x in 4..14 && y in 4..14) &&
            (x <= 6 || x >= 12 || y <= 6 || y >= 12)) .9f else 0f }
        val seed = requireNotNull(ObjectMaskPolicy.trackingPoint(source, ObjectMaskPolicy.Point(.25f, .5f)))
        assertTrue(PortraitMaskPolicy.confidenceAt(source, seed.x, seed.y) >= .65f)
        assertFalse(seed.x in .35f.. .6f && seed.y in .35f.. .6f)
    }

    @Test fun abruptObjectChangeStopsAssistedReseeding() {
        val old = mask { x, y -> if (x in 3..6 && y in 7..11) 1f else 0f }
        val moved = mask { x, y -> if (x in 13..16 && y in 7..11) 1f else 0f }
        val expanded = mask { x, y -> if (x in 1..17 && y in 1..17) 1f else 0f }
        assertNull(ObjectMaskPolicy.trackingPoint(moved, ObjectMaskPolicy.Point(.225f, .475f), old))
        assertNull(ObjectMaskPolicy.trackingPoint(expanded, ObjectMaskPolicy.Point(.225f, .475f), old))
    }

    @Test fun largeHollowObjectRemainsSelectedAcrossIdenticalFrames() {
        val ring = mask { x, y -> if (x in 2..17 && y in 2..17 &&
            !(x in 6..13 && y in 6..13)) 1f else 0f }
        val firstSeed = requireNotNull(ObjectMaskPolicy.trackingPoint(ring, ObjectMaskPolicy.Point(.2f, .5f)))
        // The projected prompt is more than 20% from the centroid, even with no movement.
        val dx = firstSeed.x - .5f
        val dy = firstSeed.y - .5f
        assertTrue(dx * dx + dy * dy > .2f * .2f)
        val nextSeed = requireNotNull(ObjectMaskPolicy.trackingPoint(ring, firstSeed, ring))
        assertEquals(firstSeed, nextSeed)
        assertTrue(PortraitMaskPolicy.confidenceAt(ring, nextSeed.x, nextSeed.y) >= .65f)
    }

    @Test fun modestMovementKeepsAnInteriorPrompt() {
        val old = mask { x, y -> if (x in 3..6 && y in 7..11) 1f else 0f }
        val moved = mask { x, y -> if (x in 4..7 && y in 7..11) 1f else 0f }
        val seed = requireNotNull(ObjectMaskPolicy.trackingPoint(moved, ObjectMaskPolicy.Point(.225f, .475f), old))
        assertTrue(seed.x in .225f.. .375f)
        assertTrue(PortraitMaskPolicy.confidenceAt(moved, seed.x, seed.y) >= .65f)
    }

    @Test fun objectContourExpiresFromCaptureBeforeAnOldSilhouetteCanPersistForSeconds() {
        assertEquals(1f, ObjectMaskPolicy.freshness(550L, 100L), 0f)
        assertEquals(.5f, ObjectMaskPolicy.freshness(925L, 100L), .00001f)
        assertEquals(0f, ObjectMaskPolicy.freshness(1_300L, 100L), 0f)
        assertEquals(0f, ObjectMaskPolicy.freshness(100L, -1L), 0f)
    }

    @Test fun inferenceDelayConsumesFreshnessInsteadOfRenewingTheCapturedTimestamp() {
        val capturedAt = 2_000L
        val completedAt = 2_900L
        assertEquals(.4f, ObjectMaskPolicy.freshness(completedAt, capturedAt), .00001f)
        assertEquals(0f, ObjectMaskPolicy.freshness(completedAt + 300L, capturedAt), 0f)
    }

    @Test fun expiredFutureOrInvalidClocksCannotPresentAnObjectAsFresh() {
        assertEquals(0f, ObjectMaskPolicy.freshness(99L, 100L), 0f)
        assertEquals(0f, ObjectMaskPolicy.freshness(-1L, 100L), 0f)
        assertEquals(0f, ObjectMaskPolicy.freshness(Long.MAX_VALUE, 0L), 0f)
    }

    @Test fun fadeIsMonotonicAndBoundedUntilExpiry() {
        val values = (0L..1_500L step 50L).map { ObjectMaskPolicy.freshness(it, 0L) }
        assertTrue(values.all { it in 0f..1f })
        assertTrue(values.zipWithNext().all { (a, b) -> b <= a })
        assertEquals(0f, values.last(), 0f)
    }
}
