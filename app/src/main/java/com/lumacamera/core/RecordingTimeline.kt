package com.lumacamera.core

/**
 * Surface input for MediaRecorder uses Android's CLOCK_MONOTONIC (System.nanoTime),
 * including its absolute origin. Camera timestamps may use BOOTTIME or an unknown
 * origin, so they must never become recorder PTS. Gaps retain their real duration;
 * neither a frame counter nor a zero-based presentation clock is used here.
 */
class RecordingTimeline(fps: Int) {
    private val periodNs = CapturePolicy.frameDurationNs(fps)
    private var nextDeadlineNs: Long? = null
    private var previousPresentationNs: Long? = null

    /** FPS is a ceiling. A slow GPU drops frames without speeding up the recorded scene. */
    fun presentationTime(monotonicNowNs: Long): Long? {
        val previous = previousPresentationNs
        // MediaCodec transports microseconds; don't submit duplicate timestamps after that conversion.
        if (previous != null && monotonicNowNs / 1_000L <= previous / 1_000L) return null
        val deadline = nextDeadlineNs
        if (deadline != null && monotonicNowNs < deadline && deadline - monotonicNowNs > 1_000_000L) return null
        previousPresentationNs = monotonicNowNs
        nextDeadlineNs = if (deadline == null || monotonicNowNs - deadline > periodNs * 2L)
            addPeriod(monotonicNowNs) else addPeriod(deadline)
        return monotonicNowNs
    }

    private fun addPeriod(value: Long): Long = if (value > Long.MAX_VALUE - periodNs) Long.MAX_VALUE else value + periodNs

    companion object {
        /** Duplicate callbacks can update to the same image; a restarted camera clock is still a new image. */
        fun isNewCameraFrame(timestampNs: Long, previousNs: Long?): Boolean =
            timestampNs <= 0L || previousNs == null || timestampNs != previousNs

        fun elapsedMs(startedAtNs: Long?, nowNs: Long, recording: Boolean): Long =
            if (!recording || startedAtNs == null || nowNs <= startedAtNs) 0L else (nowNs - startedAtNs) / 1_000_000L
    }
}
