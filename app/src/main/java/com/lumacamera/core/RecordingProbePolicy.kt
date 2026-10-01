package com.lumacamera.core

/** A native metadata failure may be transient; avoid both permanent poisoning and refresh-time hammering. */
object RecordingProbePolicy {
    const val FAILURE_RETRY_MS = 10_000L

    fun shouldRetry(valid: Boolean, checkedAtMs: Long, nowMs: Long): Boolean =
        !valid && (checkedAtMs < 0L || nowMs < checkedAtMs || nowMs - checkedAtMs >= FAILURE_RETRY_MS)
}
