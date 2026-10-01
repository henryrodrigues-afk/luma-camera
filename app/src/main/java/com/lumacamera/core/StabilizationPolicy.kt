package com.lumacamera.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/** Scene displacement between images, in source GLES coordinates (bottom-left origin). */
data class FrameMotionEstimate(
    val deltaX: Float = 0f,
    val deltaY: Float = 0f,
    val confidence: Float = 0f,
    val valid: Boolean = false,
    val reset: Boolean = false,
    val reason: String = "first_frame",
    // Positive CCW scene motion, measured in physical coordinates (aspect * x, y).
    val rotationRadians: Float = 0f
)

data class StabilizationTuning(
    val enabled: Boolean = true,
    val intensity: Float = .65f,
    val response: Float = .5f,
    val cropZoom: Float = 1.12f,
    val mode: Int = 0,
    val horizonCorrection: Boolean = true,
    val aspectRatio: Float = 1f
)

data class StabilizationTransform(
    val zoom: Float = 1f,
    val centerOffsetX: Float = 0f,
    val centerOffsetY: Float = 0f,
    val confidence: Float = 0f,
    val tracking: Boolean = false,
    val reason: String = "disabled",
    val rotationRadians: Float = 0f,
    val aspectRatio: Float = 1f
) {
    /** Output UV to source UV, exactly matching the copy sampler before display rotation/mirror. */
    fun sourcePoint(x: Float, y: Float): Pair<Float, Float> {
        val geometry = safeGeometry()
        val px = ((if (x.isFinite()) x.coerceIn(0f, 1f) else .5f) - .5f) * geometry.aspect
        val py = (if (y.isFinite()) y.coerceIn(0f, 1f) else .5f) - .5f
        val c = cos(geometry.angle); val s = sin(geometry.angle)
        return (.5f + (c * px - s * py) / (geometry.zoom * geometry.aspect) + geometry.x).coerceIn(0f, 1f) to
            (.5f + (s * px + c * py) / geometry.zoom + geometry.y).coerceIn(0f, 1f)
    }

    /** Inverse sampler mapping. A tracked subject outside the cropped view must not appear on its edge. */
    fun outputPoint(x: Float, y: Float): Pair<Float, Float>? {
        if (!x.isFinite() || !y.isFinite() || x !in 0f..1f || y !in 0f..1f) return null
        val geometry = safeGeometry()
        val px = (x - .5f - geometry.x) * geometry.aspect
        val py = y - .5f - geometry.y
        val c = cos(geometry.angle); val s = sin(geometry.angle)
        val u = .5f + geometry.zoom * (c * px + s * py) / geometry.aspect
        val v = .5f + geometry.zoom * (-s * px + c * py)
        if (u < -.00001f || u > 1.00001f || v < -.00001f || v > 1.00001f) return null
        return u.coerceIn(0f, 1f) to v.coerceIn(0f, 1f)
    }

    private data class Geometry(val zoom: Float, val aspect: Float, val angle: Float, val x: Float, val y: Float)
    private fun safeGeometry(): Geometry {
        val z = StabilizationGeometry.zoom(zoom)
        val aspect = StabilizationGeometry.aspect(aspectRatio)
        val maximum = StabilizationGeometry.maximumRotation(z, aspect)
        val angle = if (rotationRadians.isFinite()) rotationRadians.coerceIn(-maximum, maximum) else 0f
        val margins = StabilizationGeometry.margins(z, angle, aspect)
        val dx = if (centerOffsetX.isFinite()) centerOffsetX.coerceIn(-margins.first, margins.first) else 0f
        val dy = if (centerOffsetY.isFinite()) centerOffsetY.coerceIn(-margins.second, margins.second) else 0f
        return Geometry(z, aspect, angle, dx, dy)
    }
}

/** Four-corner bounds for the physical-aspect rotation, independent of UI/sensor display orientation. */
object StabilizationGeometry {
    fun zoom(value: Float): Float = if (value.isFinite()) value.coerceIn(1f, 100f) else 1f
    fun aspect(value: Float): Float = if (value.isFinite()) value.coerceIn(.25f, 4f) else 1f

    fun margins(zoom: Float, angle: Float, aspect: Float): Pair<Float, Float> {
        val c = abs(cos(angle)); val s = abs(sin(angle))
        return (.5f - (c + s / aspect) / (2f * zoom)).coerceAtLeast(0f) to
            (.5f - (c + s * aspect) / (2f * zoom)).coerceAtLeast(0f)
    }

    /** Fixed crop: reserve translation space and limit roll instead of enlarging the crop per frame. */
    fun maximumRotation(zoom: Float, aspect: Float, translationFraction: Float = 0f): Float {
        val z = this.zoom(zoom); val a = this.aspect(aspect)
        val reserve = .5f * (1f - 1f / z) * translationFraction.coerceIn(0f, 1f)
        if (z <= 1f) return 0f
        var low = 0f; var high = .12f // Small roll correction, at most about seven degrees.
        repeat(20) {
            val middle = (low + high) * .5f
            val margins = margins(z, middle, a)
            if (margins.first >= reserve + .000001f && margins.second >= reserve + .000001f) low = middle
            else high = middle
        }
        return low
    }
}

/** Causal image-motion filter. No future frames, neural inference or variable crop is required. */
class StabilizationPolicy {
    private var lastAnalysisAt: Long? = null
    private var lastRenderAt: Long? = null
    private var errorX = 0f
    private var errorY = 0f
    private var errorRoll = 0f
    private var displayX = 0f
    private var displayY = 0f
    private var displayRoll = 0f
    private var velocityX = 0f
    private var velocityY = 0f
    private var meanSpeed = 0f
    private var angularVelocity = 0f
    private var meanAngularSpeed = 0f
    private var pan = 0f
    private var rollPan = 0f
    private var validSeconds = 0f
    private var recovery = 0f
    private var confidence = 0f
    private var tracking = false
    private var reason = "disabled"
    private var configuredZoom = 1f
    private var configuredAspect = 1f
    private var configuredHorizon = true
    private var maximumRoll = 0f
    private var translationX = 0f
    private var translationY = 0f
    private var enabled = false

    fun reset() {
        lastAnalysisAt = null; lastRenderAt = null
        clearDynamics()
        configuredZoom = 1f; configuredAspect = 1f
        configuredHorizon = true
        maximumRoll = 0f; translationX = 0f; translationY = 0f
        enabled = false; reason = "disabled"
    }

    fun update(motion: FrameMotionEstimate, timestampMs: Long, tuning: StabilizationTuning) {
        configure(tuning)
        if (!enabled) return
        val previous = lastAnalysisAt
        if (timestampMs < 0L || (previous != null && timestampMs <= previous)) {
            tracking = false; reason = "frame_gap"; return
        }
        val gap = if (previous == null) 100L else timestampMs - previous
        lastAnalysisAt = timestampMs
        val dt = (gap / 1000f).coerceIn(.001f, .5f)
        if (motion.reset && motion.reason == "scene_cut") {
            clearDynamics(); reason = "scene_cut"; return
        }
        if (gap > 500L || motion.reset || !motion.valid || !motion.deltaX.isFinite() || !motion.deltaY.isFinite() ||
            !motion.rotationRadians.isFinite() || !motion.confidence.isFinite() || motion.confidence < .4f ||
            abs(motion.deltaX) > .25f || abs(motion.deltaY) > .25f || abs(motion.rotationRadians) > .25f) {
            loseTracking(dt)
            reason = if (gap > 500L) "frame_gap" else motion.reason
            return
        }
        val strength = bounded(tuning.intensity, 0f, 1f, .65f)
        val response = bounded(tuning.response, 0f, 1f, .5f)
        val mode = tuning.mode.coerceIn(0, 2)
        validSeconds += dt
        confidence = approach(confidence, motion.confidence.coerceIn(0f, 1f), dt, .15f)
        recovery = approach(recovery, 1f, dt, .24f)
        val vx = motion.deltaX * configuredAspect / dt
        val vy = motion.deltaY / dt
        val speed = sqrt(vx * vx + vy * vy)
        velocityX = approach(velocityX, vx, dt, .32f)
        velocityY = approach(velocityY, vy, dt, .32f)
        meanSpeed = approach(meanSpeed, speed, dt, .32f)
        angularVelocity = approach(angularVelocity, motion.rotationRadians / dt, dt, .32f)
        meanAngularSpeed = approach(meanAngularSpeed, abs(motion.rotationRadians / dt), dt, .32f)
        fun coherence(magnitude: Float, mean: Float) = if (mean > .025f && validSeconds > .20f)
            ((magnitude / mean - .55f) / .35f).coerceIn(0f, 1f) else 0f
        val desiredPan = coherence(sqrt(velocityX * velocityX + velocityY * velocityY), meanSpeed)
        val desiredRollPan = coherence(abs(angularVelocity), meanAngularSpeed)
        pan = approach(pan, desiredPan, dt, if (desiredPan > pan) .30f else .45f)
        rollPan = approach(rollPan, desiredRollPan, dt, if (desiredRollPan > rollPan) .30f else .45f)
        val steadyTau = when (mode) {
            1 -> .55f + strength * .28f + (1f - response) * .25f
            2 -> .16f + strength * .12f + (1f - response) * .12f
            else -> .30f + strength * .18f + (1f - response) * .20f
        }
        val panTau = when (mode) {
            1 -> .13f + (1f - response) * .16f
            2 -> .045f + (1f - response) * .075f
            else -> .07f + (1f - response) * .12f
        }
        val tau = steadyTau + (panTau - steadyTau) * pan
        val rollTau = steadyTau + (panTau - steadyTau) * rollPan
        errorX = integrate(errorX, motion.deltaX, dt, tau)
        errorY = integrate(errorY, motion.deltaY, dt, tau)
        errorRoll = if (tuning.horizonCorrection) integrate(errorRoll, motion.rotationRadians, dt, rollTau) else 0f
        val gain = strength * (.5f + .5f * confidence) * recovery
        errorX = antiWindup(errorX, gain, translationX, dt)
        errorY = antiWindup(errorY, gain, translationY, dt)
        errorRoll = antiWindup(errorRoll, gain, maximumRoll, dt)
        tracking = true; reason = motion.reason
    }

    /** Render interpolation follows actual elapsed time; missing analyses gently release the correction. */
    fun transform(timestampMs: Long, tuning: StabilizationTuning): StabilizationTransform {
        configure(tuning)
        if (!enabled) return StabilizationTransform()
        val previous = lastRenderAt
        if (timestampMs < 0L || (previous != null && timestampMs < previous)) {
            tracking = false; reason = "frame_gap"
            return snapshot(0f, false, reason)
        }
        val gap = if (previous == null) 0L else timestampMs - previous
        lastRenderAt = timestampMs
        val dt = (gap / 1000f).coerceIn(0f, if (gap > 250L) .05f else .10f)
        val age = lastAnalysisAt?.let { (timestampMs - it).coerceAtLeast(0L) } ?: Long.MAX_VALUE
        val freshness = if (age <= 250L) 1f else decay((age - 250L) / 1000f, .45f)
        val strength = bounded(tuning.intensity, 0f, 1f, .65f)
        if (strength == 0f) {
            errorX = 0f; errorY = 0f; errorRoll = 0f
            displayX = 0f; displayY = 0f; displayRoll = 0f
            return snapshot(confidence * freshness, tracking && age <= 350L, "zero_intensity")
        }
        val response = bounded(tuning.response, 0f, 1f, .5f)
        val gain = strength * (.5f + .5f * confidence) * recovery * freshness
        val targetX = softLimit(errorX * gain, translationX)
        val targetY = softLimit(errorY * gain, translationY)
        val targetRoll = if (tuning.horizonCorrection) softLimit(errorRoll * gain, maximumRoll) else 0f
        val tau = if (tracking && age <= 250L) .032f + (1f - response) * .04f
            else .12f + (1f - response) * .10f
        val speed = when (tuning.mode.coerceIn(0, 2)) { 1 -> .55f; 2 -> .90f; else -> .70f }
        displayX = interpolate(displayX, targetX, dt, tau, speed / configuredAspect).coerceIn(-translationX, translationX)
        displayY = interpolate(displayY, targetY, dt, tau, speed).coerceIn(-translationY, translationY)
        displayRoll = interpolate(displayRoll, targetRoll, dt, tau, .35f).coerceIn(-maximumRoll, maximumRoll)
        val active = tracking && age <= 350L
        val limited = abs(errorX * gain) > translationX * .90f || abs(errorY * gain) > translationY * .90f ||
            (tuning.horizonCorrection && maximumRoll > 0f && abs(errorRoll * gain) > maximumRoll * .90f)
        val status = when {
            lastAnalysisAt == null -> "first_frame"
            age > 350L -> "frame_gap"
            !tracking -> reason
            recovery < .85f -> "recovering"
            limited -> "crop_limit"
            pan > .55f || rollPan > .55f -> "pan"
            else -> reason
        }
        return snapshot(confidence * freshness, active, status)
    }

    private fun configure(tuning: StabilizationTuning) {
        if (!tuning.enabled) { if (enabled) reset(); return }
        val zoom = bounded(tuning.cropZoom, 1.04f, 1.25f, 1.12f)
        val aspect = StabilizationGeometry.aspect(tuning.aspectRatio)
        val changed = !enabled || zoom != configuredZoom || aspect != configuredAspect
        if (changed) {
            reset(); enabled = true; configuredZoom = zoom; configuredAspect = aspect; reason = "first_frame"
        }
        if (changed || configuredHorizon != tuning.horizonCorrection) {
            configuredHorizon = tuning.horizonCorrection
            // A constant reserve permits both roll and translation without moving the crop edges.
            maximumRoll = if (configuredHorizon) StabilizationGeometry.maximumRotation(zoom, aspect, .68f) else 0f
            val margins = StabilizationGeometry.margins(zoom, maximumRoll, aspect)
            translationX = (margins.first - .000001f).coerceAtLeast(0f)
            translationY = (margins.second - .000001f).coerceAtLeast(0f)
            // The UI changes this geometry only outside REC. Intensity/response never enter this branch.
            displayX = displayX.coerceIn(-translationX, translationX)
            displayY = displayY.coerceIn(-translationY, translationY)
            displayRoll = displayRoll.coerceIn(-maximumRoll, maximumRoll)
            if (!configuredHorizon) errorRoll = 0f
        }
    }

    private fun snapshot(quality: Float, active: Boolean, status: String) = StabilizationTransform(
        configuredZoom, displayX, displayY, quality.coerceIn(0f, 1f), active, status, displayRoll, configuredAspect)

    private fun loseTracking(dt: Float) {
        errorX *= decay(dt, .35f); errorY *= decay(dt, .35f); errorRoll *= decay(dt, .35f)
        confidence *= decay(dt, .25f); recovery *= decay(dt, .20f)
        velocityX *= decay(dt, .25f); velocityY *= decay(dt, .25f); meanSpeed *= decay(dt, .25f)
        angularVelocity *= decay(dt, .25f); meanAngularSpeed *= decay(dt, .25f)
        pan *= decay(dt, .3f); rollPan *= decay(dt, .3f)
        validSeconds = 0f; tracking = false
    }

    private fun clearDynamics() {
        errorX = 0f; errorY = 0f; errorRoll = 0f
        displayX = 0f; displayY = 0f; displayRoll = 0f
        velocityX = 0f; velocityY = 0f; meanSpeed = 0f
        angularVelocity = 0f; meanAngularSpeed = 0f
        pan = 0f; rollPan = 0f; validSeconds = 0f; recovery = 0f
        confidence = 0f; tracking = false
    }

    private fun integrate(error: Float, delta: Float, dt: Float, tau: Float): Float {
        val retain = decay(dt, tau)
        // Exact constant-velocity integration makes the path filter independent of analysis cadence.
        return error * retain + delta * (tau / dt) * (1f - retain)
    }
    private fun antiWindup(error: Float, gain: Float, bound: Float, dt: Float): Float {
        if (gain <= .0001f || bound <= 0f) return 0f
        val raw = error * gain
        val limited = softLimit(raw, bound)
        return (error + (limited - raw) / gain * (1f - decay(dt, .25f)))
            .coerceIn(-3f * bound / gain, 3f * bound / gain)
    }
    private fun softLimit(value: Float, bound: Float) = if (bound > 0f) bound * tanh(value / bound) else 0f
    private fun interpolate(current: Float, target: Float, dt: Float, tau: Float, speed: Float): Float =
        current + ((target - current) * (1f - decay(dt, tau))).coerceIn(-speed * dt, speed * dt)
    private fun approach(current: Float, target: Float, dt: Float, tau: Float) = current + (target - current) * (1f - decay(dt, tau))
    private fun decay(seconds: Float, tau: Float) = exp((-seconds.coerceAtLeast(0f) / tau).toDouble()).toFloat()
    private fun bounded(value: Float, low: Float, high: Float, fallback: Float) = if (value.isFinite()) value.coerceIn(low, high) else fallback
}
