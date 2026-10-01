package com.lumacamera.core

import org.junit.Assert.*
import org.junit.Test

class RecordingProbePolicyTest {
    @Test fun temporaryNativeFailureCannotPoisonTheUnchangedClipForever() {
        assertFalse(RecordingProbePolicy.shouldRetry(false, 1000L, 10_999L))
        assertTrue(RecordingProbePolicy.shouldRetry(false, 1000L, 11_000L))
        assertTrue(RecordingProbePolicy.shouldRetry(false, 1000L, 60_000L))
    }

    @Test fun repeatedRefreshesWithinRetryWindowDoNotReopenTheNativeParser() {
        for (now in 1000L..10_999L step 200L)
            assertFalse(RecordingProbePolicy.shouldRetry(false, 1000L, now))
    }

    @Test fun validImmutableMetadataDoesNotRequireRepeatedNativeProbing() {
        assertFalse(RecordingProbePolicy.shouldRetry(true, 1000L, Long.MAX_VALUE))
    }

    @Test fun aReversedClockOrMissingProbeTimestampAllowsRecoveryWithoutOverflow() {
        assertTrue(RecordingProbePolicy.shouldRetry(false, 20_000L, 1000L))
        assertTrue(RecordingProbePolicy.shouldRetry(false, -1L, Long.MAX_VALUE))
        assertTrue(RecordingProbePolicy.shouldRetry(false, 0L, Long.MAX_VALUE))
    }
}
