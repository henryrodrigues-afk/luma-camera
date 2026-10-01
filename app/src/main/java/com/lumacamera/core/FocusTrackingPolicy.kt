package com.lumacamera.core

import kotlin.math.exp

/** Source GLES UV in/out. Lens mode/lock/availability remain the caller's responsibility. */
class FocusTrackingPolicy {
    private var filtered: Pair<Float, Float>? = null
    private var lastInput: Pair<Float, Float>? = null
    private var lastInputAt: Long? = null
    private var dispatched: Pair<Float, Float>? = null
    private var lastDispatchAt: Long? = null

    fun reset() {
        clearObservation(); dispatched = null; lastDispatchAt = null
    }

    private fun clearObservation() { filtered = null; lastInput = null; lastInputAt = null }

    /** Null/invalid input stops following. A jump is rejected without adopting a different target. */
    fun update(point: Pair<Float, Float>?, nowMs: Long): Pair<Float, Float>? {
        if (point == null || !valid(point) || nowMs < 0L) { clearObservation(); return null }
        val previousTime = lastInputAt
        if (previousTime != null && nowMs <= previousTime) return null
        if (previousTime != null && nowMs - previousTime > MAX_GAP_MS) {
            reset()
            // Establish a fresh observation; require another timely frame before moving the lens.
            filtered = point; lastInput = point; lastInputAt = nowMs
            return null
        }
        if (lastInput?.let { distanceSquared(it, point) > MAX_JUMP * MAX_JUMP } == true) {
            lastInputAt = nowMs // Repeated jumps are not a missing-frame gap that permits a new target.
            return null
        }
        val previous = filtered
        val alpha = if (previousTime == null) 1f else
            (1.0 - exp(-(nowMs - previousTime) / SMOOTHING_MS)).toFloat()
        val next = if (previous == null) point else
            (previous.first + (point.first - previous.first) * alpha) to
                (previous.second + (point.second - previous.second) * alpha)
        filtered = next; lastInput = point; lastInputAt = nowMs
        val sent = dispatched
        if (lastDispatchAt?.let { nowMs - it < MIN_INTERVAL_MS } == true) return null
        if (sent != null && distanceSquared(sent, next) < MIN_MOVEMENT * MIN_MOVEMENT) return null
        dispatched = next; lastDispatchAt = nowMs
        return next
    }

    private fun valid(point: Pair<Float, Float>) = point.first.isFinite() && point.second.isFinite() &&
        point.first in 0f..1f && point.second in 0f..1f
    private fun distanceSquared(a: Pair<Float, Float>, b: Pair<Float, Float>): Float {
        val x = a.first - b.first; val y = a.second - b.second
        return x * x + y * y
    }

    companion object {
        const val MIN_INTERVAL_MS = 1_000L
        const val MAX_GAP_MS = 2_000L
        const val MIN_MOVEMENT = .035f
        const val MAX_JUMP = .30f
        private const val SMOOTHING_MS = 250.0
    }
}
