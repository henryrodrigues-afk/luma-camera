package com.lumacamera.core

import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

data class ExposureSolution(val iso: Int, val exposureNs: Long)

/**
 * Camera2 disables AE for both ISO and shutter together. This controller keeps the
 * enabled manual parameter fixed and meters the other one using frame luminance.
 * Call with the latest measured/current exposure, not an initial slider default.
 * With both controls disabled the camera's own AE owns exposure; with both enabled
 * no luminance correction is applied. An enabled control means manual control.
 */
object ExposureAutomation {
    fun next(
        isoEnabled: Boolean,
        shutterEnabled: Boolean,
        iso: Int,
        exposureNs: Long,
        luminance: Float,
        targetLuminance: Float,
        minIso: Int,
        maxIso: Int,
        minExposureNs: Long,
        maxExposureNs: Long,
        frameDurationNs: Long,
    ): ExposureSolution {
        require(minIso > 0 && maxIso >= minIso) { "Invalid sensor ISO bounds" }
        require(minExposureNs > 0 && maxExposureNs >= minExposureNs) {
            "Invalid sensor exposure bounds"
        }
        require(frameDurationNs > 0) { "Frame duration must be positive" }
        val exposureCeiling = minOf(maxExposureNs, frameDurationNs)
        require(minExposureNs <= exposureCeiling) {
            "Sensor minimum exposure exceeds the frame budget"
        }

        val currentIso = iso.coerceIn(minIso, maxIso)
        val currentExposure = exposureNs.coerceIn(minExposureNs, exposureCeiling)
        if (isoEnabled == shutterEnabled) {
            return ExposureSolution(currentIso, currentExposure)
        }

        // Invalid meter samples hold exposure. Finite out-of-range values are
        // normalized; a zero sample still produces a bounded, gradual increase.
        val target = if (targetLuminance.isFinite()) {
            targetLuminance.toDouble().coerceIn(0.001, 1.0)
        } else 0.45
        val measured = if (luminance.isFinite()) {
            luminance.toDouble().coerceIn(0.001, 1.0)
        } else target
        val gain = (target / measured).pow(0.35).coerceIn(0.7, 1.4)

        return if (isoEnabled) {
            ExposureSolution(
                currentIso,
                (currentExposure.toDouble() * gain).roundToLong()
                    .coerceIn(minExposureNs, exposureCeiling),
            )
        } else {
            ExposureSolution(
                (currentIso.toDouble() * gain).roundToInt().coerceIn(minIso, maxIso),
                currentExposure,
            )
        }
    }
}
