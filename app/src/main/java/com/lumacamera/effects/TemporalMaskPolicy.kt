package com.lumacamera.effects

import com.lumacamera.core.ObjectMaskPolicy
import kotlin.math.abs
import kotlin.math.exp

/** A bounded, time-based filter. It stabilizes confidence without inventing motion or object identity. */
class TemporalMaskPolicy(private val kind: Kind) {
    enum class Kind { PERSON, OBJECT }
    data class Output(val mask: PortraitMaskPolicy.Mask, val subjectPresent: Boolean, val historyReset: Boolean)
    private data class Geometry(val area: Int, val x: Float, val y: Float)

    private var generation = Long.MIN_VALUE
    private var previous: PortraitMaskPolicy.Mask? = null
    private var previousGeometry: Geometry? = null
    private var previousScene: FloatArray? = null
    private var present = false

    /** Call on the single inference-completion thread. A generation change discards all old history. */
    fun process(
        incoming: PortraitMaskPolicy.Mask,
        activeGeneration: Long,
        stability: Float = DEFAULT_STABILITY,
        sceneSignature: FloatArray? = null
    ): Output {
        require(incoming.width > 0 && incoming.height > 0 &&
            incoming.width.toLong() * incoming.height == incoming.confidence.size.toLong())
        val tuning = normalizeStability(stability)
        val current = incoming.copy(confidence = FloatArray(incoming.confidence.size) { index ->
            incoming.confidence[index].let { if (it.isFinite()) it.coerceIn(0f, 1f) else 0f }
        })
        val old = previous
        val elapsedMs = if (old == null) 0L else current.capturedAtMs - old.capturedAtMs
        val intervalLimit = if (kind == Kind.PERSON) 900L else 2_000L
        val contextReset = generation != activeGeneration || old == null || old.width != current.width ||
            old.height != current.height || elapsedMs <= 0L || elapsedMs > intervalLimit ||
            sceneChanged(previousScene, sceneSignature)
        var reset = contextReset
        var moving = false
        val geometry = geometry(current)
        val oldGeometry = previousGeometry
        if (!reset && oldGeometry != null && oldGeometry.area > 0 && geometry.area > 0) {
            val areaRatio = geometry.area.toFloat() / oldGeometry.area
            val dx = geometry.x - oldGeometry.x
            val dy = geometry.y - oldGeometry.y
            // A moving soft edge must follow the current mask rather than drag confidence
            // from the subject's former position. Do not predict motion without evidence.
            moving = dx * dx + dy * dy > .004f * .004f || areaRatio !in .92f..1.08f
            if (areaRatio !in .35f..2.8f || dx * dx + dy * dy > .13f * .13f) reset = true
        }
        if (!reset && old != null) {
            var strongChanges = 0
            var boundaryChanges = 0
            for (index in current.confidence.indices) {
                val before = old.confidence[index]
                val after = current.confidence[index]
                if ((before >= .75f && after <= .25f) || (before <= .25f && after >= .75f)) strongChanges++
                if ((before >= .5f && after <= .35f) || (before <= .35f && after >= .5f)) boundaryChanges++
            }
            if (strongChanges.toFloat() / current.confidence.size > .18f) reset = true
            // Hands and two people moving in opposite directions can retain the same global
            // center/area. Strong local boundary crossings are motion; tiny confidence jitter is not.
            if (boundaryChanges >= maxOf(2, (current.confidence.size * .001f).toInt())) moving = true
        }
        val subjectPresent = current.capturedAtMs >= 0L && when (kind) {
            Kind.PERSON -> PortraitMaskPolicy.hasPerson(current, wasPresent = present && !contextReset && tuning > 0f)
            Kind.OBJECT -> ObjectMaskPolicy.hasObject(current)
        }
        generation = activeGeneration
        previousScene = sceneSignature?.copyOf()
        if (!subjectPresent) {
            // A genuinely empty/invalid result clears immediately. No repeated old silhouette or new timestamp.
            previous = null
            previousGeometry = null
            present = false
            return Output(current.copy(confidence = FloatArray(current.confidence.size)), false, true)
        }
        val filtered = if (reset || tuning == 0f || old == null) current else {
            // A time constant keeps the visual response comparable at 3, 5 and 8 analysis frames per second.
            val timeConstantMs = 100f + tuning * if (kind == Kind.PERSON) 400f else 700f
            val baseAlpha = (1f - exp(-elapsedMs / timeConstantMs)).coerceIn(.08f, 1f)
            val confidence = FloatArray(current.confidence.size) { index ->
                val before = old.confidence[index]
                val after = current.confidence[index]
                val hardSwitch = (before >= .65f && after <= .25f) || (before <= .25f && after >= .65f)
                val alpha = when {
                    hardSwitch -> 1f
                    moving && abs(after - before) >= .04f -> maxOf(baseAlpha, .85f)
                    abs(after - before) >= .25f && (after >= .8f || after <= .15f) -> maxOf(baseAlpha, .85f)
                    else -> baseAlpha
                }
                val value = before + (after - before) * alpha
                // Object validity/seed tracking are based on the current raw mask, never a retained old core.
                if (kind == Kind.OBJECT && after >= .65f) maxOf(value, .65f) else value
            }
            current.copy(confidence = confidence)
        }
        val safe = if (kind == Kind.OBJECT && !ObjectMaskPolicy.hasObject(filtered)) current else filtered
        previous = safe
        previousGeometry = geometry
        present = true
        return Output(safe, true, reset)
    }

    /** Only the inference thread resets filter state; wrappers invalidate by advancing their generation. */
    fun reset() {
        generation = Long.MIN_VALUE
        previous = null
        previousGeometry = null
        previousScene = null
        present = false
    }

    private fun geometry(mask: PortraitMaskPolicy.Mask): Geometry {
        var count = 0
        var weightSum = 0.0
        var x = 0.0
        var y = 0.0
        for (index in mask.confidence.indices) {
            val weight = mask.confidence[index]
            if (weight < .45f) continue
            count++
            weightSum += weight
            x += ((index % mask.width + .5) / mask.width) * weight
            y += ((index / mask.width + .5) / mask.height) * weight
        }
        return if (count == 0) Geometry(0, .5f, .5f)
        else Geometry(count, (x / weightSum).toFloat(), (y / weightSum).toFloat())
    }

    private fun sceneChanged(previous: FloatArray?, current: FloatArray?): Boolean {
        if (previous == null || current == null || previous.size != current.size || current.isEmpty()) return false
        var difference = 0f
        for (index in current.indices) {
            if (!previous[index].isFinite() || !current[index].isFinite()) return true
            difference += abs(current[index] - previous[index])
        }
        return difference / current.size > .20f
    }

    companion object {
        const val DEFAULT_STABILITY = .65f
        fun normalizeStability(value: Float): Float = if (value.isFinite()) value.coerceIn(0f, 1f) else DEFAULT_STABILITY

        /** Forty-eight RGB samples from bytes already read for inference; no extra GPU readback. */
        fun sceneSignature(argb: IntArray, width: Int, height: Int): FloatArray {
            require(width > 0 && height > 0 && width.toLong() * height == argb.size.toLong())
            val signature = FloatArray(8 * 6 * 3)
            var cursor = 0
            for (y in 0 until 6) for (x in 0 until 8) {
                val px = ((x + .5f) * width / 8).toInt().coerceAtMost(width - 1)
                val py = ((y + .5f) * height / 6).toInt().coerceAtMost(height - 1)
                val color = argb[py * width + px]
                signature[cursor++] = ((color ushr 16) and 255) / 255f
                signature[cursor++] = ((color ushr 8) and 255) / 255f
                signature[cursor++] = (color and 255) / 255f
            }
            return signature
        }
    }
}
