package com.lumacamera.effects

import com.lumacamera.camera.CaptureSettings
import com.lumacamera.core.FrameMotionEstimate
import com.lumacamera.core.StabilizationPolicy
import com.lumacamera.core.StabilizationTuning
import org.junit.Assert.*
import org.junit.Test

class MotionAnalysisConfigurationPolicyTest {
    private val settings = CaptureSettings(stabilizationEnabled = true)

    @Test fun zoomRejectsOldAnalysisWithoutReplacingTheDisplayedCorrection() {
        val gate = MotionAnalysisGate()
        val ticket = gate.tryBegin(1_000L)!!
        val policy = StabilizationPolicy()
        val tuning = StabilizationTuning(intensity = 1f, cropZoom = 1.2f)
        policy.transform(0L, tuning)
        for (time in 100L..1_000L step 100L) {
            policy.update(FrameMotionEstimate(.015f, 0f, 1f, true, reason = "tracked"), time, tuning)
            policy.transform(time, tuning)
        }
        val before = policy.transform(1_090L, tuning)
        assertTrue(before.centerOffsetX > 0f)
        val change = MotionAnalysisConfigurationPolicy.change(settings, settings.copy(zoomRatio = 1.2f))
        if (change.resetReference) gate.invalidate()
        if (change.resetCorrection) policy.reset()
        assertFalse(gate.shouldAccept(ticket, 1_100L))
        assertFalse(change.resetCorrection)
        assertEquals(before, policy.transform(1_090L, tuning))
        assertNull(gate.tryBegin(1_100L)) // Invalidation cannot release the old job's scratch buffer.
        gate.complete(ticket)
        assertNotNull(gate.tryBegin(1_110L))
    }

    @Test fun liveStrengthResponseAndDisplayEditsKeepTheAnalysisReference() {
        val gate = MotionAnalysisGate()
        val ticket = gate.tryBegin(1_000L)!!
        for (next in listOf(settings.copy(stabilizationStrength = .2f),
            settings.copy(stabilizationResponse = .8f), settings.copy(previewLutEnabled = true),
            settings.copy(logEnabled = true), settings.copy(waveformEnabled = true))) {
            val change = MotionAnalysisConfigurationPolicy.change(settings, next)
            assertFalse(change.resetReference); assertFalse(change.resetCorrection)
            if (change.resetReference) gate.invalidate()
        }
        assertTrue(gate.shouldAccept(ticket, 1_100L))
    }

    @Test fun cropQualityAndMasterChangesReplaceGeometryButGuideChangesOnlyRefreshReference() {
        for (next in listOf(settings.copy(stabilizationCrop = .2f),
            settings.copy(stabilizationQuality = 1), settings.copy(stabilizationEnabled = false))) {
            val change = MotionAnalysisConfigurationPolicy.change(settings, next)
            assertTrue(change.resetReference); assertTrue(change.resetCorrection)
        }
        for (next in listOf(settings.copy(stabilizationMode = 1),
            settings.copy(stabilizationHorizonCorrection = false),
            settings.copy(stabilizationUseSubjectMask = false), settings.copy(subjectTrackingEnabled = true),
            settings.copy(objectTapRevision = 1L), settings.copy(subjectMode = 1))) {
            val change = MotionAnalysisConfigurationPolicy.change(settings, next)
            assertTrue(change.resetReference); assertFalse(change.resetCorrection)
        }
    }
}
