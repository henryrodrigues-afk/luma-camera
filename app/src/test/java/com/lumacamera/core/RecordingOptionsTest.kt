package com.lumacamera.core

import org.junit.Assert.*
import org.junit.Test

class RecordingOptionsTest {
    @Test fun optionsBoundUntrustedInputs() {
        val options = RecordingOptions(-8, true, Int.MAX_VALUE, "\n  Projeto\u0000  ", "Cena\t1", -5,
            "x".repeat(40_000)).sanitized()
        assertNull(options.audioDeviceId)
        assertEquals(3072, options.splitSizeMb)
        assertEquals("Projeto", options.projectName)
        assertEquals("Cena1", options.sceneName)
        assertEquals(1, options.takeNumber)
        assertEquals(32_768, options.captureMetadata!!.length)
    }
    @Test fun splitHasMinimumAndNeverExceedsSafeDiskBudget() {
        val options = RecordingOptions(splitEnabled = true, splitSizeMb = 2)
        assertEquals(64L * StoragePolicy.MIB, RecordingSegmentPolicy.segmentLimitBytes(options, 512L * StoragePolicy.MIB))
        assertEquals(32L * StoragePolicy.MIB, RecordingSegmentPolicy.segmentLimitBytes(options, 160L * StoragePolicy.MIB))
    }
    @Test fun retainedPartsReserveTheirGalleryCopies() {
        assertEquals(52L * StoragePolicy.MIB,
            RecordingSegmentPolicy.remainingSessionBytes(400L * StoragePolicy.MIB, 200L * StoragePolicy.MIB))
        assertFalse(RecordingSegmentPolicy.canQueueNext(400L * StoragePolicy.MIB, 200L * StoragePolicy.MIB,
            64L * StoragePolicy.MIB))
        assertTrue(RecordingSegmentPolicy.canQueueNext(512L * StoragePolicy.MIB, 64L * StoragePolicy.MIB,
            128L * StoragePolicy.MIB))
    }
    @Test fun hugeRetainedPartsCannotOverflowBudget() {
        assertEquals(0L, RecordingSegmentPolicy.remainingSessionBytes(Long.MAX_VALUE, Long.MAX_VALUE))
    }
    @Test fun segmentNamesAreOrderedWithoutWrapping() {
        assertEquals("_P001", RecordingSegmentPolicy.partSuffix(1))
        assertEquals("_P1000", RecordingSegmentPolicy.partSuffix(1000))
    }
    @Test fun selectedInputNeverCountsAsAConfirmedRecordingRoute() {
        val status = AudioRoutePolicy.status(7, null, null, true, true)
        assertFalse(status.confirmed)
        assertNull(status.actualDeviceId)
    }
    @Test fun defaultFallbackReportsTheActualInputInsteadOfRequestedUsb() {
        val status = AudioRoutePolicy.status(7, 3, "Microfone interno", true, true)
        assertTrue(status.confirmed)
        assertEquals(3, status.actualDeviceId)
        assertTrue(status.message.contains("Microfone interno"))
    }
    @Test fun cachedRouteOutsideRecAndSilentTakesNeverClaimCurrentAudio() {
        assertNull(AudioRoutePolicy.status(7, 7, "USB", false, true).actualDeviceId)
        assertFalse(AudioRoutePolicy.status(7, 7, "USB", false, true).confirmed)
        assertFalse(AudioRoutePolicy.status(7, 7, "USB", true, false).confirmed)
        assertEquals("Sem áudio", AudioRoutePolicy.status(7, 7, "USB", true, false).label)
    }
    @Test fun outsideAppFilenamesKeepReadableProjectSceneAndTake() {
        assertEquals("Video-de-Produto_Cena-2_T000012",
            RecordingSegmentPolicy.filenameIdentity("Vídeo de Produto", "Cena 2", 12))
    }
    @Test fun identifiersCannotIntroducePathSyntaxOrOversizedNames() {
        val identity = RecordingSegmentPolicy.filenameIdentity("../" + "A".repeat(200), "..\\..//\u0000💡", Int.MAX_VALUE)
        assertTrue(identity.matches(Regex("[A-Za-z0-9_-]+")))
        assertFalse(identity.contains(".."))
        assertTrue(identity.length <= 57)
        assertTrue(identity.endsWith("T999999"))
    }
    @Test fun anonymousAndNonAsciiProjectsStillHaveANumberedTake() {
        assertEquals("T000001", RecordingSegmentPolicy.filenameIdentity("", "", 0))
        assertEquals("T000003", RecordingSegmentPolicy.filenameIdentity("电影", "💡", 3))
    }

    @Test fun pendingNativeSwitchCannotAssignTheWholeTakeDurationToThePreviousPart() {
        // Native may have switched 15 seconds into a 30-second take before STOP wins the callback race.
        val estimate = RecordingSegmentPolicy.finalPartDurationEstimate(30_000L, true)
        assertNull(estimate)
        assertTrue(RecordingValidation.durationIsPlausible(15_000L, 0L, estimate))
        assertFalse(RecordingValidation.durationIsPlausible(15_000L, 0L, 30_000L))
        assertEquals(15_000L, RecordingSegmentPolicy.finalPartDurationEstimate(15_000L, false))
        assertNull(RecordingSegmentPolicy.finalPartDurationEstimate(0L, false))
    }
}
