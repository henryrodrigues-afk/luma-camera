package com.lumacamera.effects

import com.lumacamera.core.FrameMotionEstimate
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Sparse pyramidal matching and rigid spatial consensus in GLES bottom-left coordinates.
 * Confined to one serial analysis executor; two reusable pyramids own the caller's copied luma.
 */
class FrameMotionEstimator {
    private class Frame {
        val pixels = Array(3) { FloatArray(0) }
        val widths = IntArray(3)
        val heights = IntArray(3)
        var time = 0L
        fun set(source: FloatArray, width: Int, height: Int, timestamp: Long) {
            widths[0] = width; heights[0] = height; time = timestamp
            if (pixels[0].size != source.size) pixels[0] = FloatArray(source.size)
            source.copyInto(pixels[0])
            for (level in 1..2) {
                val pw = widths[level - 1]; val ph = heights[level - 1]
                val w = pw / 2; val h = ph / 2
                widths[level] = w; heights[level] = h
                if (pixels[level].size != w * h) pixels[level] = FloatArray(w * h)
                val parent = pixels[level - 1]; val target = pixels[level]
                for (y in 0 until h) for (x in 0 until w) {
                    val i = y * 2 * pw + x * 2
                    target[y * w + x] = (parent[i] + parent[i + 1] + parent[i + pw] + parent[i + pw + 1]) * .25f
                }
            }
        }
    }
    private var reference = Frame()
    private var work = Frame()
    private var hasReference = false
    private val xs = FloatArray(35)
    private val ys = FloatArray(35)
    private val dxs = FloatArray(35)
    private val dys = FloatArray(35)
    private val qualities = FloatArray(35)
    private val inliers = BooleanArray(35)
    private val bestInliers = BooleanArray(35)
    private val costs = FloatArray(65 * 65)
    private val maskOffsets = intArrayOf(0, 0, -3, -3, 3, -3, -3, 3, 3, 3)

    fun reset() { hasReference = false }

    /** At most 35 patches, three scales, ±20%/32px search and 5.7° rotation per analysis pair.
     * rotationRadians is scene displacement, positive CCW in physical pixels (aspect width/height),
     * about image centre. deltaX/Y are that centre's translation normalized independently.
     */
    fun consume(luma: FloatArray, width: Int, height: Int, timestampMs: Long,
        foregroundMask: PortraitMaskPolicy.Mask? = null): FrameMotionEstimate {
        if (width < 20 || height < 20 || width > 256 || height > 256 || timestampMs < 0L ||
            width.toLong() * height != luma.size.toLong() || luma.any { !it.isFinite() || it !in 0f..1f }) {
            reset(); return rejected("invalid_frame", true)
        }
        work.set(luma, width, height, timestampMs)
        val old = reference; val current = work
        reference = current; work = old
        val hadReference = hasReference; hasReference = true
        var mean = 0f
        for (value in luma) mean += value
        if (mean / luma.size < .045f) return rejected("dark", true)
        if (!hadReference || old.widths[0] != width || old.heights[0] != height) return rejected("first_frame", true)
        if (timestampMs <= old.time || timestampMs - old.time > 500L) return rejected("frame_gap", true)
        val mask = foregroundMask?.takeIf { timestampMs >= it.capturedAtMs && timestampMs - it.capturedAtMs <= 600L }
        if (mask != null && (mask.width <= 0 || mask.height <= 0 ||
            mask.width.toLong() * mask.height != mask.confidence.size.toLong())) return rejected("invalid_mask")
        val columns = (width / 12).coerceIn(3, 7)
        val rows = (height / 12).coerceIn(3, 5)
        val radiusX = (width / 5).coerceIn(6, 32)
        val radiusY = (height / 5).coerceIn(6, 32)
        var textured = 0; var failedAppearance = 0; var count = 0
        for (row in 0 until rows) for (column in 0 until columns) {
            val cx = (8f + (width - 17) * column / (columns - 1f)).roundToInt()
            val cy = (8f + (height - 17) * row / (rows - 1f)).roundToInt()
            if (excludedForeground(mask, cx, cy, width, height)) continue
            val sourceMean = blockMean(old.pixels[0], width, cx, cy)
            val contrast = blockContrast(old.pixels[0], width, cx, cy, sourceMean)
            if (contrast < .025f) continue
            textured++
            var predictedX = 0; var predictedY = 0; var searched = false
            var finalBest = Float.POSITIVE_INFINITY; var finalAlternative = Float.POSITIVE_INFINITY
            var ambiguousCoarse = false
            for (level in 2 downTo 0) {
                val scale = 1 shl level
                val w = old.widths[level]; val h = old.heights[level]
                val x = ((cx + .5f) / scale - .5f).roundToInt()
                val y = ((cy + .5f) / scale - .5f).roundToInt()
                if (x < 3 || y < 3 || x + 3 >= w || y + 3 >= h) continue
                val oldMean = blockMean(old.pixels[level], w, x, y)
                if (level > 0 && blockContrast(old.pixels[level], w, x, y, oldMean) < .010f) continue
                val limitX = ceil(radiusX / scale.toDouble()).toInt()
                val limitY = ceil(radiusY / scale.toDouble()).toInt()
                val px = (predictedX / scale.toFloat()).roundToInt()
                val py = (predictedY / scale.toFloat()).roundToInt()
                val minX = if (searched) maxOf(-limitX, px - 3) else -limitX
                val maxX = if (searched) minOf(limitX, px + 3) else limitX
                val minY = if (searched) maxOf(-limitY, py - 3) else -limitY
                val maxY = if (searched) minOf(limitY, py + 3) else limitY
                val sw = maxX - minX + 1
                var best = Float.POSITIVE_INFINITY; var bx = 0; var by = 0
                for (dy in minY..maxY) for (dx in minX..maxX) {
                    val nx = x + dx; val ny = y + dy
                    val cost = if (nx < 3 || ny < 3 || nx + 3 >= w || ny + 3 >= h)
                        Float.POSITIVE_INFINITY else blockCost(old.pixels[level], current.pixels[level], w, x, y, nx, ny, oldMean)
                    costs[(dy - minY) * sw + dx - minX] = cost
                    if (cost < best) { best = cost; bx = dx; by = dy }
                }
                var alternative = Float.POSITIVE_INFINITY
                for (dy in minY..maxY) for (dx in minX..maxX)
                    if (abs(dx - bx) > 1 || abs(dy - by) > 1)
                        alternative = minOf(alternative, costs[(dy - minY) * sw + dx - minX])
                if (!searched && level > 0 && best < .0001f && alternative < .0001f) ambiguousCoarse = true
                predictedX = bx * scale; predictedY = by * scale; searched = true
                if (level == 0) { finalBest = best; finalAlternative = alternative }
            }
            // Half-pixel aliasing at a pyramid level can hide the real match of a sharp pattern.
            // Retry only a poor residual at the bounded original scale; textured cuts pay this ceiling
            // but cannot create consensus. This also prevents a coarse false match becoming "scene cut".
            if (!ambiguousCoarse && finalBest > minOf(.04f, contrast * .30f)) {
                val sw = radiusX * 2 + 1
                finalBest = Float.POSITIVE_INFINITY
                for (dy in -radiusY..radiusY) for (dx in -radiusX..radiusX) {
                    val nx = cx + dx; val ny = cy + dy
                    val cost = if (nx < 3 || ny < 3 || nx + 3 >= width || ny + 3 >= height)
                        Float.POSITIVE_INFINITY else blockCost(old.pixels[0], current.pixels[0], width,
                            cx, cy, nx, ny, sourceMean)
                    costs[(dy + radiusY) * sw + dx + radiusX] = cost
                    if (cost < finalBest) { finalBest = cost; predictedX = dx; predictedY = dy }
                }
                finalAlternative = Float.POSITIVE_INFINITY
                for (dy in -radiusY..radiusY) for (dx in -radiusX..radiusX)
                    if (abs(dx - predictedX) > 1 || abs(dy - predictedY) > 1)
                        finalAlternative = minOf(finalAlternative, costs[(dy + radiusY) * sw + dx + radiusX])
            }
            if (finalBest > contrast * .45f) failedAppearance++
            if (finalBest > minOf(.12f, contrast * .65f)) continue
            val uniqueness = ((finalAlternative - finalBest) / (finalAlternative + .01f)).coerceIn(0f, 1f)
            if (ambiguousCoarse || !uniqueness.isFinite() || uniqueness < .12f ||
                abs(predictedX) >= radiusX || abs(predictedY) >= radiusY) continue
            if (excludedForeground(mask, cx + predictedX, cy + predictedY, width, height)) continue
            val dx = predictedX + refinement(old.pixels[0], current.pixels[0], width, height,
                cx, cy, predictedX, predictedY, sourceMean, true, finalBest)
            val dy = predictedY + refinement(old.pixels[0], current.pixels[0], width, height,
                cx, cy, predictedX, predictedY, sourceMean, false, finalBest)
            xs[count] = cx + .5f - width * .5f; ys[count] = cy + .5f - height * .5f
            dxs[count] = dx; dys[count] = dy
            qualities[count] = (uniqueness * .6f + (1f - finalBest / .12f) * .4f).coerceIn(0f, 1f)
            count++
        }
        if (textured < maxOf(5, ceil(columns * rows * .4).toInt())) return rejected("low_texture")
        if (failedAppearance >= textured * .65f) return rejected("scene_cut", true)
        if (count < maxOf(5, ceil(textured * .55).toInt())) return rejected("low_consensus")
        var bestWeight = -1f
        fun candidate(tx: Float, ty: Float, angle: Float) {
            if (!angle.isFinite() || abs(angle) > MAX_ROTATION) return
            val c = cos(angle); val s = sin(angle)
            var weight = 0f
            for (i in 0 until count) {
                val ex = dxs[i] - (tx + (c - 1f) * xs[i] - s * ys[i])
                val ey = dys[i] - (ty + s * xs[i] + (c - 1f) * ys[i])
                inliers[i] = ex * ex + ey * ey <= RESIDUAL_SQUARED
                if (inliers[i]) weight += qualities[i]
            }
            if (weight > bestWeight) {
                bestWeight = weight; inliers.copyInto(bestInliers, endIndex = count)
            }
        }
        for (i in 0 until count) {
            candidate(dxs[i], dys[i], 0f)
            for (j in i + 1 until count) {
                val ax = xs[j] - xs[i]; val ay = ys[j] - ys[i]
                if (ax * ax + ay * ay < minOf(width, height) * minOf(width, height) * .16f) continue
                val bx = ax + dxs[j] - dxs[i]; val by = ay + dys[j] - dys[i]
                val angle = atan2(ax * by - ay * bx, ax * bx + ay * by)
                val c = cos(angle); val s = sin(angle)
                candidate((dxs[i] + dxs[j] - (c - 1f) * (xs[i] + xs[j]) + s * (ys[i] + ys[j])) * .5f,
                    (dys[i] + dys[j] - s * (xs[i] + xs[j]) - (c - 1f) * (ys[i] + ys[j])) * .5f, angle)
            }
        }
        // Weighted Procrustes rigid fit to inliers, never a mean of unrelated motion vectors.
        var total = 0f; var oldX = 0f; var oldY = 0f; var newX = 0f; var newY = 0f
        for (i in 0 until count) if (bestInliers[i]) {
            val q = qualities[i]; total += q; oldX += xs[i] * q; oldY += ys[i] * q
            newX += (xs[i] + dxs[i]) * q; newY += (ys[i] + dys[i]) * q
        }
        if (total <= 0f) return rejected("low_consensus")
        oldX /= total; oldY /= total; newX /= total; newY /= total
        var cross = 0f; var dot = 0f; var spread = 0f
        for (i in 0 until count) if (bestInliers[i]) {
            val ax = xs[i] - oldX; val ay = ys[i] - oldY
            val bx = xs[i] + dxs[i] - newX; val by = ys[i] + dys[i] - newY
            cross += qualities[i] * (ax * by - ay * bx); dot += qualities[i] * (ax * bx + ay * by)
            spread += qualities[i] * (ax * ax + ay * ay)
        }
        // Zoom/focus breathing and depth parallax are not part of a rigid camera transform.
        val fittedScale = if (spread > .001f) sqrt(cross * cross + dot * dot) / spread else 0f
        if (!fittedScale.isFinite() || abs(fittedScale - 1f) > .02f) return rejected("non_rigid")
        var angle = atan2(cross, dot)
        if (abs(angle) < .002f) angle = 0f // Quantization must not rotate a static scene.
        val c = cos(angle); val s = sin(angle)
        val tx = newX - c * oldX + s * oldY; val ty = newY - s * oldX - c * oldY
        if (abs(angle) > MAX_ROTATION) return rejected("low_consensus")
        var accepted = 0; var quality = 0f; var quadrants = 0
        var minX = Float.POSITIVE_INFINITY; var maxX = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY
        for (i in 0 until count) {
            val ex = dxs[i] - (tx + (c - 1f) * xs[i] - s * ys[i])
            val ey = dys[i] - (ty + s * xs[i] + (c - 1f) * ys[i])
            if (ex * ex + ey * ey > RESIDUAL_SQUARED) continue
            accepted++; quality += qualities[i]
            quadrants = quadrants or (1 shl ((if (xs[i] >= 0f) 1 else 0) + (if (ys[i] >= 0f) 2 else 0)))
            minX = minOf(minX, xs[i]); maxX = maxOf(maxX, xs[i]); minY = minOf(minY, ys[i]); maxY = maxOf(maxY, ys[i])
        }
        if (accepted < maxOf(5, ceil(textured * .6).toInt()) || Integer.bitCount(quadrants) < 3 ||
            maxX - minX < width * .45f || maxY - minY < height * .45f) return rejected("low_consensus")
        val confidence = (accepted / textured.toFloat() * .7f + quality / accepted * .3f).coerceIn(0f, 1f)
        return FrameMotionEstimate(tx / width, ty / height, confidence, true, false, "tracked", angle)
    }
    private fun blockMean(pixels: FloatArray, width: Int, cx: Int, cy: Int): Float {
        var sum = 0f
        for (y in -3..3 step 2) for (x in -3..3 step 2) sum += pixels[(cy + y) * width + cx + x]
        return sum / 16f
    }
    private fun blockContrast(pixels: FloatArray, width: Int, cx: Int, cy: Int, mean: Float): Float {
        var sum = 0f
        for (y in -3..3 step 2) for (x in -3..3 step 2) sum += abs(pixels[(cy + y) * width + cx + x] - mean)
        return sum / 16f
    }
    private fun blockCost(old: FloatArray, next: FloatArray, width: Int, cx: Int, cy: Int, nx: Int, ny: Int, oldMean: Float): Float {
        val nextMean = blockMean(next, width, nx, ny)
        var sum = 0f
        for (y in -3..3 step 2) for (x in -3..3 step 2)
            sum += abs((old[(cy + y) * width + cx + x] - oldMean) - (next[(ny + y) * width + nx + x] - nextMean))
        return sum / 16f
    }
    private fun refinement(old: FloatArray, next: FloatArray, width: Int, height: Int, cx: Int, cy: Int,
        dx: Int, dy: Int, oldMean: Float, horizontal: Boolean, best: Float): Float {
        if (best < .0001f) return 0f
        val nx = cx + dx; val ny = cy + dy
        if (nx < 4 || ny < 4 || nx + 4 >= width || ny + 4 >= height) return 0f
        val before = blockCost(old, next, width, cx, cy, nx - if (horizontal) 1 else 0, ny - if (horizontal) 0 else 1, oldMean)
        val after = blockCost(old, next, width, cx, cy, nx + if (horizontal) 1 else 0, ny + if (horizontal) 0 else 1, oldMean)
        val denominator = before - 2f * best + after
        return if (denominator > .00001f) (.5f * (before - after) / denominator).coerceIn(-.5f, .5f) else 0f
    }
    private fun excludedForeground(mask: PortraitMaskPolicy.Mask?, cx: Int, cy: Int, width: Int, height: Int): Boolean {
        if (mask == null) return false
        for (i in maskOffsets.indices step 2) {
            val x = ((cx + maskOffsets[i] + .5f) / width * mask.width).toInt().coerceIn(0, mask.width - 1)
            val y = ((cy + maskOffsets[i + 1] + .5f) / height * mask.height).toInt().coerceIn(0, mask.height - 1)
            val confidence = mask.confidence[y * mask.width + x]
            if (!confidence.isFinite() || confidence >= .6f) return true
        }
        return false
    }
    private fun rejected(reason: String, reset: Boolean = false) = FrameMotionEstimate(reason = reason, reset = reset)
    companion object {
        private const val MAX_ROTATION = .10f
        private const val RESIDUAL_SQUARED = 1.05f * 1.05f
    }
}
