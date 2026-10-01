package com.lumacamera.effects

/** Fresh connected subject evidence drives AF. It never estimates depth or invents lens distance. */
class SubjectTrackAnalysis {
    private var previous: Pair<Float, Float>? = null
    private var visited = BooleanArray(0)
    private var queue = IntArray(0)

    fun reset(selected: Pair<Float, Float>? = null) {
        previous = selected?.takeIf { it.first.isFinite() && it.second.isFinite() &&
            it.first in 0f..1f && it.second in 0f..1f }
    }

    /** Keep one connected person/object, rather than aiming between two unrelated people. */
    fun validatedCentroid(mask: PortraitMaskPolicy.Mask?, hasSubject: Boolean, freshness: Float): Pair<Float, Float>? {
        if (!hasSubject || !freshness.isFinite() || freshness < .8f || mask == null || mask.width <= 0 || mask.height <= 0 ||
            mask.width.toLong() * mask.height != mask.confidence.size.toLong() ||
            mask.confidence.size > 512 * 512) return null
        val count = mask.confidence.size
        if (visited.size < count) { visited = BooleanArray(count); queue = IntArray(count) }
        visited.fill(false, 0, count)
        val anchor = previous
        var chosen: Pair<Float, Float>? = null
        var bestArea = 0
        var bestDistance = Float.POSITIVE_INFINITY
        fun supported(index: Int): Boolean {
            val value = mask.confidence[index]
            return value.isFinite() && value >= .6f
        }
        for (start in 0 until count) {
            if (visited[start] || !supported(start)) continue
            var head = 0; var tail = 1
            queue[0] = start; visited[start] = true
            var weight = 0.0; var xSum = 0.0; var ySum = 0.0
            while (head < tail) {
                val index = queue[head++]
                val x = index % mask.width; val y = index / mask.width
                val confidence = mask.confidence[index].coerceAtMost(1f).toDouble()
                weight += confidence
                xSum += (x + .5) / mask.width * confidence
                ySum += (y + .5) / mask.height * confidence
                fun append(next: Int) {
                    if (!visited[next] && supported(next)) { visited[next] = true; queue[tail++] = next }
                }
                if (x > 0) append(index - 1)
                if (x + 1 < mask.width) append(index + 1)
                if (y > 0) append(index - mask.width)
                if (y + 1 < mask.height) append(index + mask.width)
            }
            val coverage = tail.toDouble() / count
            if (weight <= 0.0 || coverage < .003 || coverage > .9) continue
            var point = (xSum / weight).toFloat().coerceIn(.02f, .98f) to
                (ySum / weight).toFloat().coerceIn(.02f, .98f)
            // A hollow subject's centroid can land on a different component inside it. Global
            // confidence alone does not establish that this point belongs to the chosen subject.
            val centerPixel = (point.second * mask.height).toInt().coerceIn(0, mask.height - 1) * mask.width +
                (point.first * mask.width).toInt().coerceIn(0, mask.width - 1)
            var centerInComponent = false
            for (position in 0 until tail) if (queue[position] == centerPixel) {
                centerInComponent = true; break
            }
            if (!centerInComponent || PortraitMaskPolicy.confidenceAt(mask, point.first, point.second) < .6f) {
                var nearest = Float.POSITIVE_INFINITY
                var interior = point
                for (position in 0 until tail) {
                    val pixel = queue[position]
                    val x = (pixel % mask.width + .5f) / mask.width
                    val y = (pixel / mask.width + .5f) / mask.height
                    val dx = x - point.first; val dy = y - point.second
                    val distance = dx * dx + dy * dy
                    if (distance < nearest) { nearest = distance; interior = x to y }
                }
                point = interior
            }
            if (anchor == null) {
                if (tail > bestArea) { bestArea = tail; chosen = point }
            } else {
                val dx = point.first - anchor.first; val dy = point.second - anchor.second
                val distance = dx * dx + dy * dy
                if (distance <= .25f * .25f && distance < bestDistance) { bestDistance = distance; chosen = point }
            }
        }
        // Retain anchor on loss; another person entering must not silently steal the optical target.
        if (chosen != null) previous = chosen
        return chosen
    }
}
