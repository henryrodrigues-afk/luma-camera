package com.lumacamera.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/** Preview-only geometry, in upright display pixels. None of these guides crops the recording. */
object CompositionPolicy {
    data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
        val centerX: Float get() = left + width * .5f
        val centerY: Float get() = top + height * .5f
    }

    fun normalizeGridMode(mode: Int): Int = if (mode in 0..2) mode else 0
    fun normalizeFrameGuide(mode: Int): Int = if (mode in 0..4) mode else 0

    /** A defensive copy; generated only when overlay geometry changes. */
    fun gridFractions(mode: Int): FloatArray = when (normalizeGridMode(mode)) {
        1 -> floatArrayOf(.5f)
        2 -> floatArrayOf(.38196602f, .61803395f)
        else -> floatArrayOf(1f / 3f, 2f / 3f)
    }

    fun guideBounds(width: Float, height: Float, mode: Int): Bounds {
        val w = dimension(width); val h = dimension(height)
        if (w == 0f || h == 0f) return Bounds(0f, 0f, w, h)
        val ratio = when (normalizeFrameGuide(mode)) {
            1 -> 1f
            2 -> 9f / 16f
            3 -> 16f / 9f
            4 -> 2.39f
            else -> return Bounds(0f, 0f, w, h)
        }
        val guideWidth: Float; val guideHeight: Float
        if (w / h > ratio) { guideHeight = h; guideWidth = h * ratio }
        else { guideWidth = w; guideHeight = w / ratio }
        val left = (w - guideWidth) * .5f; val top = (h - guideHeight) * .5f
        return Bounds(left, top, left + guideWidth, top + guideHeight)
    }

    /** 90% of the active guide; independently visible even when the grid/guide are off. */
    fun safeBounds(bounds: Bounds, fraction: Float = .9f): Bounds {
        val keep = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else .9f
        if (!valid(bounds)) return Bounds(0f, 0f, 0f, 0f)
        val insetX = bounds.width * (1f - keep) * .5f
        val insetY = bounds.height * (1f - keep) * .5f
        return Bounds(bounds.left + insetX, bounds.top + insetY, bounds.right - insetX, bounds.bottom - insetY)
    }

    /** Horizontal lines repeat every 180 degrees; invalid input remains unavailable, never level. */
    fun normalizeRoll(degrees: Float): Float {
        if (!degrees.isFinite()) return Float.NaN
        return (((degrees % 180f) + 270f) % 180f) - 90f
    }

    fun isLevel(degrees: Float): Boolean = degrees.isFinite() && abs(normalizeRoll(degrees)) <= 1.5f

    /**
     * Project world-up onto the display. For SensorManager's 9-element device→world matrix,
     * pass R[6], R[7]; for a 16-element matrix use R[8], R[9]. No Euler roll/gimbal assumptions.
     * Display rotation is 0/90/180/270 degrees, with Android's AXIS_Y/AXIS_MINUS_X at 90.
     * Near a camera aimed at the floor/sky, no reliable horizon exists and this returns null.
     */
    fun horizonRoll(gravityX: Float, gravityY: Float, displayRotationDegrees: Int = 0): Float? {
        if (!gravityX.isFinite() || !gravityY.isFinite()) return null
        val magnitude = sqrt(gravityX.toDouble() * gravityX + gravityY.toDouble() * gravityY)
        if (magnitude < .15 || magnitude > 1.05) return null
        val turn = ((displayRotationDegrees % 360) + 360) % 360
        val x: Float; val y: Float
        when (turn) {
            0 -> { x = gravityX; y = gravityY }
            90 -> { x = gravityY; y = -gravityX }
            180 -> { x = -gravityX; y = -gravityY }
            270 -> { x = -gravityY; y = gravityX }
            else -> return null
        }
        return normalizeRoll(Math.toDegrees(atan2(x.toDouble(), y.toDouble())).toFloat())
    }

    private fun dimension(value: Float) = if (value.isFinite() && value > 0f) value else 0f
    private fun valid(bounds: Bounds) = bounds.left.isFinite() && bounds.top.isFinite() &&
        bounds.right.isFinite() && bounds.bottom.isFinite() && bounds.left >= 0f && bounds.top >= 0f &&
        bounds.right >= bounds.left && bounds.bottom >= bounds.top
}
