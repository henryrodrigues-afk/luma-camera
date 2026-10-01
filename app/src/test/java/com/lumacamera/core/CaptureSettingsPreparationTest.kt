package com.lumacamera.core

import com.lumacamera.camera.CaptureSettings
import org.junit.Assert.*
import org.junit.Test

class CaptureSettingsPreparationTest {
    private val configured = CaptureSettings(
        isoEnabled = true, shutterEnabled = true, iso = 800, exposureNs = 8_000_000L,
        focusEnabled = true, focusLockEnabled = true, focusDiopters = 2f,
        whiteBalanceEnabled = true, whiteBalance = 5, awbLockEnabled = true,
        aeLockEnabled = true, evEnabled = true, ev = 3,
        logEnabled = true, logStrength = .8f, logPreviewAssist = true,
        stabilizationEnabled = true, stabilizationCrop = .15f,
        gainEnabled = true, gainStops = 2f, contrastEnabled = true, contrast = 1.4f,
        saturationEnabled = true, saturation = .8f, temperatureEnabled = true, temperature = -.2f,
        waveformEnabled = true, videoBitrateScale = 1.5f, zoomRatio = 2f,
        cinematicEnabled = true, cinematicAutoFocus = false, cinematicTapFocus = true,
        focusBackground = true, blurEnabled = true,
    )

    @Test fun automaticBlurPreservesLensExposureColorLogAndRecordingConfiguration() {
        val result = configured.preparedForAutomaticBlur(SubjectFocusPolicy.PEOPLE, AutomaticBlurPolicy.NATURAL)
        assertTrue(result.portraitEnabled && result.subjectTrackingEnabled)
        assertTrue(result.cinematicEnabled && result.cinematicAutoFocus)
        assertFalse(result.cinematicTapFocus || result.focusBackground || result.blurEnabled)
        assertTrue(result.isoEnabled && result.shutterEnabled && result.aeLockEnabled && result.evEnabled)
        assertEquals(configured.iso, result.iso)
        assertEquals(configured.exposureNs, result.exposureNs)
        assertEquals(configured.ev, result.ev)
        assertTrue(result.focusEnabled && result.focusLockEnabled)
        assertEquals(configured.focusDiopters, result.focusDiopters, 0f)
        assertTrue(result.whiteBalanceEnabled && result.awbLockEnabled)
        assertEquals(configured.whiteBalance, result.whiteBalance)
        assertEquals(configured.logEnabled, result.logEnabled)
        assertEquals(configured.logStrength, result.logStrength, 0f)
        assertEquals(configured.logPreviewAssist, result.logPreviewAssist)
        assertEquals(configured.gainStops, result.gainStops, 0f)
        assertEquals(configured.contrast, result.contrast, 0f)
        assertEquals(configured.saturation, result.saturation, 0f)
        assertEquals(configured.temperature, result.temperature, 0f)
        assertEquals(configured.stabilizationCrop, result.stabilizationCrop, 0f)
        assertEquals(configured.videoBitrateScale, result.videoBitrateScale, 0f)
        assertTrue(result.waveformEnabled)
    }

    @Test fun choosingAnObjectPresetDoesNotInventACenterSelectionOrEnableCinema() {
        val result = CaptureSettings().preparedForAutomaticBlur(SubjectFocusPolicy.OBJECTS, AutomaticBlurPolicy.BALANCED)
        assertFalse(result.objectPointSelected)
        assertFalse(result.cinematicEnabled)
        assertTrue(SubjectFocusPolicy.needsObjectSelection(result.subjectMode, result.objectPointSelected))
    }

    @Test fun changingOnlyObjectStyleKeepsTheTappedObject() {
        val source = CaptureSettings(subjectMode = SubjectFocusPolicy.OBJECTS, objectPointSelected = true,
            objectFocusX = .2f, objectFocusY = .8f, objectTapRevision = 7L)
        val result = source.preparedForAutomaticBlur(SubjectFocusPolicy.OBJECTS, AutomaticBlurPolicy.STRONG)
        assertTrue(result.objectPointSelected)
        assertEquals(.2f, result.objectFocusX, 0f)
        assertEquals(.8f, result.objectFocusY, 0f)
        assertEquals(7L, result.objectTapRevision)
    }

    @Test fun switchingSubjectInvalidatesOldSelectionEvenAtRevisionOverflow() {
        val source = CaptureSettings(subjectMode = SubjectFocusPolicy.OBJECTS, objectPointSelected = true,
            objectFocusX = .2f, objectTapRevision = Long.MAX_VALUE)
        val person = source.preparedForAutomaticBlur(SubjectFocusPolicy.PEOPLE, AutomaticBlurPolicy.NATURAL)
        val objectAgain = person.preparedForAutomaticBlur(SubjectFocusPolicy.OBJECTS, AutomaticBlurPolicy.NATURAL)
        assertFalse(person.objectPointSelected)
        assertFalse(objectAgain.objectPointSelected)
        assertEquals(0L, person.objectTapRevision)
        assertEquals(1L, objectAgain.objectTapRevision)
    }

    @Test fun invalidObjectCoordinatesStayUnselectedAndCannotEnterShaders() {
        val source = CaptureSettings(subjectMode = SubjectFocusPolicy.OBJECTS, objectPointSelected = true,
            objectFocusX = Float.NaN, objectFocusY = 3f)
        val result = source.preparedForAutomaticBlur(SubjectFocusPolicy.OBJECTS, AutomaticBlurPolicy.NATURAL)
        assertFalse(result.objectPointSelected)
        assertTrue(result.objectFocusX.isFinite() && result.objectFocusY in 0f..1f)
    }

    @Test fun safeAutomaticExposureKeepsCreativeProfileFocusAndWhiteBalance() {
        val result = configured.preparedForSafeAutomaticExposure(-6, 6, 1f / 3f)
        assertFalse(result.isoEnabled || result.shutterEnabled || result.aeLockEnabled || result.gainEnabled)
        assertTrue(result.evEnabled)
        assertEquals(-1, result.ev)
        assertEquals(0f, result.gainStops, 0f)
        assertTrue(result.logEnabled)
        assertEquals(configured.portraitEnabled, result.portraitEnabled)
        assertEquals(configured.logStrength, result.logStrength, 0f)
        assertEquals(configured.logPreviewAssist, result.logPreviewAssist)
        assertEquals(configured.focusEnabled, result.focusEnabled)
        assertEquals(configured.focusLockEnabled, result.focusLockEnabled)
        assertEquals(configured.focusDiopters, result.focusDiopters, 0f)
        assertEquals(configured.whiteBalanceEnabled, result.whiteBalanceEnabled)
        assertEquals(configured.whiteBalance, result.whiteBalance)
        assertEquals(configured.awbLockEnabled, result.awbLockEnabled)
        assertEquals(configured.contrast, result.contrast, 0f)
        assertEquals(configured.saturation, result.saturation, 0f)
        assertEquals(configured.stabilizationCrop, result.stabilizationCrop, 0f)
        assertEquals(configured.videoBitrateScale, result.videoBitrateScale, 0f)
        assertEquals(configured.blurEnabled, result.blurEnabled)
        assertEquals(configured.cinematicEnabled, result.cinematicEnabled)
    }

    @Test fun unsupportedCompensationReturnsTrueAutoWithoutKeepingOldPositiveEv() {
        val result = configured.preparedForSafeAutomaticExposure(0, 0, 0f)
        assertFalse(result.evEnabled)
        assertEquals(0, result.ev)
        assertFalse(result.isoEnabled || result.shutterEnabled || result.aeLockEnabled)
    }

    @Test fun preparationIsOneTimeAndDoesNotOverrideLaterManualEdits() {
        val automatic = configured.preparedForSafeAutomaticExposure(-6, 6, 1f / 3f)
            .preparedForAutomaticBlur(SubjectFocusPolicy.PEOPLE, AutomaticBlurPolicy.BALANCED)
        val manual = automatic.copy(isoEnabled = true, iso = 1600, portraitStrength = .12f,
            subjectTrackingEnabled = false, blurEnabled = true)
        assertTrue(manual.isoEnabled && manual.blurEnabled)
        assertFalse(manual.subjectTrackingEnabled)
        assertEquals(1600, manual.iso)
        assertEquals(.12f, manual.portraitStrength, 0f)
    }

    @Test fun freshColorAndBlurDefaultsAreModerateAndNeutral() {
        val initial = CaptureSettings()
        assertEquals(0f, initial.gainStops, 0f)
        assertEquals(0f, initial.temperature, 0f)
        assertEquals(1f, initial.contrast, 0f)
        assertEquals(1f, initial.saturation, 0f)
        assertTrue(initial.portraitStrength <= .25f)
        assertFalse(initial.gainEnabled || initial.contrastEnabled || initial.saturationEnabled || initial.portraitEnabled)
    }
}
