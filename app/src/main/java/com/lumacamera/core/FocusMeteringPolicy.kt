package com.lumacamera.core

import kotlin.math.roundToInt

/** Sensor-coordinate rectangle; kept free of Android types so crop mapping is testable. */
data class FocusBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    init { require(right > left && bottom > top) { "A área de foco precisa ter largura e altura positivas" } }
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

object FocusMeteringPolicy {
    /** Input is unrotated preview coordinates, top-left origin. Invalid values fall back to center. */
    fun normalizedPoint(x: Float, y: Float): Pair<Float, Float> = normalize(x) to normalize(y)

    private fun normalize(value: Float): Float = if (value.isFinite()) value.coerceIn(0f, 1f) else .5f

    /** Camera2 adds a centered crop when the video aspect ratio differs from the sensor crop. */
    fun visibleBounds(sensor: FocusBounds, streamWidth: Int, streamHeight: Int, crop: FocusBounds? = null): FocusBounds {
        require(streamWidth > 0 && streamHeight > 0) { "Dimensões de vídeo inválidas" }
        val intersection = crop?.let {
            val left = maxOf(sensor.left, it.left); val top = maxOf(sensor.top, it.top)
            val right = minOf(sensor.right, it.right); val bottom = minOf(sensor.bottom, it.bottom)
            if (right > left && bottom > top) FocusBounds(left, top, right, bottom) else sensor
        } ?: sensor
        val ratio = streamWidth.toDouble() / streamHeight
        val sensorRatio = intersection.width.toDouble() / intersection.height
        return if (sensorRatio > ratio) {
            val width = (intersection.height * ratio).roundToInt().coerceIn(1, intersection.width)
            val left = intersection.left + (intersection.width - width) / 2
            FocusBounds(left, intersection.top, left + width, intersection.bottom)
        } else {
            val height = (intersection.width / ratio).roundToInt().coerceIn(1, intersection.height)
            val top = intersection.top + (intersection.height - height) / 2
            FocusBounds(intersection.left, top, intersection.right, top + height)
        }
    }

    /** Clamp and shift a small target to keep the entire metering rectangle inside the visible crop. */
    fun region(visible: FocusBounds, x: Float, y: Float, fraction: Float = .12f): FocusBounds {
        val point = normalizedPoint(x, y)
        val extent = if (fraction.isFinite()) fraction.coerceIn(.01f, 1f) else .12f
        val width = (visible.width * extent).roundToInt().coerceIn(1, visible.width)
        val height = (visible.height * extent).roundToInt().coerceIn(1, visible.height)
        val centerX = visible.left + (point.first * (visible.width - 1)).roundToInt()
        val centerY = visible.top + (point.second * (visible.height - 1)).roundToInt()
        val left = (centerX - width / 2).coerceIn(visible.left, visible.right - width)
        val top = (centerY - height / 2).coerceIn(visible.top, visible.bottom - height)
        return FocusBounds(left, top, left + width, top + height)
    }
}
