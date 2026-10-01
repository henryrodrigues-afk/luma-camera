package com.lumacamera.effects

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** Geometry and safety rules shared by inference and GLES. No Android dependencies. */
object PortraitMaskPolicy {
    data class Mask(val width: Int, val height: Int, val confidence: FloatArray, val capturedAtMs: Long)
    data class UprightPixels(val width: Int, val height: Int, val argb: IntArray)

    /** Bitmap pixels use a top-left origin. A positive rotation is clockwise. */
    fun rotateTopLeftArgb(source: IntArray, width: Int, height: Int, rotation: Int): UprightPixels {
        require(width > 0 && height > 0 && source.size == width * height)
        val turn = normalizedRotation(rotation)
        val rotatedWidth = if (turn == 90 || turn == 270) height else width
        val rotatedHeight = if (turn == 90 || turn == 270) width else height
        val output = IntArray(source.size)
        for (y in 0 until height) for (x in 0 until width) {
            val destination = when (turn) {
                90 -> x * rotatedWidth + height - 1 - y
                180 -> (height - 1 - y) * rotatedWidth + width - 1 - x
                270 -> (width - 1 - x) * rotatedWidth + y
                else -> y * rotatedWidth + x
            }
            output[destination] = source[y * width + x]
        }
        return UprightPixels(rotatedWidth, rotatedHeight, output)
    }

    /** Undo inference rotation and convert top-left mask rows to the source GLES bottom-left origin. */
    fun alignToSource(
        upright: FloatArray, maskWidth: Int, maskHeight: Int,
        sourceWidth: Int, sourceHeight: Int, rotation: Int, capturedAtMs: Long
    ): Mask {
        require(maskWidth > 0 && maskHeight > 0 && upright.size == maskWidth * maskHeight)
        require(sourceWidth > 0 && sourceHeight > 0)
        val turn = normalizedRotation(rotation)
        val aligned = FloatArray(sourceWidth * sourceHeight)
        for (y in 0 until sourceHeight) for (x in 0 until sourceWidth) {
            val sourceX = (x + .5f) / sourceWidth
            val sourceY = 1f - (y + .5f) / sourceHeight
            val u: Float
            val v: Float
            when (turn) {
                90 -> { u = 1f - sourceY; v = sourceX }
                180 -> { u = 1f - sourceX; v = 1f - sourceY }
                270 -> { u = sourceY; v = 1f - sourceX }
                else -> { u = sourceX; v = sourceY }
            }
            aligned[y * sourceWidth + x] = sample(upright, maskWidth, maskHeight, u, v)
        }
        return Mask(sourceWidth, sourceHeight, aligned, capturedAtMs)
    }

    /** Keep the native model grid; normalized texture coordinates handle display scaling once. */
    fun alignRawToSource(
        upright: FloatArray, maskWidth: Int, maskHeight: Int, rotation: Int, capturedAtMs: Long
    ): Mask {
        val turn = normalizedRotation(rotation)
        return alignToSource(upright, maskWidth, maskHeight,
            if (turn == 90 || turn == 270) maskHeight else maskWidth,
            if (turn == 90 || turn == 270) maskWidth else maskHeight,
            turn, capturedAtMs)
    }

    fun confidenceAt(mask: Mask, sourceGlX: Float, sourceGlY: Float): Float =
        sample(mask.confidence, mask.width, mask.height, sourceGlX, sourceGlY)

    /** Legacy stateless blend; segmenter callbacks already apply the adaptive temporal filter. */
    fun smooth(previous: Mask?, next: Mask): Mask {
        if (previous == null || previous.width != next.width || previous.height != next.height ||
            next.capturedAtMs - previous.capturedAtMs > 600L) return next
        val output = FloatArray(next.confidence.size) { index ->
            previous.confidence[index] * .15f + next.confidence[index] * .85f
        }
        return next.copy(confidence = output)
    }

    /** A delayed inference result must never freeze a stale person's silhouette indefinitely. */
    fun freshness(nowMs: Long, capturedAtMs: Long): Float {
        if (capturedAtMs < 0L || nowMs < capturedAtMs) return 0f
        val age = nowMs - capturedAtMs
        return when {
            age <= 250L -> 1f
            age >= 600L -> 0f
            else -> (600L - age) / 350f
        }
    }

    /** Enter on confident evidence; tolerate soft-confidence edges only while a real subject remains. */
    fun hasPerson(mask: Mask, wasPresent: Boolean = false): Boolean {
        if (mask.width <= 0 || mask.height <= 0 || mask.width.toLong() * mask.height != mask.confidence.size.toLong()) return false
        val threshold = if (wasPresent) .45f else .65f
        val coverage = if (wasPresent) .002 else .003
        return mask.confidence.count { it.isFinite() && it >= threshold } >=
            maxOf(1, ceil(mask.confidence.size * coverage).toInt())
    }

    /** Hysteresis stops a soft hair boundary from repeatedly changing cinematic focus. */
    fun targetBackground(confidence: Float, previous: Boolean): Boolean = when {
        confidence >= .6f -> false
        confidence <= .4f -> true
        else -> previous
    }

    fun advanceFocus(current: Float, target: Boolean, elapsedSeconds: Float, transitionSeconds: Float): Float {
        val value = current.coerceIn(0f, 1f)
        val destination = if (target) 1f else 0f
        val delta = elapsedSeconds.coerceAtLeast(0f) / transitionSeconds.coerceIn(.15f, 3f)
        return if (abs(value - destination) <= delta) destination
        else (value + if (destination > value) delta else -delta).coerceIn(0f, 1f)
    }

    private fun normalizedRotation(rotation: Int): Int {
        val result = ((rotation % 360) + 360) % 360
        require(result == 0 || result == 90 || result == 180 || result == 270)
        return result
    }

    private fun sample(values: FloatArray, width: Int, height: Int, u: Float, v: Float): Float {
        val x = ((if (u.isFinite()) u else .5f).coerceIn(0f, 1f) * width - .5f).coerceIn(0f, width - 1f)
        val y = ((if (v.isFinite()) v else .5f).coerceIn(0f, 1f) * height - .5f).coerceIn(0f, height - 1f)
        val left = floor(x).toInt(); val bottom = floor(y).toInt()
        val right = (left + 1).coerceAtMost(width - 1); val top = (bottom + 1).coerceAtMost(height - 1)
        val fx = x - left; val fy = y - bottom
        fun at(px: Int, py: Int) = values[py * width + px].let { if (it.isFinite()) it.coerceIn(0f, 1f) else 0f }
        val first = at(left, bottom) * (1f - fx) + at(right, bottom) * fx
        val second = at(left, top) * (1f - fx) + at(right, top) * fx
        return first * (1f - fy) + second * fy
    }
}
