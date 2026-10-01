package com.lumacamera.core

/** Reject a newly recorded file with a foreign-clock timeline; never replace its duration with a guess. */
object RecordingValidation {
    fun durationIsPlausible(durationMs: Long, firstVideoTimeUs: Long, expectedDurationMs: Long?): Boolean {
        if (durationMs <= 0L || firstVideoTimeUs < 0L) return false
        if (expectedDurationMs == null || expectedDurationMs <= 0L) return true
        // Allow native startup, codec draining and rounding. Hours of source-clock offset are not latency.
        val toleranceMs = maxOf(3_000L, expectedDurationMs / 4L)
        val maximumMs = if (expectedDurationMs > Long.MAX_VALUE - toleranceMs) Long.MAX_VALUE
            else expectedDurationMs + toleranceMs
        val minimumMs = (expectedDurationMs - toleranceMs).coerceAtLeast(1L)
        return durationMs in minimumMs..maximumMs && firstVideoTimeUs / 1_000L <= maximumMs
    }
}
