package com.lumacamera.core

import org.junit.Assert.*
import org.junit.Test

class RecordingTimelineTest {
    private val origin = 123_000_000_000_000L

    @Test fun recorderReceivesAbsoluteMonotonicOriginNotZeroOrCameraBootOffset() {
        val clock = RecordingTimeline(30)
        assertEquals(origin, clock.presentationTime(origin)!!)
        assertEquals(origin + 40_000_000L, clock.presentationTime(origin + 40_000_000L)!!)
        // An arbitrary camera clock is deliberately absent from the presentation API.
        assertTrue(RecordingTimeline.isNewCameraFrame(origin + 86_400_000_000_000L, 100L))
    }

    @Test fun realGapsNeverCompressToFrameCountDurationOrScheduleFramesInTheFuture() {
        val clock = RecordingTimeline(30)
        val submitted = listOf(0L, 34L, 400L, 800L, 2_000L).map {
            clock.presentationTime(origin + it * 1_000_000L)!!
        }
        assertEquals(2_000_000_000L, submitted.last() - submitted.first())
        assertTrue(submitted.zipWithNext().all { (a, b) -> b > a })
        assertEquals(origin + 2_034_000_000L, clock.presentationTime(origin + 2_034_000_000L)!!)
    }

    @Test fun pacingUsesMonotonicTimeAndDoesNotCatchUpAfterAWorkerStall() {
        val clock = RecordingTimeline(30)
        assertNotNull(clock.presentationTime(origin))
        assertNull(clock.presentationTime(origin + 10_000_000L))
        assertNull(clock.presentationTime(origin + 20_000_000L))
        assertNotNull(clock.presentationTime(origin + 33_333_333L))
        assertNotNull(clock.presentationTime(origin + 1_000_000_000L))
        assertNull(clock.presentationTime(origin + 1_001_000_000L))
        assertNotNull(clock.presentationTime(origin + 1_033_333_333L))
    }

    @Test fun duplicateCameraCallbacksAreRejectedButSourceClockResetCannotFreezePreview() {
        assertFalse(RecordingTimeline.isNewCameraFrame(5_000L, 5_000L))
        assertTrue(RecordingTimeline.isNewCameraFrame(1_000L, 5_000L))
        assertTrue(RecordingTimeline.isNewCameraFrame(0L, 5_000L))
        assertTrue(RecordingTimeline.isNewCameraFrame(-1L, 5_000L))
        assertTrue(RecordingTimeline.isNewCameraFrame(5_000L, null))
    }

    @Test fun invalidOrDuplicateMonotonicReadingsAreNotInventedAsFuturePts() {
        val clock = RecordingTimeline(30)
        assertNotNull(clock.presentationTime(origin))
        assertNull(clock.presentationTime(origin))
        assertNull(clock.presentationTime(origin - 1_000L))
        assertNull(clock.presentationTime(origin + 999L))
    }

    @Test fun newClipStartsItsOwnPacingButKeepsTheSystemClockOrigin() {
        val first = RecordingTimeline(24)
        assertEquals(origin, first.presentationTime(origin)!!)
        assertNotNull(first.presentationTime(origin + 50_000_000L))
        val second = RecordingTimeline(24)
        assertEquals(origin + 5_000_000_000L, second.presentationTime(origin + 5_000_000_000L)!!)
    }

    @Test fun elapsedCounterExcludesPrepareAndPreviousClips() {
        assertEquals(0L, RecordingTimeline.elapsedMs(null, origin, true))
        assertEquals(0L, RecordingTimeline.elapsedMs(origin, origin + 1_000_000_000L, false))
        assertEquals(0L, RecordingTimeline.elapsedMs(origin, origin - 1_000L, true))
        assertEquals(1_234L, RecordingTimeline.elapsedMs(origin, origin + 1_234_999_999L, true))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidFrameRateIsRejected() { RecordingTimeline(0) }
}
