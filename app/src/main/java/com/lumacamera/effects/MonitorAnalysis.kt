package com.lumacamera.effects

import java.nio.ByteBuffer

/** A snapshot of the encoded image being recorded, before display assistance or monitor overlays. */
data class MonitorHistogram(
    val bins: IntArray,
    val shadowClip: Float,
    val highlightClip: Float,
    val mean: Float
)

/** Density maps of the recorded RGB signal; scopes are intentionally independent of display LUT/assist. */
data class MonitorScopes(
    val histogram: MonitorHistogram,
    val waveform: IntArray,
    val rgbParade: IntArray,
    val vectorscope: IntArray,
    val columns: Int = MonitorAnalysis.SCOPE_COLUMNS,
    val levels: Int = MonitorAnalysis.BIN_COUNT
)

/** SDR RGB-luma approximation; encoded LOG stays encoded here. This is not a sensor/RAW histogram. */
object MonitorAnalysis {
    const val BIN_COUNT = 64
    const val READBACK_WIDTH = 64
    const val READBACK_HEIGHT = 36
    const val INTERVAL_MS = 250L
    const val SCOPE_COLUMNS = 64

    /** One pixel traversal for all enabled scopes. Empty arrays represent disabled tools. */
    fun scopes(rgba: ByteBuffer, width: Int, height: Int,
        waveformEnabled: Boolean, rgbParadeEnabled: Boolean, vectorscopeEnabled: Boolean): MonitorScopes {
        val pixelCount = width.toLong() * height
        require(width > 0 && height > 0 && pixelCount <= rgba.limit() / 4)
        val count = pixelCount.toInt()
        val bins = IntArray(BIN_COUNT)
        val waveform = if (waveformEnabled) IntArray(SCOPE_COLUMNS * BIN_COUNT) else IntArray(0)
        val parade = if (rgbParadeEnabled) IntArray(3 * SCOPE_COLUMNS * BIN_COUNT) else IntArray(0)
        val vector = if (vectorscopeEnabled) IntArray(BIN_COUNT * BIN_COUNT) else IntArray(0)
        var shadows = 0; var highlights = 0; var sum = 0.0
        for (index in 0 until count) {
            val offset = index * 4
            val r = (rgba.get(offset).toInt() and 255) / 255f
            val g = (rgba.get(offset + 1).toInt() and 255) / 255f
            val b = (rgba.get(offset + 2).toInt() and 255) / 255f
            val luminance = r * .2126f + g * .7152f + b * .0722f
            val level = (luminance * BIN_COUNT).toInt().coerceIn(0, BIN_COUNT - 1)
            bins[level]++
            if (luminance <= .01f) shadows++
            if (luminance >= .99f) highlights++
            sum += luminance
            val column = ((index % width).toLong() * SCOPE_COLUMNS / width).toInt().coerceIn(0, SCOPE_COLUMNS - 1)
            if (waveformEnabled) waveform[level * SCOPE_COLUMNS + column]++
            if (rgbParadeEnabled) {
                val slice = SCOPE_COLUMNS * BIN_COUNT
                parade[(r * BIN_COUNT).toInt().coerceIn(0, BIN_COUNT - 1) * SCOPE_COLUMNS + column]++
                parade[slice + (g * BIN_COUNT).toInt().coerceIn(0, BIN_COUNT - 1) * SCOPE_COLUMNS + column]++
                parade[2 * slice + (b * BIN_COUNT).toInt().coerceIn(0, BIN_COUNT - 1) * SCOPE_COLUMNS + column]++
            }
            if (vectorscopeEnabled) {
                // Rec.709-derived Cb/Cr approximation, normalized into -0.5..0.5. No RAW/HDR calibration.
                val cb = (b - luminance) / 1.8556f
                val cr = (r - luminance) / 1.5748f
                val x = ((cb + .5f) * (BIN_COUNT - 1)).toInt().coerceIn(0, BIN_COUNT - 1)
                val y = ((cr + .5f) * (BIN_COUNT - 1)).toInt().coerceIn(0, BIN_COUNT - 1)
                vector[y * BIN_COUNT + x]++
            }
        }
        return MonitorScopes(MonitorHistogram(bins, shadows.toFloat() / count, highlights.toFloat() / count,
            (sum / count).toFloat()), waveform, parade, vector)
    }

    fun histogram(rgba: ByteBuffer, width: Int, height: Int): MonitorHistogram {
        val pixelCount = width.toLong() * height
        require(width > 0 && height > 0 && pixelCount <= rgba.limit() / 4)
        val count = pixelCount.toInt()
        val bins = IntArray(BIN_COUNT)
        var shadows = 0
        var highlights = 0
        var sum = 0.0
        for (index in 0 until count) {
            val offset = index * 4
            val red = rgba.get(offset).toInt() and 255
            val green = rgba.get(offset + 1).toInt() and 255
            val blue = rgba.get(offset + 2).toInt() and 255
            val luminance = (red * .2126f + green * .7152f + blue * .0722f) / 255f
            bins[(luminance * BIN_COUNT).toInt().coerceIn(0, BIN_COUNT - 1)]++
            if (luminance <= .01f) shadows++
            if (luminance >= .99f) highlights++
            sum += luminance
        }
        // Each result owns its bins. UI dispatch can retain it without racing a reused GPU buffer.
        return MonitorHistogram(bins, shadows.toFloat() / count, highlights.toFloat() / count,
            (sum / count).toFloat())
    }
}
