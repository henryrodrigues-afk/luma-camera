package com.lumacamera.effects

import org.junit.Assert.*
import org.junit.Test

class SimulatedLogTest {
    @Test fun disabledCurveIsIdentity() {
        for (i in 0..255) {
            val source = i / 255f
            assertEquals(source, SimulatedLog.encode(source, 0f), 0f)
            assertEquals(source, SimulatedLog.decode(source, 0f), 0f)
        }
    }
    @Test fun endpointsLeaveDefinedHeadroomAndNeverClipSdrMidtones() {
        assertEquals(.09375f, SimulatedLog.encode(0f), .000001f)
        assertEquals(.9375f, SimulatedLog.encode(1f), .000001f)
        assertEquals(0f, SimulatedLog.decode(0f), 0f)
        assertEquals(1f, SimulatedLog.decode(1f), .000001f)
    }
    @Test fun curveIsStrictlyMonotonicForEveryStrength() {
        for (amount in listOf(.1f, .35f, .5f, .75f, 1f)) {
            var previous = SimulatedLog.encode(0f, amount)
            for (i in 1..1024) {
                val next = SimulatedLog.encode(i / 1024f, amount)
                assertTrue("strength=$amount step=$i", next > previous)
                previous = next
            }
        }
    }
    @Test fun inverseRecoversSourceAcrossFullAndPartialStrengths() {
        for (amount in listOf(0f, .1f, .35f, .5f, .75f, 1f)) for (i in 0..1024) {
            val source = i / 1024f
            assertEquals(source, SimulatedLog.decode(SimulatedLog.encode(source, amount), amount), .00001f)
        }
    }
    @Test fun quantizedLogAndInverseStayWithinUsefulEightBitTolerance() {
        for (i in 0..255) {
            val source = i / 255f
            val encoded = SimulatedLog.encode(source)
            val quantized = kotlin.math.round(encoded * 255f) / 255f
            assertEquals(source, SimulatedLog.decode(quantized), .006f)
        }
    }
    @Test fun cubeContainsExpectedGridAndChannelOrderWithMatchingStrength() {
        val rows = SimulatedLog.decodeCube(17, .65f, SimulatedLog.LEGACY_VERSION).lineSequence()
            .filter { it.firstOrNull()?.let { character -> character.isDigit() || character == '-' } == true }
            .map { it.trim().split(' ').map(String::toFloat) }.toList()
        assertEquals(17 * 17 * 17, rows.size)
        assertEquals(listOf(0f, 0f, 0f), rows[0])
        assertEquals(SimulatedLog.decode(1f / 16f, .65f), rows[1][0], .000001f)
        assertEquals(0f, rows[1][1], 0f)
        assertEquals(0f, rows[17][0], 0f)
        assertEquals(SimulatedLog.decode(1f / 16f, .65f), rows[17][1], .000001f)
        assertEquals(listOf(1f, 1f, 1f), rows.last())
    }
    @Test fun lutInterpolationRecoversSourceAtThirtyThreeAndSixtyFivePoints() {
        for (size in listOf(33, 65)) for (amount in listOf(.5f, 1f)) for (i in 0..255) {
            val source = i / 255f
            val coordinate = SimulatedLog.encode(source, amount) * (size - 1)
            val low = coordinate.toInt().coerceIn(0, size - 2)
            val fraction = coordinate - low
            val recovered = SimulatedLog.decode(low.toFloat() / (size - 1), amount) * (1f - fraction) +
                SimulatedLog.decode((low + 1).toFloat() / (size - 1), amount) * fraction
            // Uniform cube grids approximate the clamped toe, especially when
            // partial strength moves its boundary between two 33-point samples.
            val tolerance = if (size == 65) .002f else if (amount == 1f) .006f else .012f
            assertEquals(source, recovered, tolerance)
        }
    }

    @Test fun v2CompressesChromaWhileKeepingLogLuminanceAndNeutralColors() {
        val source = floatArrayOf(.88f, .12f, .25f)
        for (strength in listOf(.25f, .5f, 1f)) {
            val v1 = SimulatedLog.encodeRgb(source[0], source[1], source[2], strength, 1)
            val v2 = SimulatedLog.encodeRgb(source[0], source[1], source[2], strength, 2)
            assertEquals(luminance(v1), luminance(v2), .000001f)
            val retention = 1f - SimulatedLog.CHROMA_COMPRESSION * strength
            assertEquals((v1.maxOrNull()!! - v1.minOrNull()!!) * retention,
                v2.maxOrNull()!! - v2.minOrNull()!!, .000001f)
        }
        val neutral = SimulatedLog.encodeRgb(.42f, .42f, .42f)
        assertEquals(neutral[0], neutral[1], 0f)
        assertEquals(neutral[1], neutral[2], 0f)
        assertEquals(SimulatedLog.encode(.42f), neutral[0], .000001f)
    }

    @Test fun v1RgbProfilePreservesTheEarlierScalarCurve() {
        for (strength in listOf(0f, .45f, 1f)) for (source in colorPatches()) {
            val encoded = SimulatedLog.encodeRgb(source[0], source[1], source[2], strength, 1)
            assertArrayEquals(source.map { SimulatedLog.encode(it, strength) }.toFloatArray(), encoded, .000001f)
            assertArrayEquals(source, SimulatedLog.decodeRgb(encoded[0], encoded[1], encoded[2], strength, 1), .00001f)
        }
    }

    @Test fun v2InverseRecoversColorAcrossCompleteAndPartialStrengths() {
        for (strength in listOf(0f, .1f, .35f, .5f, .75f, 1f)) for (source in colorPatches()) {
            val encoded = SimulatedLog.encodeRgb(source[0], source[1], source[2], strength, 2)
            assertTrue(encoded.all { it in 0f..1f })
            val decoded = SimulatedLog.decodeRgb(encoded[0], encoded[1], encoded[2], strength, 2)
            assertArrayEquals(source, decoded, .00001f)
        }
    }

    @Test fun v2InverseRemainsUsefulAfterEightBitColorQuantization() {
        for (strength in listOf(.45f, .75f, 1f)) for (source in colorPatches()) {
            val encoded = SimulatedLog.encodeRgb(source[0], source[1], source[2], strength, 2)
                .map { kotlin.math.round(it * 255f) / 255f }.toFloatArray()
            assertArrayEquals(source, SimulatedLog.decodeRgb(encoded[0], encoded[1], encoded[2], strength, 2), .01f)
        }
    }

    @Test fun v2CubeIsAThreeDimensionalInverseWithUnclippedBoundaryValues() {
        val text = SimulatedLog.decodeCube(33, 1f, 2)
        assertTrue(text.contains("TITLE \"LumaLog v2"))
        val rows = parseCube(text)
        assertEquals(33 * 33 * 33, rows.size)
        assertTrue(rows.any { row -> row.any { it < 0f } })
        assertTrue(rows.any { row -> row.any { it > 1f } })
        assertTrue(rows.all { row -> row.all { it.isFinite() } })
        val center = rows[(16 * 33 + 16) * 33 + 16]
        val lessGreen = rows[(16 * 33 + 15) * 33 + 16]
        // Changing green affects the restored red too: v2 is not three 1D LUTs.
        assertTrue(lessGreen[0] > center[0])
    }

    @Test fun v2ExportedCubeRestoresQuantizedColorWithTrilinearInterpolation() {
        for ((size, strength) in listOf(33 to 1f, 65 to .45f)) {
            val rows = parseCube(SimulatedLog.decodeCube(size, strength, 2))
            for (source in colorPatches()) {
                val encoded = SimulatedLog.encodeRgb(source[0], source[1], source[2], strength, 2)
                    .map { kotlin.math.round(it * 255f) / 255f }.toFloatArray()
                assertArrayEquals(source, sampleCube(rows, size, encoded), .014f)
            }
        }
    }

    private fun luminance(rgb: FloatArray): Float = rgb[0] * .2126f + rgb[1] * .7152f + rgb[2] * .0722f

    private fun colorPatches(): List<FloatArray> = buildList {
        for (blue in 0..8) for (green in 0..8) for (red in 0..8) {
            add(floatArrayOf(red.toFloat(), green.toFloat(), blue.toFloat()).map {
                kotlin.math.round(it / 8f * 255f) / 255f
            }.toFloatArray())
        }
        add(floatArrayOf(.73f, .49f, .38f)); add(floatArrayOf(.19f, .31f, .58f))
    }

    private fun parseCube(text: String): List<FloatArray> = text.lineSequence()
        .filter { it.firstOrNull()?.let { character -> character.isDigit() || character == '-' } == true }
        .map { it.trim().split(' ').map(String::toFloat).toFloatArray() }.toList()

    private fun sampleCube(rows: List<FloatArray>, size: Int, input: FloatArray): FloatArray {
        val coordinates = input.map { it.coerceIn(0f, 1f) * (size - 1) }
        val lows = coordinates.map { it.toInt().coerceAtMost(size - 2) }
        val fractions = coordinates.mapIndexed { i, value -> value - lows[i] }
        val output = FloatArray(3)
        for (blue in 0..1) for (green in 0..1) for (red in 0..1) {
            val weight = (if (red == 1) fractions[0] else 1f - fractions[0]) *
                (if (green == 1) fractions[1] else 1f - fractions[1]) *
                (if (blue == 1) fractions[2] else 1f - fractions[2])
            val row = rows[((lows[2] + blue) * size + lows[1] + green) * size + lows[0] + red]
            for (channel in 0..2) output[channel] += row[channel] * weight
        }
        return output.map { it.coerceIn(0f, 1f) }.toFloatArray()
    }
}
