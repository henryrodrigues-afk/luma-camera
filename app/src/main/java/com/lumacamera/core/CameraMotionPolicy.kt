package com.lumacamera.core

/** Time-based A/B moves; independent of UI frame rate and without depth estimation. */
object CameraMotionPolicy {
    const val MIN_DURATION_MS = 100L
    const val MAX_DURATION_MS = 120_000L

    fun durationMs(seconds: Float): Long? = if (seconds.isFinite() && seconds in .1f..120f)
        (seconds.toDouble() * 1_000.0).toLong().coerceIn(MIN_DURATION_MS, MAX_DURATION_MS) else null

    fun sample(a: Float, b: Float, elapsedMs: Long, durationMs: Long,
        minValue: Float, maxValue: Float): Float? {
        if (!a.isFinite() || !b.isFinite() || !minValue.isFinite() || !maxValue.isFinite() ||
            minValue > maxValue || durationMs !in MIN_DURATION_MS..MAX_DURATION_MS) return null
        val progress = (elapsedMs.coerceAtLeast(0L).toDouble() / durationMs).coerceIn(0.0, 1.0)
        val eased = progress * progress * (3.0 - 2.0 * progress)
        val start = a.coerceIn(minValue, maxValue).toDouble()
        val end = b.coerceIn(minValue, maxValue).toDouble()
        return (start + (end - start) * eased).toFloat().coerceIn(minValue, maxValue)
    }

    fun completed(elapsedMs: Long, durationMs: Long): Boolean =
        durationMs in MIN_DURATION_MS..MAX_DURATION_MS && elapsedMs >= durationMs
}
