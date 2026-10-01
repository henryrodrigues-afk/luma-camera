package com.lumacamera.effects

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.SystemClock
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.components.containers.NormalizedKeypoint
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.interactivesegmenter.InteractiveSegmenter
import com.lumacamera.core.ObjectMaskPolicy
import com.lumacamera.core.ObjectTrackingPolicy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** Offline MagicTouch v1. Synchronous MediaPipe inference runs on one CPU thread, never the GPU worker. */
class ObjectSegmenter(
    context: Context,
    private val worker: Handler,
    private val rotation: Int,
    private val onMask: (PortraitMaskPolicy.Mask) -> Unit,
    private val onFailure: (Exception) -> Unit
) {
    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor { job ->
        Thread(job, "LumaObjectMask").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    // client and assisted-seed state belong exclusively to the inference thread.
    private var client: InteractiveSegmenter? = null
    private var trackingGeneration = -1L
    private var selectionRevision = Long.MIN_VALUE
    private var requestedPoint: ObjectMaskPolicy.Point? = null
    private var trackedPoint: ObjectMaskPolicy.Point? = null
    private var previousMask: PortraitMaskPolicy.Mask? = null
    private val visualTracking = ObjectTrackingPolicy()
    private val temporal = TemporalMaskPolicy(TemporalMaskPolicy.Kind.OBJECT)
    @Volatile private var stability = TemporalMaskPolicy.DEFAULT_STABILITY
    @Volatile private var closed = false
    @Volatile private var generation = 0L
    @Volatile private var selectionLost = false
    // Scheduling state belongs to the capture worker.
    private var busy = false
    private var lastSubmittedAt = Long.MIN_VALUE
    private var lastFailureAt = Long.MIN_VALUE

    fun updateTuning(stability: Float) { this.stability = TemporalMaskPolicy.normalizeStability(stability) }

    /** Call before GPU readback. There is no queue of old camera frames and at most three submissions/sec. */
    fun canSubmit(nowMs: Long): Boolean = !closed && !busy && !selectionLost &&
        (lastSubmittedAt == Long.MIN_VALUE || nowMs - lastSubmittedAt >= 334L) &&
        (lastFailureAt == Long.MIN_VALUE || nowMs - lastFailureAt >= 3_000L)

    fun submit(
        rgbaBottomLeft: ByteBuffer, width: Int, height: Int, capturedAtMs: Long,
        seedSourceGlX: Float, seedSourceGlY: Float, selectionRevision: Long = 0L
    ) {
        if (closed || busy || selectionLost) return
        val prompt = ObjectMaskPolicy.uprightPoint(seedSourceGlX, seedSourceGlY, rotation) ?: return
        busy = true
        lastSubmittedAt = capturedAtMs
        val activeGeneration = generation
        val frameStability = stability
        try {
            require(width > 0 && height > 0 && width.toLong() * height * 4 <= rgbaBottomLeft.limit())
            // The GLES readback buffer is reused by the next frame. Own these bytes before leaving its thread.
            val pixels = ByteArray(width * height * 4)
            rgbaBottomLeft.duplicate().apply { position(0) }.get(pixels)
            executor.execute {
                if (closed || activeGeneration != generation) {
                    deliver(activeGeneration, null)
                    return@execute
                }
                val outcome = runCatching {
                    val requested = ObjectMaskPolicy.Point(seedSourceGlX, seedSourceGlY)
                    if (trackingGeneration != activeGeneration || this.selectionRevision != selectionRevision ||
                        requestedPoint != requested) {
                        trackingGeneration = activeGeneration
                        this.selectionRevision = selectionRevision
                        requestedPoint = requested
                        trackedPoint = requested
                        previousMask = null
                        visualTracking.reset()
                        temporal.reset()
                    }
                    val visualFrame = ObjectTrackingPolicy.frame(pixels, width, height, capturedAtMs)
                    val hadReference = visualTracking.initialized
                    val prediction = if (hadReference) visualTracking.predict(visualFrame) else null
                    if (hadReference && prediction?.point == null) {
                        // No inference from an obsolete point after occlusion, ambiguity or a large gap.
                        // deliver() latches the lost selection only after checking this generation.
                        previousMask = null
                        visualTracking.reset()
                        temporal.reset()
                        return@runCatching PortraitMaskPolicy.Mask(width, height, FloatArray(width * height), capturedAtMs)
                    }
                    val seed = prediction?.point ?: trackedPoint ?: requested
                    val uprightSeed = if (seed == requested) prompt else
                        requireNotNull(ObjectMaskPolicy.uprightPoint(seed.x, seed.y, rotation))
                    val frame = infer(pixels, width, height, capturedAtMs, uprightSeed)
                    val selected = ObjectMaskPolicy.selectedComponent(frame.mask, seed)
                    val nextSeed = ObjectMaskPolicy.trackingPoint(selected, seed, previousMask)
                    val confirmedSeed = nextSeed?.let {
                        visualTracking.confirm(visualFrame, if (hadReference) seed else it, selected)
                    }
                    if (confirmedSeed == null) {
                        previousMask = null
                        trackedPoint = requested
                        visualTracking.reset()
                        temporal.process(ObjectMaskPolicy.empty(selected), activeGeneration,
                            frameStability, frame.sceneSignature).mask
                    } else {
                        trackedPoint = confirmedSeed
                        previousMask = selected
                        temporal.process(selected, activeGeneration, frameStability, frame.sceneSignature).mask
                    }
                }
                if (outcome.isFailure) {
                    runCatching { client?.close() }
                    client = null
                    previousMask = null
                    visualTracking.reset()
                    trackedPoint = requestedPoint
                    temporal.reset()
                }
                deliver(activeGeneration, outcome)
            }
        } catch (error: Exception) {
            busy = false
            lastFailureAt = SystemClock.elapsedRealtime()
            if (!closed && activeGeneration == generation) {
                selectionLost = true
                onMask(emptyLossMask(capturedAtMs))
                onFailure(error)
            }
        }
    }

    private fun infer(
        rgba: ByteArray, width: Int, height: Int, capturedAtMs: Long,
        seed: ObjectMaskPolicy.Point
    ): InferenceFrame {
        var bitmap: Bitmap? = null
        var image: MPImage? = null
        var masks: List<MPImage> = emptyList()
        var category: MPImage? = null
        try {
            val argb = IntArray(width * height)
            for (y in 0 until height) for (x in 0 until width) {
                val offset = ((height - 1 - y) * width + x) * 4
                val red = rgba[offset].toInt() and 255
                val green = rgba[offset + 1].toInt() and 255
                val blue = rgba[offset + 2].toInt() and 255
                argb[y * width + x] = (255 shl 24) or (red shl 16) or (green shl 8) or blue
            }
            val upright = PortraitMaskPolicy.rotateTopLeftArgb(argb, width, height, rotation)
            val inputBitmap = Bitmap.createBitmap(upright.argb, upright.width, upright.height, Bitmap.Config.ARGB_8888)
            bitmap = inputBitmap
            val inputImage = BitmapImageBuilder(inputBitmap).build()
            image = inputImage
            val detector = client ?: InteractiveSegmenter.createFromOptions(appContext,
                InteractiveSegmenter.InteractiveSegmenterOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL_ASSET).build())
                    .setOutputConfidenceMasks(true)
                    .setOutputCategoryMask(false)
                    .build()
            ).also { client = it }
            val result = detector.segment(inputImage, InteractiveSegmenter.RegionOfInterest.create(
                NormalizedKeypoint.create(seed.x, seed.y)
            ))
            masks = result.confidenceMasks().orElse(emptyList())
            category = result.categoryMask().orElse(null)
            val foreground = masks[ObjectMaskPolicy.foregroundMaskIndex(masks.size)]
            val floatBuffer = ByteBufferExtractor.extract(foreground).duplicate()
                .order(ByteOrder.nativeOrder()).apply { rewind() }.asFloatBuffer()
            val pixelCount = foreground.width * foreground.height
            check(foreground.width > 0 && foreground.height > 0 && floatBuffer.remaining() >= pixelCount) {
                "Object mask has an invalid buffer"
            }
            val confidence = FloatArray(pixelCount)
            floatBuffer.get(confidence)
            val mask = PortraitMaskPolicy.alignToSource(confidence, foreground.width, foreground.height,
                width, height, rotation, capturedAtMs)
            return InferenceFrame(mask, TemporalMaskPolicy.sceneSignature(argb, width, height))
        } finally {
            // All task output images own native storage; copy confidence before closing them.
            masks.forEach { runCatching { it.close() } }
            if (category != null && masks.none { it === category }) runCatching { category?.close() }
            runCatching { image?.close() }
            bitmap?.recycle()
        }
    }

    private data class InferenceFrame(val mask: PortraitMaskPolicy.Mask, val sceneSignature: FloatArray)

    private fun deliver(activeGeneration: Long, outcome: Result<PortraitMaskPolicy.Mask>?) {
        if (closed) return
        worker.post {
            busy = false // Also release scheduling after a discarded generation.
            if (closed || activeGeneration != generation || outcome == null) return@post
            outcome.fold({ mask ->
                // The model has no object identity. Once lost, require a new explicit selection.
                // Set this only on the capture worker after the generation guard, never from an old CPU job.
                selectionLost = !ObjectMaskPolicy.hasObject(mask)
                onMask(mask)
            }) { error ->
                selectionLost = true // An inference failure must not reacquire another object at the old tap.
                onMask(emptyLossMask(SystemClock.elapsedRealtime()))
                lastFailureAt = SystemClock.elapsedRealtime()
                onFailure(error as? Exception ?: IllegalStateException(error))
            }
        }
    }

    /** New taps/modes discard pending results and allow a fresh selection after a lost target. */
    fun invalidate() {
        generation++
        selectionLost = false
    }

    fun close() {
        if (closed) return
        closed = true
        generation++
        // Queued after the single in-flight task, so close never races with segment().
        try {
            executor.execute {
                runCatching { client?.close() }
                client = null
                previousMask = null
                visualTracking.reset()
            }
        } catch (_: RejectedExecutionException) {
            // A prior close already scheduled native cleanup.
        } finally {
            executor.shutdown()
        }
    }

    companion object { const val MODEL_ASSET = "models/magic_touch_v1.tflite" }
    private fun emptyLossMask(capturedAtMs: Long) = PortraitMaskPolicy.Mask(1, 1, floatArrayOf(0f), capturedAtMs)
}
