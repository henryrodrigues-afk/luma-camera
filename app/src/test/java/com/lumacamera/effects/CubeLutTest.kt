package com.lumacamera.effects

import org.junit.Assert.*
import org.junit.Test

class CubeLutTest {
    private fun identity(size: Int, header: String = "") = buildString {
        append("TITLE \"Identity $size\"\nLUT_3D_SIZE $size\n$header")
        for (blue in 0 until size) for (green in 0 until size) for (red in 0 until size)
            append("${red.toFloat() / (size - 1)} ${green.toFloat() / (size - 1)} ${blue.toFloat() / (size - 1)}\n")
    }

    @Test fun valid17And33BuildHorizontalSlicesWithRedFastestAndGreenRows() {
        for (size in listOf(17, 33)) {
            val lut = CubeLut.parse(identity(size))
            assertEquals(size, lut.size); assertEquals(size * size, lut.atlasWidth); assertEquals(size, lut.atlasHeight)
            val pixels = lut.atlasRgba()
            fun rgb(x: Int, y: Int) = (0..2).map { pixels.get((y * lut.atlasWidth + x) * 4 + it).toInt() and 255 }
            assertEquals(listOf(0, 0, 0), rgb(0, 0))
            assertEquals(listOf(255, 0, 0), rgb(size - 1, 0))
            assertEquals(listOf(0, 0, 255), rgb((size - 1) * size, 0))
            assertEquals(listOf(255, 255, 255), rgb(lut.atlasWidth - 1, size - 1))
        }
    }
    @Test fun domainsAndResolveInputRangeAreRespectedAndCommentsAllowed() {
        val domain = CubeLut.parse(identity(17, "DOMAIN_MIN -1 0 .1\nDOMAIN_MAX 1 2 .9\n# comment\n"))
        assertArrayEquals(floatArrayOf(-1f, 0f, .1f), domain.domainMin, 0f)
        assertArrayEquals(floatArrayOf(1f, 2f, .9f), domain.domainMax, 0f)
        val range = CubeLut.parse("\uFEFF" + identity(17, "LUT_3D_INPUT_RANGE -1 2\n"))
        assertArrayEquals(floatArrayOf(-1f, -1f, -1f), range.domainMin, 0f)
        assertArrayEquals(floatArrayOf(2f, 2f, 2f), range.domainMax, 0f)
    }
    @Test fun sdrPreviewOutputClampsExtendedCubeValuesWithoutOverflow() {
        val lut = CubeLut.parse(identity(17).replaceFirst("0.0 0.0 0.0", "-1 2 .5"))
        val pixels = lut.atlasRgba()
        assertEquals(0, pixels.get(0).toInt() and 255); assertEquals(255, pixels.get(1).toInt() and 255)
        assertEquals(128, pixels.get(2).toInt() and 255)
    }
    @Test fun unsupportedPartialExcessNonFiniteAndInvalidDomainsFailClearly() {
        val valid = identity(17)
        val invalid = listOf("LUT_1D_SIZE 17", "LUT_3D_SIZE 65", "LUT_3D_SIZE 17\n0 0 0",
            valid + "0 0 0\n", valid.replaceFirst("0.0 0.0 0.0", "NaN 0 0"),
            valid.replaceFirst("0.0 0.0 0.0", "Infinity 0 0"),
            identity(17, "DOMAIN_MIN 1 1 1\n"), identity(17, "DOMAIN_MAX 0 0 0\n"),
            identity(17, "LUT_3D_SIZE 17\n"), identity(17, "DOMAIN_MIN 0 0 0\nDOMAIN_MIN 0 0 0\n"))
        invalid.forEach { text -> assertThrows(IllegalArgumentException::class.java) { CubeLut.parse(text) } }
    }
    @Test fun unreasonableFileAndLineLengthsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { CubeLut.parse("#".repeat(CubeLut.MAX_TEXT_BYTES + 1)) }
        assertThrows(IllegalArgumentException::class.java) { CubeLut.parse("#".repeat(4097)) }
    }
}
