package com.lumacamera.effects

import android.graphics.Bitmap
import android.os.Handler
import android.os.SystemClock
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.Segmenter
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** Bundled on-device model; one asynchronous frame in flight and no camera-frame queue. */
class PortraitSegmenter(
    private val worker: Handler,
    private val rotation: Int,
    private val onMask: (PortraitMaskPolicy.Mask) -> Unit,
    private val onFailure: (Exception) -> Unit
) {
    private val directExecutor = Executor { it.run() }
    private val executor = Executors.newSingleThreadExecutor { job ->
        Thread(job, "LumaPortraitMask").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val temporal = TemporalMaskPolicy(TemporalMaskPolicy.Kind.PERSON)
    @Volatile private var stability = TemporalMaskPolicy.DEFAULT_STABILITY
    @Volatile var subjectPresent: Boolean = false
        private set
    private var client: Segmenter? = null
    private var busy = false
    @Volatile private var closed = false
    @Volatile private var generation = 0L
    private var lastSubmittedAt = Long.MIN_VALUE
    private var lastFailureAt = Long.MIN_VALUE
    private var latencyWindowAt = Long.MIN_VALUE
    private var latencySamples = 0
    private var latencyTotalMs = 0L
    private var latencyMaxMs = 0L
    private var latencyExpired = 0

    fun updateTuning(stability: Float) { this.stability = TemporalMaskPolicy.normalizeStability(stability) }

    /** Called from the capture worker before the GPU readback, so busy frames incur no stall. */
    fun canSubmit(nowMs: Long, quality: Boolean): Boolean = !closed && !busy &&
        (lastSubmittedAt == Long.MIN_VALUE || nowMs - lastSubmittedAt >= submissionIntervalMs(quality)) &&
        (lastFailureAt == Long.MIN_VALUE || nowMs - lastFailureAt >= 3_000L)

    fun submit(rgbaBottomLeft: ByteBuffer, width: Int, height: Int, capturedAtMs: Long) {
        if (closed || busy) return
        busy = true
        lastSubmittedAt = capturedAtMs
        val activeGeneration = generation
        val frameStability = stability
        try {
            require(width > 0 && height > 0 && width.toLong() * height * 4 <= rgbaBottomLeft.limit())
            // Own the small readback before GL reuses its buffer; conversion and bitmap work
            // run away from rendering. busy covers preparation, inference and completion.
            val pixels = ByteArray(width * height * 4)
            rgbaBottomLeft.duplicate().apply { position(0) }.get(pixels)
            executor.execute {
                if (closed || activeGeneration != generation) {
                    deliver(activeGeneration, null)
                    return@execute
                }
                var bitmap: Bitmap? = null
                try {
                    val argb = PortraitPixels.topLeftArgb(pixels, width, height)
                    val upright = PortraitMaskPolicy.rotateTopLeftArgb(argb, width, height, rotation)
                    val sceneSignature = TemporalMaskPolicy.sceneSignature(argb, width, height)
                    val inputBitmap = Bitmap.createBitmap(upright.argb, upright.width, upright.height, Bitmap.Config.ARGB_8888)
                    bitmap = inputBitmap
                    val detector = client ?: Segmentation.getClient(
                        SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.STREAM_MODE)
                            .enableRawSizeMask().build()
                    ).also { client = it }
                    detector.process(InputImage.fromBitmap(inputBitmap, 0)).addOnCompleteListener(directExecutor) { task ->
                        // Always release the bitmap, even when shutdown rejects delivery.
                        val outcome = runCatching {
                            if (closed || activeGeneration != generation) return@runCatching null
                            if (!task.isSuccessful) throw task.exception ?: IllegalStateException("Segmentação não concluída")
                            val mask = task.result
                            require(mask.width > 0 && mask.height > 0 && mask.width.toLong() * mask.height <= 512L * 512L)
                            val buffer = mask.buffer
                            buffer.rewind()
                            val confidence = FloatArray(mask.width * mask.height) { buffer.float }
                            PortraitMaskPolicy.alignRawToSource(confidence, mask.width, mask.height, rotation, capturedAtMs)
                        }
                        inputBitmap.recycle()
                        if (!closed) try {
                            executor.execute completion@ {
                                if (closed || activeGeneration != generation) {
                                    deliver(activeGeneration, null)
                                    return@completion
                                }
                                val filtered = outcome.mapCatching { aligned ->
                                    if (aligned == null) null
                                    else if (PortraitMaskPolicy.freshness(SystemClock.elapsedRealtime(), aligned.capturedAtMs) <= 0f) {
                                        temporal.reset()
                                        TemporalMaskPolicy.Output(emptyMask(aligned.capturedAtMs), false, true)
                                    } else temporal.process(aligned, activeGeneration, frameStability, sceneSignature)
                                }
                                deliver(activeGeneration, filtered)
                            }
                        } catch (_: RejectedExecutionException) { /* close already owns client cleanup. */ }
                    }
                } catch (error: Exception) {
                    bitmap?.recycle()
                    deliver(activeGeneration, Result.failure(error))
                }
            }
        } catch (error: Exception) {
            busy = false
            lastFailureAt = SystemClock.elapsedRealtime()
            subjectPresent = false
            if (!closed && activeGeneration == generation) {
                onMask(emptyMask(capturedAtMs))
                onFailure(error)
            }
        }
    }

    private fun deliver(activeGeneration: Long, outcome: Result<TemporalMaskPolicy.Output?>?) {
        if (closed) return
        worker.post {
            busy = false
            if (closed || activeGeneration != generation) return@post
            outcome?.fold({ result ->
                if (result != null) {
                    val now = SystemClock.elapsedRealtime()
                    val freshness = PortraitMaskPolicy.freshness(now, result.mask.capturedAtMs)
                    subjectPresent = result.subjectPresent && freshness > 0f
                    reportLatency(now, result.mask.capturedAtMs, freshness <= 0f)
                    onMask(result.mask)
                }
            }) { error ->
                subjectPresent = false
                onMask(emptyMask(SystemClock.elapsedRealtime()))
                lastFailureAt = SystemClock.elapsedRealtime()
                onFailure(error as? Exception ?: IllegalStateException(error))
            }
        }
    }

    /** Do not accept an old mask after the user disables the effect. */
    fun invalidate() { generation++; subjectPresent = false }

    fun close() {
        if (closed) return
        closed = true
        generation++
        subjectPresent = false
        try {
            executor.execute { runCatching { client?.close() }; client = null; temporal.reset() }
        } catch (_: RejectedExecutionException) {
            // A prior close already owns cleanup.
        } finally { executor.shutdown() }
    }

    private fun emptyMask(time: Long) = PortraitMaskPolicy.Mask(1, 1, floatArrayOf(0f), time)
    private fun submissionIntervalMs(quality: Boolean) = if (quality) 166L else 125L

    /** Aggregated capture-to-callback age only; never pixels, subjects or their coordinates. */
    private fun reportLatency(now: Long, capturedAtMs: Long, expired: Boolean) {
        if (latencyWindowAt == Long.MIN_VALUE) latencyWindowAt = now
        val age = (now - capturedAtMs).coerceAtLeast(0L)
        latencySamples++
        latencyTotalMs += age
        latencyMaxMs = maxOf(latencyMaxMs, age)
        if (expired) latencyExpired++
        if (now - latencyWindowAt >= 10_000L) {
            android.util.Log.i("LumaMask", "person samples=$latencySamples avgMs=${latencyTotalMs / latencySamples} " +
                "maxMs=$latencyMaxMs expired=$latencyExpired")
            latencyWindowAt = now
            latencySamples = 0; latencyTotalMs = 0L; latencyMaxMs = 0L; latencyExpired = 0
        }
    }
}
