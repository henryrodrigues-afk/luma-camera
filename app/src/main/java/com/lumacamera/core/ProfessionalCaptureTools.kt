package com.lumacamera.core

/** Physical shutter shortcuts. A limited sensor reports the actual clamped exposure to the caller. */
object ProfessionalCaptureTools {
    fun exposureForAngle(degrees: Float, fps: Int, sensorMinNs: Long, sensorMaxNs: Long): Long? {
        if (!degrees.isFinite() || degrees !in 1f..360f || fps !in 1..240 ||
            sensorMinNs <= 0L || sensorMaxNs < sensorMinNs) return null
        val maximum = minOf(sensorMaxNs, 1_000_000_000L / fps)
        if (maximum < sensorMinNs) return null
        return (1_000_000_000.0 / fps * degrees / 360.0).toLong().coerceIn(sensorMinNs, maximum)
    }

    fun angleForExposure(exposureNs: Long, fps: Int): Float? =
        if (exposureNs <= 0L || fps !in 1..240) null else
            (exposureNs / 1_000_000_000.0 * fps * 360.0).toFloat()
}
