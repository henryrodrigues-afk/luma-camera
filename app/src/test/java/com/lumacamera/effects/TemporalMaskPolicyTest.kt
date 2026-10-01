package com.lumacamera.effects

import com.lumacamera.core.ObjectMaskPolicy
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class TemporalMaskPolicyTest {
    private fun mask(time: Long, width: Int = 20, height: Int = 20, value: (Int, Int) -> Float) =
        PortraitMaskPolicy.Mask(width, height, FloatArray(width * height) { value(it % width, it / width) }, time)
    private fun person(time: Long, confidence: Float = .9f) =
        mask(time) { x, y -> if (x in 5..14 && y in 3..16) confidence else 0f }
    private fun policy() = TemporalMaskPolicy(TemporalMaskPolicy.Kind.PERSON)

    @Test fun staticConfidenceFluctuationIsReducedWithoutChangingCaptureTimestamp() {
        val filter = policy()
        filter.process(person(0L), 0L)
        val result = filter.process(person(200L, .55f), 0L)
        val confidence = result.mask.confidence[9 * 20 + 9]
        assertTrue(confidence > .55f && confidence < .9f)
        assertTrue(abs(confidence - .9f) < abs(.55f - .9f) * .5f)
        assertTrue(result.subjectPresent)
        assertEquals(200L, result.mask.capturedAtMs)
        assertFalse(result.historyReset)
    }

    @Test fun highStabilityReducesMoreJitterAndZeroStabilityUsesCurrentMask() {
        fun filtered(stability: Float): Float {
            val filter = policy()
            filter.process(person(0L), 0L, stability)
            return filter.process(person(200L, .68f), 0L, stability).mask.confidence[9 * 20 + 9]
        }
        assertTrue(filtered(1f) > filtered(.3f))
        assertEquals(.68f, filtered(0f), .00001f)
    }

    @Test fun presenceHysteresisKeepsSoftEvidenceButNeverAnEmptySilhouette() {
        val filter = policy()
        assertTrue(filter.process(person(0L), 0L).subjectPresent)
        repeat(8) { step -> assertTrue(filter.process(person((step + 1) * 200L, .5f), 0L).subjectPresent) }
        val lost = filter.process(mask(1_800L) { _, _ -> 0f }, 0L)
        assertFalse(lost.subjectPresent)
        assertTrue(lost.mask.confidence.all { it == 0f })
        assertFalse(filter.process(person(2_000L, .5f), 0L).subjectPresent)
    }

    @Test fun weakEvidenceCannotInitializeAPersonAndOneNoisePixelCannotKeepThem() {
        assertFalse(policy().process(person(0L, .5f), 0L).subjectPresent)
        val filter = policy()
        filter.process(person(0L), 0L)
        val noise = mask(200L, 40, 40) { x, y -> if (x == 5 && y == 5) .8f else 0f }
        assertFalse(filter.process(noise, 0L).subjectPresent)
    }

    @Test fun hardLocalChangesUseCurrentPixelsWithoutDraggingTheOldSubject() {
        val filter = policy()
        val first = person(0L)
        filter.process(first, 0L, 1f)
        val current = person(200L).copy(confidence = person(200L).confidence.copyOf().apply {
            this[9 * 20 + 9] = 0f
            this[9 * 20 + 2] = .9f
        })
        val result = filter.process(current, 0L, 1f)
        assertEquals(0f, result.mask.confidence[9 * 20 + 9], 0f)
        assertEquals(.9f, result.mask.confidence[9 * 20 + 2], 0f)
    }

    @Test fun largeMovementDropsMaskHistoryInsteadOfLeavingATrail() {
        val filter = policy()
        val left = mask(0L) { x, y -> if (x in 1..5 && y in 5..14) .9f else 0f }
        val right = mask(200L) { x, y -> if (x in 13..17 && y in 5..14) .9f else 0f }
        filter.process(left, 0L, 1f)
        val result = filter.process(right, 0L, 1f)
        assertTrue(result.historyReset)
        assertArrayEquals(right.confidence, result.mask.confidence, 0f)
    }

    @Test fun smallMovementUpdatesSoftEdgesWithoutDraggingTheirFormerPosition() {
        fun softPerson(time: Long, shift: Int) = mask(time) { x, y ->
            when {
                y !in 3..16 -> .12f
                x in (5 + shift)..(14 + shift) -> .9f
                x == 4 + shift || x == 15 + shift -> .55f
                else -> .12f
            }
        }
        val filter = policy()
        filter.process(softPerson(0L, 0), 0L, 1f)
        val current = softPerson(125L, 1)
        val result = filter.process(current, 0L, 1f)
        assertFalse(result.historyReset)
        assertTrue(result.mask.confidence[9 * 20 + 4] < .20f)
        assertTrue(result.mask.confidence[9 * 20 + 16] >= .48f)
        assertEquals(125L, result.mask.capturedAtMs)
    }

    @Test fun opposingSubjectsMoveTheirSoftEdgesEvenWhenGlobalCenterAndAreaStayFixed() {
        fun twoPeople(time: Long, shift: Int) = mask(time) { x, y ->
            if (y !in 4..12) .3f
            else when {
                x in (3 + shift)..(4 + shift) || x in (15 - shift)..(16 - shift) -> .9f
                x in (2 + shift)..(5 + shift) || x in (14 - shift)..(17 - shift) -> .6f
                else -> .3f
            }
        }
        val filter = policy()
        filter.process(twoPeople(0L, 0), 0L, 1f)
        val result = filter.process(twoPeople(125L, 1), 0L, 1f)
        assertFalse(result.historyReset)
        assertTrue(result.mask.confidence[8 * 20 + 2] <= .35f)
        assertTrue(result.mask.confidence[8 * 20 + 6] >= .55f)
    }

    @Test fun rgbSceneChangeDiscardsHistoryEvenWhenSilhouettesMatch() {
        val filter = policy()
        filter.process(person(0L), 0L, 1f, FloatArray(144))
        val current = person(200L, .68f)
        val result = filter.process(current, 0L, 1f, FloatArray(144) { 1f })
        assertTrue(result.historyReset)
        assertArrayEquals(current.confidence, result.mask.confidence, 0f)
    }

    @Test fun newGenerationCannotReuseThePreviousPresenceOrMask() {
        val filter = policy()
        filter.process(person(0L), 0L, 1f)
        val result = filter.process(person(200L, .5f), 1L, 1f)
        assertFalse(result.subjectPresent)
        assertTrue(result.mask.confidence.all { it == 0f })
    }

    @Test fun longGapsResizeAndOutOfOrderFramesResetHistory() {
        val filter = policy()
        filter.process(person(0L), 0L, 1f)
        val late = filter.process(person(1_001L, .68f), 0L, 1f)
        assertTrue(late.historyReset)
        assertEquals(.68f, late.mask.confidence[9 * 20 + 9], 0f)
        assertTrue(filter.process(person(1_000L, .7f), 0L).historyReset)
        assertTrue(filter.process(mask(1_100L, 10, 10) { _, _ -> .8f }, 0L).historyReset)
    }

    @Test fun timeConstantDoesNotDependOnTheNumberOfAnalysisFrames() {
        val one = policy()
        val two = policy()
        one.process(person(0L), 0L, 1f)
        two.process(person(0L), 0L, 1f)
        val single = one.process(person(400L, .68f), 0L, 1f)
        two.process(person(200L, .68f), 0L, 1f)
        val repeated = two.process(person(400L, .68f), 0L, 1f)
        assertEquals(single.mask.confidence[9 * 20 + 9], repeated.mask.confidence[9 * 20 + 9], .00001f)
    }

    @Test fun anInvalidObjectClearsImmediatelyRatherThanRecoveringFromHistory() {
        val filter = TemporalMaskPolicy(TemporalMaskPolicy.Kind.OBJECT)
        assertTrue(filter.process(person(0L), 0L).subjectPresent)
        val result = filter.process(mask(334L) { _, _ -> 0f }, 0L)
        assertFalse(result.subjectPresent)
        assertTrue(result.mask.confidence.all { it == 0f })
    }

    @Test fun filteredObjectPreservesCurrentValidityAtItsConfidenceThreshold() {
        val filter = TemporalMaskPolicy(TemporalMaskPolicy.Kind.OBJECT)
        filter.process(person(0L), 0L, 1f)
        val shifted = mask(334L) { x, y -> if (x in 6..15 && y in 3..16) .65f else 0f }
        val result = filter.process(shifted, 0L, 1f)
        assertTrue(result.subjectPresent)
        assertTrue(ObjectMaskPolicy.hasObject(result.mask))
        for (index in shifted.confidence.indices) if (shifted.confidence[index] >= .65f) {
            assertTrue(result.mask.confidence[index] >= .65f)
        }
    }

    @Test fun negativeTimestampsAndNonFiniteConfidencesCannotEnableBlur() {
        assertFalse(policy().process(person(-1L), 0L).subjectPresent)
        val malformed = mask(0L) { _, _ -> Float.NaN }
        assertFalse(policy().process(malformed, 0L).subjectPresent)
    }

    @Test fun signatureReadsAllRgbChannelsAndHandlesTinyImages() {
        val red = TemporalMaskPolicy.sceneSignature(intArrayOf(0xFFFF0000.toInt()), 1, 1)
        assertEquals(144, red.size)
        assertEquals(1f, red[0], 0f); assertEquals(0f, red[1], 0f); assertEquals(0f, red[2], 0f)
        assertArrayEquals(red, TemporalMaskPolicy.sceneSignature(IntArray(12) { 0xFFFF0000.toInt() }, 4, 3), 0f)
    }

    @Test fun stabilityValuesAreNormalizedAndPresenceUsesAnExactMinimumArea() {
        assertEquals(.65f, TemporalMaskPolicy.normalizeStability(Float.NaN), 0f)
        assertEquals(0f, TemporalMaskPolicy.normalizeStability(-1f), 0f)
        assertEquals(1f, TemporalMaskPolicy.normalizeStability(2f), 0f)
        val small = mask(0L, 40, 25) { x, y -> if (y == 0 && x < 3) .8f else 0f }
        assertTrue(PortraitMaskPolicy.hasPerson(small))
        assertFalse(PortraitMaskPolicy.hasPerson(small.copy(confidence = small.confidence.copyOf().apply { this[2] = 0f })))
    }
}
