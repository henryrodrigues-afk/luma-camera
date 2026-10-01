package com.lumacamera.core

import com.lumacamera.camera.CaptureSettings
import org.junit.Assert.*
import org.junit.Test

class EditingPresetTest {
    @Test fun cleanEditingSetupPreservesLensExposureAndPortraitChoices() {
        val original = CaptureSettings(isoEnabled = true, shutterEnabled = true, focusEnabled = true,
            iso = 640, exposureNs = 10_000_000L, focusDiopters = 2f, evEnabled = true, ev = -2,
            portraitEnabled = true, cinematicEnabled = true, portraitStrength = .7f, blurEnabled = true,
            flatEnabled = true, gainEnabled = true, temperatureEnabled = true, saturationEnabled = true,
            contrastEnabled = true, sharpnessEnabled = true, trailEnabled = true, logProfileVersion = 1,
            logStrength = .4f, logPreviewAssist = true)
        val ready = original.preparedForEditing()
        assertEquals(original.iso, ready.iso)
        assertEquals(original.exposureNs, ready.exposureNs)
        assertEquals(original.focusDiopters, ready.focusDiopters, 0f)
        assertTrue(ready.isoEnabled && ready.shutterEnabled && ready.focusEnabled)
        assertTrue(ready.portraitEnabled && ready.cinematicEnabled && ready.blurEnabled)
        assertEquals(original.portraitStrength, ready.portraitStrength, 0f)
        assertEquals(original.ev, ready.ev)
        assertTrue(ready.logEnabled)
        assertEquals(2, ready.logProfileVersion)
        assertEquals(1f, ready.logStrength, 0f)
        assertFalse(ready.logPreviewAssist)
        assertFalse(ready.flatEnabled || ready.gainEnabled || ready.temperatureEnabled || ready.saturationEnabled ||
            ready.contrastEnabled || ready.sharpnessEnabled || ready.trailEnabled)
    }

    @Test fun settingsCanStillBeEditedIndependentlyAfterApplyingThePreset() {
        val edited = CaptureSettings().preparedForEditing().copy(saturationEnabled = true, saturation = 1.3f)
        assertTrue(edited.logEnabled)
        assertTrue(edited.saturationEnabled)
        assertEquals(1.3f, edited.saturation, 0f)
        assertFalse(edited.contrastEnabled)
    }
}
