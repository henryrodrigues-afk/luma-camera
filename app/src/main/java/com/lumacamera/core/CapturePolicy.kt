package com.lumacamera.core

import kotlin.math.roundToLong

/** A requested recording mode; the camera and encoder must still confirm it. */
data class VideoMode(val width: Int, val height: Int, val fps: Int, val bitrate: Int)

data class EncoderLimits(
    val minWidth: Int,
    val maxWidth: Int,
    val minHeight: Int,
    val maxHeight: Int,
    val widthAlignment: Int,
    val heightAlignment: Int,
    val maxBitrate: Int,
    val minBitrate: Int = 1,
)

/** Pure negotiation rules shared by capability discovery and recording. */
object CapturePolicy {
    fun boundedBitrate(width: Int, height: Int, fps: Int, maxBitrate: Int, minBitrate: Int = 1): Int {
        require(width > 0 && height > 0 && fps > 0) { "Dimensions and FPS must be positive" }
        require(minBitrate > 0 && maxBitrate >= minBitrate) { "Invalid encoder bitrate range" }
        // Convert before multiplication to avoid overflowing an Int for large sensors.
        val estimate = (width.toDouble() * height * fps * 0.14).roundToLong()
        return estimate.coerceIn(2_000_000L, 40_000_000L)
            .coerceIn(minBitrate.toLong(), maxBitrate.toLong()).toInt()
    }

    /** Quality changes encoding budget, never camera resolution/FPS or an unsupported bitrate mode. */
    fun scaledBitrate(baseBitrate: Int, scale: Float, minBitrate: Int = 1, maxBitrate: Int = Int.MAX_VALUE): Int {
        require(baseBitrate > 0) { "Base bitrate must be positive" }
        require(minBitrate > 0 && maxBitrate >= minBitrate) { "Invalid encoder bitrate range" }
        val boundedScale = if (scale.isFinite()) scale.coerceIn(.5f, 2f) else 1f
        return (baseBitrate.toDouble() * boundedScale).roundToLong()
            .coerceIn(minBitrate.toLong(), maxBitrate.toLong()).toInt()
    }

    /** Camera2 locks affect their automatic controllers only; manual exposure/WB retain full ownership. */
    fun autoLock(requested: Boolean, supported: Boolean, automatic: Boolean): Boolean =
        requested && supported && automatic

    /** MediaRecorder exposes an uncalibrated maximum-amplitude proxy, not a precise dBFS meter. */
    fun audioPeak(amplitude: Int): Float = amplitude.coerceIn(0, 32_767) / 32_767f

    fun frameDurationNs(fps: Int): Long {
        require(fps > 0) { "FPS must be positive" }
        return 1_000_000_000L / fps
    }

    fun clampExposureNs(requested: Long, sensorMin: Long, sensorMax: Long, fps: Int): Long {
        require(sensorMin > 0 && sensorMax >= sensorMin) { "Invalid sensor exposure bounds" }
        val upper = minOf(sensorMax, frameDurationNs(fps))
        require(sensorMin <= upper) { "Sensor minimum exposure exceeds the frame budget" }
        return requested.coerceIn(sensorMin, upper)
    }

    /** Never invent or upscale a camera size; FPS ranges alone are only a first filter. */
    fun compatibleModes(
        sizes: List<Pair<Int, Int>>,
        fpsRanges: List<IntRange>,
        encoder: EncoderLimits,
        maxFps: Int = 30,
    ): List<VideoMode> {
        require(encoder.minWidth > 0 && encoder.maxWidth >= encoder.minWidth)
        require(encoder.minHeight > 0 && encoder.maxHeight >= encoder.minHeight)
        require(encoder.widthAlignment > 0 && encoder.heightAlignment > 0)
        require(encoder.minBitrate > 0 && encoder.maxBitrate >= encoder.minBitrate)
        require(maxFps > 0)
        val rates = listOf(30, 24).filter { fps ->
            fps <= maxFps && fpsRanges.any { fps in it }
        }
        return sizes.asSequence()
            .filter { (width, height) ->
                width in encoder.minWidth..encoder.maxWidth &&
                    height in encoder.minHeight..encoder.maxHeight &&
                    width % encoder.widthAlignment == 0 &&
                    height % encoder.heightAlignment == 0
            }
            .flatMap { (width, height) ->
                rates.asSequence().map { fps ->
                    VideoMode(width, height, fps, boundedBitrate(width, height, fps, encoder.maxBitrate, encoder.minBitrate))
                }
            }
            .distinct()
            .sortedWith(compareByDescending<VideoMode> { it.width.toLong() * it.height }
                .thenByDescending { it.fps })
            .toList()
    }
}
