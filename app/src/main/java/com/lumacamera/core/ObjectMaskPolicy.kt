package com.lumacamera.core

import com.lumacamera.effects.PortraitMaskPolicy
import kotlin.math.ceil

/** Geometry and conservative mask checks for the bundled, single-point MagicTouch model. */
object ObjectMaskPolicy {
    data class Point(val x: Float, val y: Float)

    /** The prompt uses upright top-left coordinates; capture and masks use source GLES coordinates. */
    fun uprightPoint(sourceGlX: Float, sourceGlY: Float, rotation: Int): Point? {
        if (!sourceGlX.isFinite() || !sourceGlY.isFinite() ||
            sourceGlX !in 0f..1f || sourceGlY !in 0f..1f) return null
        return when (((rotation % 360) + 360) % 360) {
            0 -> Point(sourceGlX, 1f - sourceGlY)
            90 -> Point(sourceGlY, sourceGlX)
            180 -> Point(1f - sourceGlX, sourceGlY)
            270 -> Point(1f - sourceGlY, 1f - sourceGlX)
            else -> throw IllegalArgumentException("Rotation must be a quarter turn")
        }
    }

    /** v1 emits one sigmoid foreground mask. Two-channel model outputs use background then foreground. */
    fun foregroundMaskIndex(maskCount: Int): Int = when (maskCount) {
        1 -> 0
        2 -> 1
        else -> throw IllegalStateException("Unexpected object-mask count: $maskCount")
    }

    fun hasObject(mask: PortraitMaskPolicy.Mask): Boolean {
        if (!validShape(mask)) return false
        val pixels = mask.confidence.size
        val confident = mask.confidence.count { it.isFinite() && it >= .65f }
        return confident >= maxOf(1, ceil(pixels * .003).toInt()) && confident <= pixels * .95
    }

    /** Reject a background prompt and preserve only the connected foreground around the selected object. */
    fun selectedComponent(mask: PortraitMaskPolicy.Mask, seed: Point): PortraitMaskPolicy.Mask {
        if (!validShape(mask) || !validPoint(seed)) return empty(mask)
        val width = mask.width
        val height = mask.height
        val centerX = (seed.x * width - .5f).toInt().coerceIn(0, width - 1)
        val centerY = (seed.y * height - .5f).toInt().coerceIn(0, height - 1)
        // A small tolerance accommodates a tap on an antialiased edge without selecting a distant object.
        val radius = maxOf(1, ceil(maxOf(width, height) * .025).toInt())
        var anchor = -1
        var closest = Float.POSITIVE_INFINITY
        for (y in maxOf(0, centerY - radius)..minOf(height - 1, centerY + radius)) {
            for (x in maxOf(0, centerX - radius)..minOf(width - 1, centerX + radius)) {
                val index = y * width + x
                if (mask.confidence[index] < .6f || !mask.confidence[index].isFinite()) continue
                val dx = (x + .5f) / width - seed.x
                val dy = (y + .5f) / height - seed.y
                val distance = dx * dx + dy * dy
                if (distance < closest) { closest = distance; anchor = index }
            }
        }
        if (anchor < 0) return empty(mask)
        val selected = BooleanArray(mask.confidence.size)
        val queue = IntArray(mask.confidence.size)
        var head = 0
        var tail = 1
        queue[0] = anchor
        selected[anchor] = true
        fun enqueue(index: Int) {
            if (!selected[index] && mask.confidence[index].isFinite() && mask.confidence[index] >= .5f) {
                selected[index] = true
                queue[tail++] = index
            }
        }
        while (head < tail) {
            val index = queue[head++]
            val x = index % width
            val y = index / width
            if (x > 0) enqueue(index - 1)
            if (x + 1 < width) enqueue(index + 1)
            if (y > 0) enqueue(index - width)
            if (y + 1 < height) enqueue(index + width)
        }
        val output = FloatArray(mask.confidence.size)
        for (index in output.indices) {
            val x = index % width
            val y = index / width
            val softEdge = (x > 0 && selected[index - 1]) ||
                (x + 1 < width && selected[index + 1]) ||
                (y > 0 && selected[index - width]) ||
                (y + 1 < height && selected[index + width])
            if (selected[index] || softEdge) {
                output[index] = mask.confidence[index].let { if (it.isFinite()) it.coerceIn(0f, 1f) else 0f }
            }
        }
        return mask.copy(confidence = output).let { if (hasObject(it)) it else empty(it) }
    }

    /** Assisted reseeding for slow movement; this is not an object identity tracker. */
    fun trackingPoint(
        mask: PortraitMaskPolicy.Mask,
        previousPoint: Point,
        previousMask: PortraitMaskPolicy.Mask? = null
    ): Point? {
        if (!hasObject(mask) || !validPoint(previousPoint)) return null
        var weightSum = 0.0
        var weightedX = 0.0
        var weightedY = 0.0
        var area = 0
        for (index in mask.confidence.indices) {
            val weight = mask.confidence[index]
            if (!weight.isFinite() || weight < .65f) continue
            weightedX += ((index % mask.width + .5) / mask.width) * weight
            weightedY += ((index / mask.width + .5) / mask.height) * weight
            weightSum += weight
            area++
        }
        val centerX = (weightedX / weightSum).toFloat()
        val centerY = (weightedY / weightSum).toFloat()
        if (previousMask != null && hasObject(previousMask)) {
            var oldArea = 0
            var oldWeightSum = 0.0
            var oldWeightedX = 0.0
            var oldWeightedY = 0.0
            for (index in previousMask.confidence.indices) {
                val weight = previousMask.confidence[index]
                if (!weight.isFinite() || weight < .65f) continue
                oldArea++
                oldWeightSum += weight
                oldWeightedX += ((index % previousMask.width + .5) / previousMask.width) * weight
                oldWeightedY += ((index / previousMask.width + .5) / previousMask.height) * weight
            }
            val areaRatio = (area.toFloat() / mask.confidence.size) /
                (oldArea.toFloat() / previousMask.confidence.size)
            // Compare like geometry. An interior prompt can be far from a hollow object's centroid.
            val dx = centerX - (oldWeightedX / oldWeightSum).toFloat()
            val dy = centerY - (oldWeightedY / oldWeightSum).toFloat()
            if (areaRatio !in .25f..4f || dx * dx + dy * dy > .2f * .2f) return null
        }
        // A centroid can fall in a hollow object's background: project it onto an actual foreground pixel.
        var closest = Float.POSITIVE_INFINITY
        var chosenIndex = -1
        for (index in mask.confidence.indices) {
            if (!mask.confidence[index].isFinite() || mask.confidence[index] < .65f) continue
            val x = (index % mask.width + .5f) / mask.width
            val y = (index / mask.width + .5f) / mask.height
            val dx = x - centerX
            val dy = y - centerY
            val distance = dx * dx + dy * dy
            if (distance < closest) { closest = distance; chosenIndex = index }
        }
        return if (chosenIndex < 0) null else Point(
            (chosenIndex % mask.width + .5f) / mask.width,
            (chosenIndex / mask.width + .5f) / mask.height
        )
    }

    /** Age is measured from capture, never completion: an old silhouette must not become a fresh result. */
    fun freshness(nowMs: Long, capturedAtMs: Long): Float {
        if (capturedAtMs < 0L || nowMs < capturedAtMs) return 0f
        val age = nowMs - capturedAtMs
        return when {
            age <= 450L -> 1f
            age >= 1_200L -> 0f
            else -> (1_200L - age) / 750f
        }
    }

    fun empty(mask: PortraitMaskPolicy.Mask): PortraitMaskPolicy.Mask =
        mask.copy(confidence = FloatArray(mask.confidence.size))

    private fun validPoint(point: Point) = point.x.isFinite() && point.y.isFinite() &&
        point.x in 0f..1f && point.y in 0f..1f

    private fun validShape(mask: PortraitMaskPolicy.Mask) = mask.width > 0 && mask.height > 0 &&
        mask.width.toLong() * mask.height == mask.confidence.size.toLong()
}
