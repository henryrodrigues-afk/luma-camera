package com.lumacamera.effects

import java.nio.ByteBuffer
import kotlin.math.roundToInt

/** Bounded Adobe/Resolve 3D .cube reader. Display SDR output is clamped to 8-bit, never baked into MP4. */
class CubeLut private constructor(
    val size: Int,
    val title: String,
    val domainMin: FloatArray,
    val domainMax: FloatArray,
    private val values: FloatArray
) {
    val atlasWidth: Int get() = size * size
    val atlasHeight: Int get() = size

    /** Red changes fastest in .cube; each blue slice occupies one horizontal atlas tile. */
    fun atlasRgba(): ByteBuffer = ByteBuffer.allocateDirect(size * size * size * 4).apply {
        for (green in 0 until size) for (blue in 0 until size) for (red in 0 until size) {
            val source = ((blue * size + green) * size + red) * 3
            for (channel in 0..2) put((values[source + channel].coerceIn(0f, 1f) * 255f).roundToInt().toByte())
            put(255.toByte())
        }
        flip()
    }

    companion object {
        const val MAX_TEXT_BYTES = 4 * 1024 * 1024
        const val MAX_TITLE_LENGTH = 80

        fun parse(text: String): CubeLut {
            require(text.length <= MAX_TEXT_BYTES && text.toByteArray(Charsets.UTF_8).size <= MAX_TEXT_BYTES) {
                "LUT maior que 4 MB"
            }
            var size = 0
            var title = "LUT importada"
            var min = floatArrayOf(0f, 0f, 0f)
            var max = floatArrayOf(1f, 1f, 1f)
            var hasMin = false; var hasMax = false; var hasTitle = false
            var values: FloatArray? = null
            var count = 0
            fun triple(parts: List<String>, start: Int): FloatArray {
                require(parts.size == start + 3) { "Linha da LUT deve ter três valores" }
                return FloatArray(3) { channel ->
                    val value = parts[start + channel].toFloatOrNull()
                    require(value != null && value.isFinite() && value in -16f..16f) { "Valor inválido na LUT" }
                    value
                }
            }
            for (original in text.removePrefix("\uFEFF").lineSequence()) {
                require(original.length <= 4096) { "Linha da LUT muito longa" }
                val line = original.substringBefore('#').trim()
                if (line.isEmpty()) continue
                val parts = line.split(Regex("\\s+"))
                when (parts[0]) {
                    "TITLE" -> {
                        require(!hasTitle && count == 0) { "Cabeçalho TITLE repetido ou fora de ordem" }
                        hasTitle = true
                        title = line.substringAfter("TITLE").trim().trim('"').filter { !it.isISOControl() }.take(MAX_TITLE_LENGTH)
                            .ifBlank { "LUT importada" }
                    }
                    "LUT_3D_SIZE" -> {
                        require(size == 0 && parts.size == 2) { "Tamanho da LUT inválido ou repetido" }
                        size = parts[1].toIntOrNull() ?: 0
                        require(size == 17 || size == 33) { "Use uma LUT 3D de 17 ou 33 pontos" }
                        values = FloatArray(size * size * size * 3)
                    }
                    "DOMAIN_MIN" -> {
                        require(!hasMin && count == 0) { "DOMAIN_MIN repetido ou fora de ordem" }
                        hasMin = true; min = triple(parts, 1)
                    }
                    "DOMAIN_MAX" -> {
                        require(!hasMax && count == 0) { "DOMAIN_MAX repetido ou fora de ordem" }
                        hasMax = true; max = triple(parts, 1)
                    }
                    "LUT_3D_INPUT_RANGE" -> {
                        require(!hasMin && !hasMax && count == 0 && parts.size == 3) { "Faixa da LUT inválida" }
                        val lo = parts[1].toFloatOrNull(); val hi = parts[2].toFloatOrNull()
                        require(lo != null && hi != null && lo.isFinite() && hi.isFinite() && lo >= -16f && hi <= 16f) {
                            "Faixa da LUT inválida"
                        }
                        min = FloatArray(3) { lo }; max = FloatArray(3) { hi }; hasMin = true; hasMax = true
                    }
                    "LUT_1D_SIZE", "LUT_1D_INPUT_RANGE" -> throw IllegalArgumentException("LUT 1D ou combinada não é compatível; use LUT 3D")
                    else -> {
                        val destination = values ?: throw IllegalArgumentException("Informe LUT_3D_SIZE antes dos dados")
                        val rgb = triple(parts, 0)
                        require(count < size * size * size) { "LUT contém dados em excesso" }
                        rgb.copyInto(destination, count * 3); count++
                    }
                }
            }
            require(size != 0 && count == size * size * size) { "LUT incompleta" }
            require((0..2).all { max[it] - min[it] >= .0001f }) { "Domínio da LUT deve ter máximo maior que mínimo" }
            return CubeLut(size, title, min, max, checkNotNull(values))
        }
    }
}
