package com.lumacamera.core

import kotlin.math.roundToInt

/** A modest AE bias, not highlight recovery or a guarantee that the camera will avoid clipping. */
object ExposureSafetyPolicy {
    const val TARGET_EV = -1f / 3f
    private const val MAX_DARKENING_EV = .5f

    /** Returns device compensation steps. Unsupported, invalid or excessively coarse ranges stay neutral. */
    fun compensationSteps(minimum: Int, maximum: Int, step: Float): Int {
        if (minimum > maximum || minimum >= 0 || maximum < 0 || !step.isFinite() || step <= 0f) return 0
        val candidate = (TARGET_EV.toDouble() / step).roundToInt().coerceIn(minimum, maximum)
        val stops = candidate.toDouble() * step
        return if (candidate < 0 && stops >= -MAX_DARKENING_EV - 1e-6) candidate else 0
    }
}
