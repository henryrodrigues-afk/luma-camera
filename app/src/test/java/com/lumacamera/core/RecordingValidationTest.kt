package com.lumacamera.core

import org.junit.Assert.*
import org.junit.Test

class RecordingValidationTest {
    @Test fun completedDurationKeepsNativeStartupDrainAndRoundingTolerance() {
        assertTrue(RecordingValidation.durationIsPlausible(5_020L, 10_000L, 5_000L))
        assertTrue(RecordingValidation.durationIsPlausible(7_500L, 1_000_000L, 5_000L))
        assertTrue(RecordingValidation.durationIsPlausible(100L, 0L, 100L))
    }

    @Test fun aForeignClockOffsetCannotPublishAsANormalNewClip() {
        assertFalse(RecordingValidation.durationIsPlausible(86_405_000L, 86_400_000_000L, 5_000L))
        assertFalse(RecordingValidation.durationIsPlausible(5_000L, 86_400_000_000L, 5_000L))
        assertFalse(RecordingValidation.durationIsPlausible(30_000L, 0L, 5_000L))
    }

    @Test fun acceleratedOrTruncatedTimelineIsRejectedWithoutOverridingTheContainerDuration() {
        assertFalse(RecordingValidation.durationIsPlausible(1_000L, 0L, 10_000L))
        assertTrue(RecordingValidation.durationIsPlausible(11_000L, 0L, 10_000L))
        assertTrue(RecordingValidation.durationIsPlausible(8_000L, 0L, 10_000L))
    }

    @Test fun missingMetadataOrMissingSamplesNeverBecomeAnInventedElapsedDuration() {
        assertFalse(RecordingValidation.durationIsPlausible(0L, 0L, 5_000L))
        assertFalse(RecordingValidation.durationIsPlausible(-1L, 0L, 5_000L))
        assertFalse(RecordingValidation.durationIsPlausible(5_000L, -1L, 5_000L))
    }

    @Test fun olderClipsWithoutARecordedWallClockKeepTheirRealMetadata() {
        assertTrue(RecordingValidation.durationIsPlausible(86_405_000L, 86_400_000_000L, null))
        assertTrue(RecordingValidation.durationIsPlausible(Long.MAX_VALUE, 0L, Long.MAX_VALUE))
    }
}
