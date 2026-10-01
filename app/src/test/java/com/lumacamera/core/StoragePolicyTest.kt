package com.lumacamera.core

import org.junit.Assert.*
import org.junit.Test

class StoragePolicyTest {
    @Test fun startRequiresReserveAndRoomForBothCopies() {
        assertFalse(StoragePolicy.canStart(StoragePolicy.MIN_START_BYTES))
        assertTrue(StoragePolicy.canStart(StoragePolicy.MIN_START_BYTES + 1L))
        assertEquals(32L * StoragePolicy.MIB, StoragePolicy.maxRecordingFileBytes(160L * StoragePolicy.MIB))
    }

    @Test fun concurrentDiskUseReducesFurtherRecordingBeforePublicationFails() {
        val clip = 100L * StoragePolicy.MIB
        assertEquals(102L * StoragePolicy.MIB,
            StoragePolicy.remainingRecordingBytes(400L * StoragePolicy.MIB, clip))
        assertEquals(0L, StoragePolicy.remainingRecordingBytes(196L * StoragePolicy.MIB, clip))
        assertEquals(0L, StoragePolicy.remainingRecordingBytes(100L * StoragePolicy.MIB, clip))
    }

    @Test fun growingClipConsumesBothOriginalAndCopyBudget() {
        val before = StoragePolicy.remainingRecordingBytes(400L * StoragePolicy.MIB, 100L * StoragePolicy.MIB)
        val after = StoragePolicy.remainingRecordingBytes(390L * StoragePolicy.MIB, 110L * StoragePolicy.MIB)
        assertEquals(10L * StoragePolicy.MIB, before - after)
    }

    @Test fun recorderLimitAndExtremeInputsStayBounded() {
        assertEquals(25L, StoragePolicy.remainingRecordingBytes(Long.MAX_VALUE, 100L, 125L))
        assertEquals(0L, StoragePolicy.remainingRecordingBytes(Long.MAX_VALUE, Long.MAX_VALUE))
        assertEquals(0L, StoragePolicy.maxRecordingFileBytes(-1L))
        assertEquals(StoragePolicy.MAX_FILE_BYTES, StoragePolicy.maxRecordingFileBytes(Long.MAX_VALUE))
        assertEquals(0L, StoragePolicy.remainingRecordingBytes(-1L, -1L, -1L))
    }

    @Test fun estimateIncludesAudioWithoutOverflowOrInventingUnknownBitrate() {
        assertEquals(8_000L, StoragePolicy.estimatedRemainingMs(1_000_000L, 1_000_000, false))
        assertTrue(StoragePolicy.estimatedRemainingMs(1_000_000L, 1_000_000, true)!! < 8_000L)
        assertNull(StoragePolicy.estimatedRemainingMs(1_000_000L, 0, true))
        assertEquals(Long.MAX_VALUE, StoragePolicy.estimatedRemainingMs(Long.MAX_VALUE, 1, false))
    }

    @Test fun failedParsingNeverAuthorizesDeletingNonemptyOriginal() {
        assertTrue(StoragePolicy.canDiscardIncomplete(0L))
        assertFalse(StoragePolicy.canDiscardIncomplete(1L))
        assertFalse(StoragePolicy.canDiscardIncomplete(Long.MAX_VALUE))
    }

    @Test fun aPreviousPublicationDoesNotConsumeTheNewClipsIndividualFileLimit() {
        val remaining = StoragePolicy.remainingRecordingBytes(2048L * StoragePolicy.MIB,
            10L * StoragePolicy.MIB, 512L * StoragePolicy.MIB, 700L * StoragePolicy.MIB)
        assertEquals(502L * StoragePolicy.MIB, remaining)
        assertTrue(remaining > 0L) // The old combined-byte calculation stopped this recording.
    }

    @Test fun previousGalleryCopiesStillReserveDiskWithoutOverflow() {
        assertEquals(2L * StoragePolicy.MIB, StoragePolicy.remainingRecordingBytes(
            800L * StoragePolicy.MIB, 100L * StoragePolicy.MIB, 1024L * StoragePolicy.MIB,
            600L * StoragePolicy.MIB))
        assertEquals(0L, StoragePolicy.remainingRecordingBytes(Long.MAX_VALUE, 1L,
            Long.MAX_VALUE, Long.MAX_VALUE))
        assertEquals(Long.MAX_VALUE, StoragePolicy.saturatedSum(Long.MAX_VALUE, 1L))
        assertEquals(5L, StoragePolicy.saturatedSum(-1L, 5L))
    }

    @Test fun onlyTheUnwrittenGalleryCopyIsReservedAsPublicationProgresses() {
        assertEquals(1000L, StoragePolicy.publicationBytesRemaining(1000L, 0L))
        assertEquals(400L, StoragePolicy.publicationBytesRemaining(1000L, 600L))
        assertEquals(0L, StoragePolicy.publicationBytesRemaining(1000L, 1200L))
        assertEquals(1000L, StoragePolicy.publicationBytesRemaining(1000L, -1L))
        assertEquals(0L, StoragePolicy.publicationBytesRemaining(-1L, Long.MAX_VALUE))
        assertEquals(Long.MAX_VALUE - 1L, StoragePolicy.publicationBytesRemaining(Long.MAX_VALUE, 1L))
    }

    @Test fun galleryCopyMustLeaveFinalizationReserveForCapture() {
        assertFalse(StoragePolicy.canPublish(192L * StoragePolicy.MIB, 96L * StoragePolicy.MIB))
        assertTrue(StoragePolicy.canPublish(193L * StoragePolicy.MIB, 96L * StoragePolicy.MIB))
        assertFalse(StoragePolicy.canPublish(Long.MAX_VALUE, Long.MAX_VALUE))
        assertFalse(StoragePolicy.canPublish(Long.MAX_VALUE, 0L))
        assertFalse(StoragePolicy.canPublish(-1L, 1L))
    }
}
