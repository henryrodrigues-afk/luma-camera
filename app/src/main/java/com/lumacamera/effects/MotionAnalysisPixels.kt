package com.lumacamera.effects

import java.nio.ByteBuffer

/** CPU worker helpers. Caller owns the RGBA buffer until the job completes; no extra GPU readback. */
object MotionAnalysisPixels {
    fun luma(rgba: ByteBuffer, width: Int, height: Int, destination: FloatArray): FloatArray {
        val count = width.toLong() * height
        require(width > 0 && height > 0 && count == destination.size.toLong() && count <= rgba.limit() / 4)
        for (index in destination.indices) {
            val offset = index * 4
            destination[index] = ((rgba.get(offset).toInt() and 255) * .2126f +
                (rgba.get(offset + 1).toInt() and 255) * .7152f +
                (rgba.get(offset + 2).toInt() and 255) * .0722f) / 255f
        }
        return destination
    }

    /** Segmenter output masks are immutable snapshots. Own a small resampled mask for consume(). */
    fun maskSnapshot(mask: PortraitMaskPolicy.Mask?, width: Int, height: Int,
        capturedAtMs: Long, destination: FloatArray): PortraitMaskPolicy.Mask? {
        require(width > 0 && height > 0 && width.toLong() * height == destination.size.toLong())
        if (mask == null || mask.width <= 0 || mask.height <= 0 ||
            mask.width.toLong() * mask.height != mask.confidence.size.toLong() || mask.capturedAtMs < 0L ||
            capturedAtMs < mask.capturedAtMs || capturedAtMs - mask.capturedAtMs > 600L) return null
        for (y in 0 until height) for (x in 0 until width)
            destination[y * width + x] = PortraitMaskPolicy.confidenceAt(mask, (x + .5f) / width, (y + .5f) / height)
        return PortraitMaskPolicy.Mask(width, height, destination, mask.capturedAtMs)
    }
}
