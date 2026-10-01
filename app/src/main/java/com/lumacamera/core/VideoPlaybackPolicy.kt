package com.lumacamera.core

data class PlaybackCheckpoint(val positionMs: Long = 0L, val playWhenReady: Boolean = true)

/** Preserve user intent across lifecycle pauses, without treating a completed clip as autoplay. */
object VideoPlaybackPolicy {
    const val SEEK_STEPS = 10_000
    enum class Stall { SURFACE, BUFFERING, SEEK, POSITION, FIRST_FRAME, VIDEO_FRAMES }
    enum class Backend { MEDIA3_SURFACE, MEDIA3_TEXTURE, ANDROID_NATIVE }

    /** Bounded internal alternatives; never retry the same broken decoder/surface indefinitely. */
    fun nextBackend(current: Backend, recoverable: Boolean = true): Backend? = if (!recoverable) null else when (current) {
        Backend.MEDIA3_SURFACE -> Backend.MEDIA3_TEXTURE
        Backend.MEDIA3_TEXTURE -> Backend.ANDROID_NATIVE
        Backend.ANDROID_NATIVE -> null
    }

    /** Watch only an active, requested playback. Pauses, audio-focus suppression and absent surfaces are safe. */
    fun stall(
        hostActive: Boolean, requested: Boolean, isPlaying: Boolean,
        bufferingAgeMs: Long?, positionAgeMs: Long?, videoFrameAgeMs: Long?,
        selectedVideo: Boolean, surfaceReady: Boolean,
        surfaceAgeMs: Long? = null, firstFrameSeen: Boolean = true, seekingAgeMs: Long? = null
    ): Stall? {
        if (!hostActive || !requested) return null
        if (selectedVideo && !surfaceReady && surfaceAgeMs != null && surfaceAgeMs >= 8_000L) return Stall.SURFACE
        if (seekingAgeMs != null) return if (seekingAgeMs >= 12_000L) Stall.SEEK else null
        if (bufferingAgeMs != null && bufferingAgeMs >= 12_000L) return Stall.BUFFERING
        if (!isPlaying) return null
        if (selectedVideo && surfaceReady && !firstFrameSeen && videoFrameAgeMs != null && videoFrameAgeMs >= 6_000L)
            return Stall.FIRST_FRAME
        if (positionAgeMs != null && positionAgeMs >= 10_000L) return Stall.POSITION
        if (selectedVideo && surfaceReady && videoFrameAgeMs != null && videoFrameAgeMs >= 10_000L)
            return Stall.VIDEO_FRAMES
        return null
    }

    /** A prepared player must not start between onStart and onResume, or during a background pause. */
    fun shouldPlay(hostStarted: Boolean, hostResumed: Boolean, requested: Boolean): Boolean =
        hostStarted && hostResumed && requested

    /** A surface/audio-focus callback must not restart sound while the timeline is being dragged. */
    fun shouldRunNative(hostStarted: Boolean, hostResumed: Boolean, requested: Boolean,
        scrubbing: Boolean, seeking: Boolean, surfaceReady: Boolean, suppressed: Boolean): Boolean =
        shouldPlay(hostStarted, hostResumed, requested) && !scrubbing && !seeking && surfaceReady && !suppressed

    /** Media3 retains playWhenReady at ENDED; user intent alone must not hold the display awake. */
    fun shouldKeepScreenOn(hostStarted: Boolean, hostResumed: Boolean, requested: Boolean,
        hasPlayer: Boolean, ended: Boolean, suppressed: Boolean, failed: Boolean): Boolean =
        shouldPlay(hostStarted, hostResumed, requested) && hasPlayer && !ended && !suppressed && !failed

    /** Explicit Play after completion starts at zero; an ordinary pause resumes at its saved position. */
    fun play(checkpoint: PlaybackCheckpoint, ended: Boolean, durationMs: Long?): PlaybackCheckpoint =
        PlaybackCheckpoint(if (ended || (durationMs != null && durationMs > 0 && checkpoint.positionMs >= durationMs))
            0L else position(checkpoint.positionMs, durationMs), true)

    /** A normalized timeline avoids Int overflow for long recordings; unknown duration cannot be sought. */
    fun seekPosition(progress: Int, durationMs: Long?, steps: Int = SEEK_STEPS): Long? {
        if (durationMs == null || durationMs <= 0 || steps <= 0) return null
        return (durationMs.toDouble() * progress.coerceIn(0, steps) / steps).toLong().coerceIn(0L, durationMs)
    }

    fun seekProgress(positionMs: Long, durationMs: Long?, steps: Int = SEEK_STEPS): Int {
        if (durationMs == null || durationMs <= 0 || steps <= 0) return 0
        return (position(positionMs, durationMs).toDouble() / durationMs * steps).toInt().coerceIn(0, steps)
    }

    /** Display, saved checkpoint and seeks share one bounded domain; TIME_UNSET is never a timestamp. */
    fun displayPosition(positionMs: Long, durationMs: Long?): Long = position(positionMs, durationMs)

    /** Error-state platform getters cannot replace healthy playback progress with the original bind point. */
    fun failurePosition(checkpoint: PlaybackCheckpoint, lastHealthyPositionMs: Long,
        prepared: Boolean, seeking: Boolean, durationMs: Long?): Long = position(
        if (prepared && !seeking) lastHealthyPositionMs else checkpoint.positionMs, durationMs)

    fun skip(positionMs: Long, deltaMs: Long, durationMs: Long?): Long {
        val current = position(positionMs, durationMs)
        val shifted = if (deltaMs > 0 && current > Long.MAX_VALUE - deltaMs) Long.MAX_VALUE else current + deltaMs
        return position(shifted, durationMs)
    }

    /** Match decoded pixels and any unapplied rotation without stretching the video into its viewport. */
    fun displayAspectRatio(width: Int, height: Int, pixelAspect: Float, rotation: Int): Float {
        if (width <= 0 || height <= 0 || !pixelAspect.isFinite() || pixelAspect <= 0f) return 16f / 9f
        val aspect = width.toFloat() * pixelAspect / height
        val normalized = ((rotation % 360) + 360) % 360
        return (if (normalized == 90 || normalized == 270) 1f / aspect else aspect)
            .let { if (it.isFinite() && it > 0f) it else 16f / 9f }
    }

    fun restore(checkpoint: PlaybackCheckpoint, durationMs: Long? = null): PlaybackCheckpoint = checkpoint.copy(
        positionMs = position(checkpoint.positionMs, durationMs)
    )

    fun snapshot(
        positionMs: Long,
        durationMs: Long?,
        currentPlayWhenReady: Boolean,
        hostResumed: Boolean,
        previous: PlaybackCheckpoint,
        ended: Boolean
    ): PlaybackCheckpoint = if (ended) PlaybackCheckpoint(0L, false) else PlaybackCheckpoint(
        position(positionMs, durationMs),
        if (hostResumed) currentPlayWhenReady else previous.playWhenReady
    )

    private fun position(positionMs: Long, durationMs: Long?): Long {
        val nonnegative = positionMs.coerceAtLeast(0L)
        return if (durationMs != null && durationMs >= 0L) nonnegative.coerceAtMost(durationMs) else nonnegative
    }
}
