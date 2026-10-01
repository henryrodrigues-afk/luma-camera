package com.lumacamera.effects

import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

/**
 * LumaLog is a reversible curve for the already processed SDR camera signal.
 * v1 remaps each channel; v2 additionally compresses chroma for a flat appearance.
 * It assumes an sRGB-like transfer, rather than identifying a sensor color space.
 * This is not S-Log, Apple Log, RAW, or a way to recover highlights clipped upstream.
 */
object SimulatedLog {
    const val LEGACY_VERSION = 1
    const val CURRENT_VERSION = 2
    const val CHROMA_COMPRESSION = .4f
    // Align endpoints with a 33-point grid, avoiding interpolation across the toe.
    const val BLACK = .09375f
    const val WHITE = .9375f
    private const val K = 63.0
    private val denominator = ln(1.0 + K)
    private val weights = doubleArrayOf(.2126, .7152, .0722)

    fun encode(signal: Float, strength: Float = 1f): Float {
        val value = signal.coerceIn(0f, 1f).toDouble()
        val amount = strength.coerceIn(0f, 1f).toDouble()
        return encodeDouble(value, amount).toFloat()
    }

    fun decode(signal: Float, strength: Float = 1f): Float {
        val value = signal.coerceIn(0f, 1f).toDouble()
        val amount = strength.coerceIn(0f, 1f).toDouble()
        if (amount == 0.0) return value.toFloat()
        if (amount == 1.0) {
            val normalized = ((value - BLACK) / (WHITE - BLACK)).coerceIn(0.0, 1.0)
            val linear = ((1.0 + K).pow(normalized) - 1.0) / K
            return srgbEncode(linear).coerceIn(0.0, 1.0).toFloat()
        }
        // The blend with the original SDR curve remains strictly monotonic.
        // Bisection provides a matching inverse at any user-selected strength.
        var low = 0.0
        var high = 1.0
        repeat(28) {
            val middle = (low + high) * .5
            if (encodeDouble(middle, amount) < value) low = middle else high = middle
        }
        return ((low + high) * .5).toFloat()
    }

    fun encodeRgb(red: Float, green: Float, blue: Float, strength: Float = 1f,
        profileVersion: Int = CURRENT_VERSION): FloatArray {
        require(profileVersion in LEGACY_VERSION..CURRENT_VERSION)
        val amount = strength.coerceIn(0f, 1f).toDouble()
        val channels = doubleArrayOf(red.toDouble(), green.toDouble(), blue.toDouble())
            .map { encodeDouble(it.coerceIn(0.0, 1.0), amount) }
        if (profileVersion == LEGACY_VERSION || amount == 0.0) return channels.map(Double::toFloat).toFloatArray()
        val luminance = channels.indices.sumOf { channels[it] * weights[it] }
        val retention = 1.0 - CHROMA_COMPRESSION * amount
        return FloatArray(3) { (luminance + (channels[it] - luminance) * retention).toFloat() }
    }

    fun decodeRgb(red: Float, green: Float, blue: Float, strength: Float = 1f,
        profileVersion: Int = CURRENT_VERSION): FloatArray {
        require(profileVersion in LEGACY_VERSION..CURRENT_VERSION)
        return decodeRgbDouble(doubleArrayOf(red.toDouble(), green.toDouble(), blue.toDouble()),
            strength.coerceIn(0f, 1f).toDouble(), profileVersion).map { it.coerceIn(0.0, 1.0).toFloat() }.toFloatArray()
    }

    /**
     * Standard .cube order: red changes fastest, then green, then blue.
     * v2 must use a genuine 3D inverse because chroma mixes the channels.
     * Unclipped table outputs allow interpolation at the valid RGB boundary;
     * clamp after the LUT instead of clipping every table entry prematurely.
     */
    fun decodeCube(size: Int = 33, strength: Float = 1f, profileVersion: Int = CURRENT_VERSION): String {
        require(size in 2..65) { "LUT size must be between 2 and 65" }
        require(strength.isFinite() && strength in 0f..1f) { "Log strength must be between 0 and 1" }
        require(profileVersion in LEGACY_VERSION..CURRENT_VERSION)
        if (profileVersion == CURRENT_VERSION) return decodeCubeV2(size, strength.toDouble())
        val values = FloatArray(size) { decode(it.toFloat() / (size - 1), strength) }
        val rows = Array(size) { String.format(Locale.US, "%.7f", values[it]) }
        return buildString(size * size * size * 31) {
            appendLine("# LumaLog v1 simulated SDR to source SDR; matching strength required")
            appendLine("# Assumed sRGB-like input transfer; no gamut conversion or added dynamic range")
            appendLine(String.format(Locale.US, "TITLE \"LumaLog v1 to SDR %.0f%%\"", strength * 100f))
            appendLine("LUT_3D_SIZE $size")
            appendLine("DOMAIN_MIN 0.0 0.0 0.0")
            appendLine("DOMAIN_MAX 1.0 1.0 1.0")
            for (blue in 0 until size) for (green in 0 until size) for (red in 0 until size) {
                append(rows[red]); append(' '); append(rows[green]); append(' '); appendLine(rows[blue])
            }
        }
    }

    private fun decodeCubeV2(size: Int, amount: Double): String {
        // A dense 1D cache avoids millions of bisections on low-cost devices.
        // It caches only the scalar inverse; every LUT row still unmixes RGB.
        val tableSize = 16385
        val minimum = -.7
        val maximum = 1.7
        val table = DoubleArray(tableSize) { decodeExtended(minimum + (maximum - minimum) * it / (tableSize - 1), amount) }
        fun cached(value: Double): Double {
            val coordinate = ((value - minimum) / (maximum - minimum) * (tableSize - 1)).coerceIn(0.0, (tableSize - 1).toDouble())
            val low = coordinate.toInt().coerceAtMost(tableSize - 2)
            val fraction = coordinate - low
            return table[low] * (1.0 - fraction) + table[low + 1] * fraction
        }
        val retention = 1.0 - CHROMA_COMPRESSION * amount
        return buildString(size * size * size * 34) {
            appendLine("# LumaLog v2 chroma-compressed simulated SDR to source SDR")
            appendLine("# Use matching version/strength; clamp output AFTER LUT; no gamut conversion")
            appendLine(String.format(Locale.US, "TITLE \"LumaLog v2 to SDR %.0f%%\"", amount * 100.0))
            appendLine("LUT_3D_SIZE $size")
            appendLine("DOMAIN_MIN 0.0 0.0 0.0")
            appendLine("DOMAIN_MAX 1.0 1.0 1.0")
            for (blue in 0 until size) for (green in 0 until size) for (red in 0 until size) {
                val r = red.toDouble() / (size - 1)
                val g = green.toDouble() / (size - 1)
                val b = blue.toDouble() / (size - 1)
                val luminance = r * weights[0] + g * weights[1] + b * weights[2]
                appendLine(String.format(Locale.US, "%.7f %.7f %.7f",
                    cached(luminance + (r - luminance) / retention),
                    cached(luminance + (g - luminance) / retention),
                    cached(luminance + (b - luminance) / retention)))
            }
        }
    }

    private fun decodeRgbDouble(channels: DoubleArray, amount: Double, version: Int): DoubleArray {
        val luminance = channels.indices.sumOf { channels[it] * weights[it] }
        val retention = if (version == CURRENT_VERSION) 1.0 - CHROMA_COMPRESSION * amount else 1.0
        return DoubleArray(3) { decodeExtended(luminance + (channels[it] - luminance) / retention, amount) }
    }

    private fun decodeExtended(value: Double, amount: Double): Double {
        if (amount == 0.0) return value
        val floor = BLACK * amount
        val ceiling = 1.0 - amount + WHITE * amount
        // Smooth linear continuations keep inverse LUT interpolation stable
        // around valid gamut edges, without inventing recoverable sensor detail.
        if (value <= floor) {
            val slope = 1.0 - amount + amount * (WHITE - BLACK) * K / (12.92 * denominator)
            return (value - floor) / slope
        }
        if (value >= ceiling) {
            val slope = 1.0 - amount + amount * (WHITE - BLACK) * K * 2.4 / ((1.0 + K) * 1.055 * denominator)
            return 1.0 + (value - ceiling) / slope
        }
        if (amount == 1.0) {
            val normalized = (value - BLACK) / (WHITE - BLACK)
            return srgbEncode(((1.0 + K).pow(normalized) - 1.0) / K)
        }
        var low = 0.0
        var high = 1.0
        repeat(28) {
            val middle = (low + high) * .5
            if (encodeDouble(middle, amount) < value) low = middle else high = middle
        }
        return (low + high) * .5
    }

    private fun encodeDouble(value: Double, amount: Double): Double {
        val linear = if (value <= .04045) value / 12.92 else ((value + .055) / 1.055).pow(2.4)
        val encoded = BLACK + (WHITE - BLACK) * ln(1.0 + K * linear) / denominator
        return value * (1.0 - amount) + encoded * amount
    }

    private fun srgbEncode(linear: Double): Double =
        if (linear <= .0031308) 12.92 * linear else 1.055 * linear.pow(1.0 / 2.4) - .055
}
