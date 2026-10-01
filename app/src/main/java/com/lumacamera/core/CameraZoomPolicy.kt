package com.lumacamera.core

import kotlin.math.roundToInt

object CameraZoomPolicy {
    fun ratio(requested: Float, maximum: Float): Float {
        val upper = if (maximum.isFinite()) maximum.coerceIn(1f, 100f) else 1f
        return if (requested.isFinite()) requested.coerceIn(1f, upper) else 1f
    }

    /** Centered digital crop in the Camera2 active array, including nonzero sensor origins. */
    fun crop(sensor: FocusBounds, requested: Float, maximum: Float): FocusBounds {
        val zoom = ratio(requested, maximum)
        val width = (sensor.width / zoom).roundToInt().coerceIn(1, sensor.width)
        val height = (sensor.height / zoom).roundToInt().coerceIn(1, sensor.height)
        val left = sensor.left + (sensor.width - width) / 2
        val top = sensor.top + (sensor.height - height) / 2
        return FocusBounds(left, top, left + width, top + height)
    }

    /** Lens AF receives a stable target at most once per second, never every segmentation frame. */
    fun shouldTrack(nowMs: Long, lastMs: Long?, point: Pair<Float, Float>, previous: Pair<Float, Float>?): Boolean {
        if (!point.first.isFinite() || !point.second.isFinite()) return false
        if (lastMs != null && (nowMs < lastMs || nowMs - lastMs < 1_000L)) return false
        if (previous == null) return true
        val dx = point.first - previous.first; val dy = point.second - previous.second
        return dx * dx + dy * dy >= .035f * .035f
    }
}
