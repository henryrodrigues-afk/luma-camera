package com.lumacamera.core

/** Defensive UI snapshot of the encoded/output signal histogram, not sensor RAW measurements. */
object HistogramDisplayPolicy {
    const val BIN_COUNT = 64
    data class Snapshot(
        val bins: IntArray,
        val peakCount: Int,
        val totalCount: Long,
        val shadowClip: Float,
        val highlightClip: Float,
        val mean: Float
    ) {
        val hasData: Boolean get() = totalCount > 0L
        fun heightAt(index: Int): Float = if (index in bins.indices && peakCount > 0)
            bins[index].toFloat() / peakCount else 0f
    }

    fun snapshot(bins: IntArray, shadowClip: Float = 0f, highlightClip: Float = 0f, mean: Float = .5f): Snapshot {
        val owned = IntArray(BIN_COUNT)
        if (bins.size == BIN_COUNT) for (index in owned.indices) owned[index] = bins[index].coerceAtLeast(0)
        var peak = 0; var total = 0L
        for (count in owned) { peak = maxOf(peak, count); total += count }
        return Snapshot(owned, peak, total, fraction(shadowClip, 0f), fraction(highlightClip, 0f), fraction(mean, .5f))
    }

    private fun fraction(value: Float, fallback: Float) = if (value.isFinite()) value.coerceIn(0f, 1f) else fallback
}
