package com.lumacamera.ui

import com.lumacamera.camera.CaptureSettings
import org.junit.Assert.*
import org.junit.Test

class CameraPreferencesTest {
    @Test fun professionalPreviewFlagsStayIndependentAndInvalidMotionValuesAreSafe() {
        val value = normalizeCaptureSettings(CaptureSettings(waveformEnabled = true, rgbParadeEnabled = false,
            vectorscopeEnabled = true, previewLutEnabled = true, previewLutStrength = Float.NaN,
            zoomRatio = Float.POSITIVE_INFINITY, subjectTrackingEnabled = true, portraitEnabled = false))
        assertTrue(value.waveformEnabled)
        assertFalse(value.rgbParadeEnabled)
        assertTrue(value.vectorscopeEnabled)
        assertTrue(value.previewLutEnabled)
        assertEquals(1f, value.previewLutStrength, 0f)
        assertEquals(1f, value.zoomRatio, 0f)
        assertTrue(value.subjectTrackingEnabled)
        assertFalse(value.portraitEnabled)
        assertEquals(20f, normalizeCaptureSettings(CaptureSettings(zoomRatio = 500f)).zoomRatio, 0f)
    }
    @Test fun monitorToolsDoNotChangeIndependentRecordingEffectsOrLocks() {
        val value = CaptureSettings(histogramEnabled = true, zebraEnabled = false, falseColorEnabled = true,
            peakingEnabled = false, audioMeterEnabled = true, logEnabled = true, portraitEnabled = true,
            aeLockEnabled = true, awbLockEnabled = false, videoBitrateScale = 1.5f)
        assertEquals(value, normalizeCaptureSettings(value))
        val prior = CaptureSettings(aeLockEnabled = false, awbLockEnabled = true)
        val rollback = value.withHardwareFrom(prior)
        assertTrue(rollback.histogramEnabled)
        assertTrue(rollback.falseColorEnabled)
        assertTrue(rollback.logEnabled)
        assertTrue(rollback.portraitEnabled)
        assertEquals(1.5f, rollback.videoBitrateScale, 0f)
        assertFalse(rollback.aeLockEnabled)
        assertTrue(rollback.awbLockEnabled)
    }

    @Test fun corruptMonitorAndQualityValuesAreBoundedBeforeGpuAndRecorder() {
        val cleaned = normalizeCaptureSettings(CaptureSettings(zebraThreshold = Float.NaN,
            peakingStrength = 9f, monitorOverlayStrength = -1f, videoBitrateScale = Float.POSITIVE_INFINITY))
        assertEquals(.95f, cleaned.zebraThreshold, 0f)
        assertEquals(1f, cleaned.peakingStrength, 0f)
        assertEquals(0f, cleaned.monitorOverlayStrength, 0f)
        assertEquals(1f, cleaned.videoBitrateScale, 0f)
        assertEquals(.5f, normalizeCaptureSettings(CaptureSettings(zebraThreshold = -1f)).zebraThreshold, 0f)
        assertEquals(2f, normalizeCaptureSettings(CaptureSettings(videoBitrateScale = 100f)).videoBitrateScale, 0f)
    }

    @Test fun compositionOptionsKeepIndependentVisibilityAndClampStoredModes() {
        val cleaned = normalizeAppPreferences(AppPreferences(grid = false, gridMode = 999, frameGuide = -3,
            safeArea = true, level = true))
        assertFalse(cleaned.grid)
        assertTrue(cleaned.safeArea)
        assertTrue(cleaned.level)
        assertEquals(2, cleaned.gridMode)
        assertEquals(0, cleaned.frameGuide)
    }

    @Test fun selectedObjectAndIndependentSettingsSurviveNormalization() {
        val value = CaptureSettings(subjectMode = 1, objectPointSelected = true,
            objectFocusX = .25f, objectFocusY = .75f, objectTapRevision = 42L,
            focusEnabled = false, portraitEnabled = true, cinematicEnabled = false, logEnabled = true)
        assertEquals(value, normalizeCaptureSettings(value))
        assertFalse(CaptureSettings(subjectMode = 1).objectPointSelected)
    }

    @Test fun invalidObjectCoordinatesCannotCreateASelectedCenterObject() {
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f, 2f)) {
            val cleaned = normalizeCaptureSettings(CaptureSettings(subjectMode = 999,
                objectPointSelected = true, objectFocusX = invalid, objectFocusY = .6f))
            assertEquals(0, cleaned.subjectMode)
            assertFalse(cleaned.objectPointSelected)
            assertTrue(cleaned.objectFocusX.isFinite())
        }
    }

    @Test fun supportedProfileVersionsSurviveAndUnknownVersionsFallBackToCurrent() {
        assertEquals(1, normalizeCaptureSettings(CaptureSettings(logProfileVersion = 1)).logProfileVersion)
        assertEquals(2, normalizeCaptureSettings(CaptureSettings(logProfileVersion = 2)).logProfileVersion)
        val invalid = normalizeCaptureSettings(CaptureSettings(logEnabled = true, logProfileVersion = 99))
        assertEquals(2, invalid.logProfileVersion)
        assertTrue(invalid.logEnabled)
    }

    @Test fun logAndPortraitRemainIndependentAcrossNormalization() {
        val original = CaptureSettings(logEnabled = true, logPreviewAssist = true,
            portraitEnabled = false, cinematicEnabled = true, cinematicAutoFocus = false,
            cinematicTapFocus = true, cinematicTapRevision = 12L, logStrength = .75f,
            focusBackground = true, portraitQuality = 1)
        assertEquals(original, normalizeCaptureSettings(original))
    }

    @Test fun invalidAiAndLogValuesCannotReachTheCompositor() {
        val cleaned = normalizeCaptureSettings(CaptureSettings(
            logStrength = Float.NaN, portraitStrength = 9f, portraitQuality = 999,
            cinematicTransitionSeconds = Float.POSITIVE_INFINITY,
            cinematicFocusX = -100f, cinematicFocusY = Float.NaN
        ))
        assertEquals(1f, cleaned.logStrength, 0f)
        assertEquals(1f, cleaned.portraitStrength, 0f)
        assertEquals(1, cleaned.portraitQuality)
        assertEquals(1f, cleaned.cinematicTransitionSeconds, 0f)
        assertEquals(0f, cleaned.cinematicFocusX, 0f)
        assertEquals(.5f, cleaned.cinematicFocusY, 0f)
    }

    @Test fun invalidStabilizationAndRefinementValuesCannotReachTheSampler() {
        val defaults = CaptureSettings()
        val cleaned = normalizeCaptureSettings(defaults.copy(stabilizationEnabled = true,
            stabilizationStrength = Float.NaN, stabilizationResponse = Float.POSITIVE_INFINITY,
            stabilizationCrop = 10f, stabilizationQuality = -2,
            portraitStability = -1f, portraitEdgeSoftness = Float.NaN))
        assertTrue(cleaned.stabilizationEnabled)
        assertEquals(defaults.stabilizationStrength, cleaned.stabilizationStrength, 0f)
        assertEquals(defaults.stabilizationResponse, cleaned.stabilizationResponse, 0f)
        assertEquals(.25f, cleaned.stabilizationCrop, 0f)
        assertEquals(0, cleaned.stabilizationQuality)
        assertEquals(0f, cleaned.portraitStability, 0f)
        assertEquals(defaults.portraitEdgeSoftness, cleaned.portraitEdgeSoftness, 0f)
        assertFalse(defaults.stabilizationEnabled)
    }

    @Test fun corruptStoredFloatsFallBackBeforeReachingShaders() {
        val fallback = CaptureSettings()
        val cleaned = normalizeCaptureSettings(fallback.copy(
            gainStops = Float.NaN, temperature = Float.POSITIVE_INFINITY,
            blurCenterX = Float.NEGATIVE_INFINITY, trailAmount = Float.NaN
        ))
        assertEquals(fallback.gainStops, cleaned.gainStops, 0f)
        assertEquals(fallback.temperature, cleaned.temperature, 0f)
        assertEquals(fallback.blurCenterX, cleaned.blurCenterX, 0f)
        assertEquals(fallback.trailAmount, cleaned.trailAmount, 0f)
    }

    @Test fun stabilizationModesNormalizeWithoutChangingIndependentOptions() {
        for (mode in 0..2) {
            val settings = CaptureSettings(stabilizationEnabled = true, stabilizationMode = mode,
                stabilizationHorizonCorrection = false, stabilizationUseSubjectMask = true,
                stabilizationStrength = .82f, stabilizationCrop = .19f, logEnabled = true)
            assertEquals(settings, normalizeCaptureSettings(settings))
        }
        for (mode in listOf(-1, 3, Int.MAX_VALUE)) {
            val settings = normalizeCaptureSettings(CaptureSettings(stabilizationMode = mode,
                stabilizationHorizonCorrection = false, portraitEnabled = false))
            assertEquals(0, settings.stabilizationMode)
            assertFalse(settings.stabilizationHorizonCorrection)
            assertFalse(settings.portraitEnabled)
        }
    }

    @Test fun obsoleteValuesAreSafeWithoutCouplingIndependentSwitches() {
        val cleaned = normalizeCaptureSettings(CaptureSettings(
            isoEnabled = true, shutterEnabled = false, gainEnabled = false,
            blurEnabled = true, trailEnabled = false,
            iso = -1, exposureNs = -1, whiteBalance = 999,
            blurRadius = 5f, blurAmount = -1f, trailAmount = 1f
        ))
        assertTrue(cleaned.isoEnabled)
        assertFalse(cleaned.shutterEnabled)
        assertFalse(cleaned.gainEnabled)
        assertTrue(cleaned.blurEnabled)
        assertFalse(cleaned.trailEnabled)
        assertTrue(cleaned.iso > 0)
        assertTrue(cleaned.exposureNs > 0)
        assertEquals(CaptureSettings().whiteBalance, cleaned.whiteBalance)
        assertEquals(.7f, cleaned.blurRadius, 0f)
        assertEquals(0f, cleaned.blurAmount, 0f)
        assertEquals(.9f, cleaned.trailAmount, 0f)
    }

    @Test fun incompleteVideoModeFallsBackAsAWhole() {
        val cleaned = normalizeAppPreferences(AppPreferences(videoWidth = 1920, videoHeight = 1080, videoFps = 0))
        assertEquals(0, cleaned.videoWidth)
        assertEquals(0, cleaned.videoHeight)
        assertEquals(0, cleaned.videoFps)
    }

    @Test fun validSelectionAndDisabledAppOptionsSurviveNormalization() {
        val value = AppPreferences(microphoneEnabled = false, grid = false,
            keepScreenOn = false, volumeShutter = false, defaultCameraId = "0",
            videoWidth = 1920, videoHeight = 1080, videoFps = 30)
        assertEquals(value, normalizeAppPreferences(value))
        assertNull(normalizeAppPreferences(value.copy(defaultCameraId = "  ")).defaultCameraId)
    }
}
