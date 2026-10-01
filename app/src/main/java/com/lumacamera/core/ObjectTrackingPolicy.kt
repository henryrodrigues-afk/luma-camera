package com.lumacamera.core

import com.lumacamera.effects.PortraitMaskPolicy
import kotlin.math.abs
import kotlin.math.roundToInt

/** Local visual correspondence, not object recognition. Coordinates and pixels use source GLES UV. */
class ObjectTrackingPolicy {
    data class Frame(val luma: FloatArray, val width: Int, val height: Int, val capturedAtMs: Long)
    data class Prediction(val point: ObjectMaskPolicy.Point?, val confidence: Float, val reason: String)
    private var previous: Frame? = null
    private var point: ObjectMaskPolicy.Point? = null
    val initialized: Boolean get() = previous != null && point != null

    fun reset() { previous = null; point = null }

    /** No state is adopted until the new AI mask confirms the predicted point's selected component. */
    fun predict(frame: Frame): Prediction {
        val old = previous ?: return rejected("uninitialized")
        val seed = point ?: return rejected("uninitialized")
        if (!valid(frame) || !valid(old) || frame.width != old.width || frame.height != old.height)
            return rejected("invalid_frame")
        if (frame.capturedAtMs <= old.capturedAtMs || frame.capturedAtMs - old.capturedAtMs > MAX_GAP_MS)
            return rejected("frame_gap")
        val cx = (seed.x * old.width - .5f).roundToInt()
        val cy = (seed.y * old.height - .5f).roundToInt()
        if (!interior(cx, cy, old.width, old.height)) return rejected("edge")
        val oldMean = mean(old, cx, cy)
        val contrast = contrast(old, cx, cy, oldMean)
        if (contrast < MIN_CONTRAST) return rejected("low_texture")
        val radius = (maxOf(old.width, old.height) * .12f).toInt().coerceIn(3, 12)
        val costs = FloatArray((radius * 2 + 1) * (radius * 2 + 1)) { Float.POSITIVE_INFINITY }
        val span = radius * 2 + 1
        var best = Float.POSITIVE_INFINITY
        var bestDx = 0; var bestDy = 0
        for (dy in -radius..radius) for (dx in -radius..radius) {
            val nx = cx + dx; val ny = cy + dy
            if (!interior(nx, ny, frame.width, frame.height)) continue
            val cost = cost(old, frame, cx, cy, nx, ny, oldMean)
            costs[(dy + radius) * span + dx + radius] = cost
            if (cost < best) { best = cost; bestDx = dx; bestDy = dy }
        }
        if (!best.isFinite() || best > minOf(.10f, contrast * .6f)) return rejected("appearance_changed")
        if (abs(bestDx) == radius || abs(bestDy) == radius) return rejected("movement_unbounded")
        var alternative = Float.POSITIVE_INFINITY
        for (dy in -radius..radius) for (dx in -radius..radius) {
            if (abs(dx - bestDx) > 1 || abs(dy - bestDy) > 1)
                alternative = minOf(alternative, costs[(dy + radius) * span + dx + radius])
        }
        val uniqueness = ((alternative - best) / (alternative + .01f)).coerceIn(0f, 1f)
        if (!uniqueness.isFinite() || uniqueness < .18f) return rejected("ambiguous")
        val confidence = (uniqueness * .6f + (1f - best / .10f) * .4f).coerceIn(0f, 1f)
        if (confidence < .5f) return rejected("low_confidence")
        val predicted = ObjectMaskPolicy.Point((cx + bestDx + .5f) / frame.width,
            (cy + bestDy + .5f) / frame.height)
        return Prediction(predicted, confidence, "tracked")
    }

    /** Reference patch remains inside the selected foreground; background pixels never become a seed. */
    fun confirm(frame: Frame, preferred: ObjectMaskPolicy.Point,
        foreground: PortraitMaskPolicy.Mask): ObjectMaskPolicy.Point? {
        if (!valid(frame) || !ObjectMaskPolicy.hasObject(foreground) || !validPoint(preferred)) return null
        fun usable(x: Int, y: Int): Boolean {
            if (!interior(x, y, frame.width, frame.height) ||
                PortraitMaskPolicy.confidenceAt(foreground, (x + .5f) / frame.width, (y + .5f) / frame.height) < .7f) return false
            // A textured background around a smooth object's edge must not become its visual
            // reference. Validate the complete patch used by mean/contrast/correspondence.
            for (dy in -PATCH_RADIUS..PATCH_RADIUS) for (dx in -PATCH_RADIUS..PATCH_RADIUS)
                if (PortraitMaskPolicy.confidenceAt(foreground,
                        (x + dx + .5f) / frame.width, (y + dy + .5f) / frame.height) < .6f) return false
            return true
        }
        var chosenX = -1; var chosenY = -1
        var best = Float.NEGATIVE_INFINITY
        // Keep the visually predicted interior patch whenever possible, rather than drifting to the centroid.
        val px = (preferred.x * frame.width - .5f).roundToInt()
        val py = (preferred.y * frame.height - .5f).roundToInt()
        if (usable(px, py) && contrast(frame, px, py, mean(frame, px, py)) >= MIN_CONTRAST) {
            chosenX = px; chosenY = py
        } else {
            // A tap/centroid can lie in a flat part. Pick a textured patch of this same connected mask.
            for (y in PATCH_RADIUS until frame.height - PATCH_RADIUS) {
                for (x in PATCH_RADIUS until frame.width - PATCH_RADIUS) {
                    if (!usable(x, y)) continue
                    val texture = contrast(frame, x, y, mean(frame, x, y))
                    if (texture < MIN_CONTRAST) continue
                    val dx = (x + .5f) / frame.width - preferred.x
                    val dy = (y + .5f) / frame.height - preferred.y
                    val score = texture - (dx * dx + dy * dy) * .05f
                    if (score > best) { best = score; chosenX = x; chosenY = y }
                }
            }
        }
        if (chosenX < 0) return null
        val next = ObjectMaskPolicy.Point((chosenX + .5f) / frame.width, (chosenY + .5f) / frame.height)
        previous = frame.copy(luma = frame.luma.copyOf())
        point = next
        return next
    }

    private fun mean(frame: Frame, x: Int, y: Int): Float {
        var sum = 0f
        for (dy in -PATCH_RADIUS..PATCH_RADIUS) for (dx in -PATCH_RADIUS..PATCH_RADIUS)
            sum += frame.luma[(y + dy) * frame.width + x + dx]
        return sum / PATCH_PIXELS
    }
    private fun contrast(frame: Frame, x: Int, y: Int, mean: Float): Float {
        var sum = 0f
        for (dy in -PATCH_RADIUS..PATCH_RADIUS) for (dx in -PATCH_RADIUS..PATCH_RADIUS)
            sum += abs(frame.luma[(y + dy) * frame.width + x + dx] - mean)
        return sum / PATCH_PIXELS
    }
    private fun cost(old: Frame, current: Frame, x: Int, y: Int, nx: Int, ny: Int, oldMean: Float): Float {
        val nextMean = mean(current, nx, ny)
        var sum = 0f
        for (dy in -PATCH_RADIUS..PATCH_RADIUS) for (dx in -PATCH_RADIUS..PATCH_RADIUS)
            sum += abs((old.luma[(y + dy) * old.width + x + dx] - oldMean) -
                (current.luma[(ny + dy) * current.width + nx + dx] - nextMean))
        return sum / PATCH_PIXELS
    }
    private fun valid(frame: Frame) = frame.width in 9..128 && frame.height in 9..128 &&
        frame.capturedAtMs >= 0L && frame.width.toLong() * frame.height == frame.luma.size.toLong() &&
        frame.luma.all { it.isFinite() && it in 0f..1f }
    private fun validPoint(point: ObjectMaskPolicy.Point) = point.x.isFinite() && point.y.isFinite() &&
        point.x in 0f..1f && point.y in 0f..1f
    private fun interior(x: Int, y: Int, width: Int, height: Int) = x >= PATCH_RADIUS && y >= PATCH_RADIUS &&
        x < width - PATCH_RADIUS && y < height - PATCH_RADIUS
    private fun rejected(reason: String) = Prediction(null, 0f, reason)

    companion object {
        const val MAX_GAP_MS = 2_500L
        private const val PATCH_RADIUS = 3
        private const val PATCH_PIXELS = 49f
        private const val MIN_CONTRAST = .025f

        /** At most 96 px on the long edge, derived directly from the copied source GLES readback. */
        fun frame(rgba: ByteArray, width: Int, height: Int, capturedAtMs: Long): Frame {
            require(width > 0 && height > 0 && width.toLong() * height * 4L <= rgba.size.toLong())
            val scale = minOf(1f, 96f / maxOf(width, height))
            val w = (width * scale).roundToInt().coerceAtLeast(1)
            val h = (height * scale).roundToInt().coerceAtLeast(1)
            val luma = FloatArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                val sx = ((x + .5f) * width / w).toInt().coerceIn(0, width - 1)
                val sy = ((y + .5f) * height / h).toInt().coerceIn(0, height - 1)
                val offset = (sy * width + sx) * 4
                luma[y * w + x] = ((rgba[offset].toInt() and 255) * .2126f +
                    (rgba[offset + 1].toInt() and 255) * .7152f + (rgba[offset + 2].toInt() and 255) * .0722f) / 255f
            }
            return Frame(luma, w, h, capturedAtMs)
        }
    }
}
