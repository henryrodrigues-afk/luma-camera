package com.lumacamera.effects

import com.lumacamera.camera.CaptureSettings
import org.junit.Assert.*
import org.junit.Test

class LocalEffectParamsTest {
    @Test fun inactiveControlsAreNeutralEvenWithStoredCreativeValues() {
        val p = LocalEffectParams.from(CaptureSettings(gainStops = 3f, temperature = 1f,
            contrast = 0f, saturation = 0f, sharpness = 1f, trailAmount = .9f))
        assertEquals(0f, p.gain, 0f); assertEquals(0f, p.temperature, 0f)
        assertEquals(1f, p.contrast, 0f); assertEquals(1f, p.saturation, 0f)
        assertEquals(0f, p.sharpness, 0f); assertEquals(0f, p.flat, 0f)
        assertFalse(p.blur); assertEquals(0f, p.trail, 0f)
    }
    @Test fun changingOneControlLeavesOtherEnabledControlsIntact() {
        val original = CaptureSettings(gainEnabled = true, gainStops = 1f,
            temperatureEnabled = true, temperature = -.5f, blurEnabled = true)
        val next = LocalEffectParams.from(original.copy(gainEnabled = false))
        assertEquals(0f, next.gain, 0f)
        assertEquals(-.5f, next.temperature, 0f); assertTrue(next.blur)
    }
    @Test fun rejectedHardwareEditKeepsSoftwareAndOtherPreviousHardwareSettings() {
        val previous = CaptureSettings(isoEnabled = true, iso = 400, focusEnabled = true, focusDiopters = 2f)
        val edit = previous.copy(shutterEnabled = true, exposureNs = 1L, blurEnabled = true, flatEnabled = true)
        val restored = edit.withHardwareFrom(previous)
        assertTrue(restored.isoEnabled); assertEquals(400, restored.iso)
        assertTrue(restored.focusEnabled); assertFalse(restored.shutterEnabled)
        assertTrue(restored.blurEnabled); assertTrue(restored.flatEnabled)
    }
    @Test fun shaderParametersStayInDocumentedRange() {
        val p = LocalEffectParams.from(CaptureSettings(gainEnabled = true, gainStops = 90f,
            temperatureEnabled = true, temperature = -5f, blurEnabled = true, blurRadius = 0f,
            contrastEnabled = true, contrast = -1f, saturationEnabled = true, saturation = 9f,
            trailEnabled = true, trailAmount = 1f))
        assertEquals(3f, p.gain, 0f); assertEquals(-1f, p.temperature, 0f)
        assertEquals(.1f, p.radius, 0f); assertEquals(0f, p.contrast, 0f)
        assertEquals(2f, p.saturation, 0f); assertEquals(.9f, p.trail, 0f)
    }
}
