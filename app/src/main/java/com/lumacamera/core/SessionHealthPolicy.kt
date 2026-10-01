package com.lumacamera.core

/** Advisory readings are never authority to change the user's recording settings. */
object SessionHealthPolicy {
    fun batteryPercent(level: Int?, scale: Int?): Int? {
        if (level == null || scale == null || scale <= 0 || level !in 0..scale) return null
        return (level.toLong() * 100L / scale).toInt()
    }

    fun batteryTemperature(tenthsCelsius: Int?): Float? = tenthsCelsius?.let {
        (it / 10f).takeIf { celsius -> celsius in -20f..80f }
    }

    fun thermalStatus(raw: Int?): Int? = raw?.takeIf { it in 0..6 }

    fun thermalLabel(raw: Int?): String = when (thermalStatus(raw)) {
        0 -> "Sem alerta térmico informado"
        1 -> "Aquecimento leve"
        2 -> "Aquecimento moderado"
        3 -> "Aquecimento alto · desempenho limitado"
        4 -> "Aquecimento muito alto"
        5 -> "Aquecimento crítico"
        6 -> "Sistema prestes a desligar por calor"
        else -> "Estado térmico indisponível"
    }

    fun guidance(raw: Int?): String? = when (thermalStatus(raw)) {
        2 -> "Confira se há quedas de fluidez antes de um clipe longo."
        3, 4, 5, 6 -> "Finalize o clipe e deixe o aparelho esfriar antes de continuar."
        else -> null
    }
}
