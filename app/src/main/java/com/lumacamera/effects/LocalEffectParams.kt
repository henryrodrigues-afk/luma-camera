package com.lumacamera.effects

import com.lumacamera.camera.CaptureSettings

/** Neutral uniforms for disabled controls, independent of Camera2 capabilities. */
data class LocalEffectParams(
    val gain: Float, val temperature: Float, val contrast: Float, val saturation: Float,
    val sharpness: Float, val flat: Float, val blur: Boolean, val blurAmount: Float,
    val centerX: Float, val centerY: Float, val radius: Float, val trail: Float,
    val logStrength: Float = 0f, val logProfileVersion: Int = SimulatedLog.CURRENT_VERSION
) {
    companion object {
        fun from(s: CaptureSettings) = LocalEffectParams(
            if (s.gainEnabled) s.gainStops.coerceIn(-3f, 3f) else 0f,
            if (s.temperatureEnabled) s.temperature.coerceIn(-1f, 1f) else 0f,
            if (s.contrastEnabled) s.contrast.coerceIn(0f, 2f) else 1f,
            if (s.saturationEnabled) s.saturation.coerceIn(0f, 2f) else 1f,
            if (s.sharpnessEnabled) s.sharpness.coerceIn(0f, 1f) else 0f,
            if (s.flatEnabled) 1f else 0f,
            s.blurEnabled, s.blurAmount.coerceIn(0f, 1f),
            s.blurCenterX.coerceIn(0f, 1f), s.blurCenterY.coerceIn(0f, 1f),
            s.blurRadius.coerceIn(.1f, .7f),
            if (s.trailEnabled) s.trailAmount.coerceIn(0f, .9f) else 0f,
            if (s.logEnabled) s.logStrength.coerceIn(0f, 1f) else 0f,
            s.logProfileVersion.coerceIn(SimulatedLog.LEGACY_VERSION, SimulatedLog.CURRENT_VERSION)
        )
    }
}
