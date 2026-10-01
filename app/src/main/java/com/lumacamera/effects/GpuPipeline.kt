package com.lumacamera.effects

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.*
import android.os.Handler
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import com.lumacamera.camera.CaptureSettings
import com.lumacamera.core.FrameGeometry
import com.lumacamera.core.RecordingTimeline
import com.lumacamera.core.SubjectFocusPolicy
import com.lumacamera.core.ObjectMaskPolicy
import com.lumacamera.core.StabilizationPolicy
import com.lumacamera.core.StabilizationTransform
import com.lumacamera.core.StabilizationTuning
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.math.cos
import kotlin.math.sin

/** GLES 2 pipeline, owned by the capture worker. One processed frame feeds preview and MP4. */
class GpuPipeline(
    private val context: Context,
    private val worker: Handler,
    private val width: Int,
    private val height: Int,
    private val rotation: Int,
    private val front: Boolean,
    private val onLuminance: (Float) -> Unit,
    private val onError: (Exception) -> Unit,
    private val onPortraitStatus: (String) -> Unit = {},
    private val sensorOrientation: Int = rotation,
    private val onStabilizationStatus: (String) -> Unit = {},
    private val onHistogram: (MonitorHistogram) -> Unit = {},
    private val onScopes: (MonitorScopes) -> Unit = {},
    private val onSubjectTrack: (Pair<Float, Float>?) -> Unit = {}
) {
    private data class Target(val texture: Int, val framebuffer: Int, val width: Int, val height: Int)
    private data class ProgramBindings(val position: Int, val uniforms: MutableMap<String, Int>)
    private var display = EGL14.EGL_NO_DISPLAY
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var anchor = EGL14.EGL_NO_SURFACE
    private var preview = EGL14.EGL_NO_SURFACE
    private var encoder = EGL14.EGL_NO_SURFACE
    private var encoderPhysicallyRotated = true
    private var previewNative: Surface? = null
    private var previewSize = Size(1, 1)
    private var cameraTexture: SurfaceTexture? = null
    private var cameraNative: Surface? = null
    val cameraSurface: Surface get() = checkNotNull(cameraNative)
    private val targets = mutableListOf<Target>()
    private val programs = mutableListOf<Int>()
    private val programBindings = mutableMapOf<Int, ProgramBindings>()
    private var cameraProgram = 0
    private var copyProgram = 0
    private var blurProgram = 0
    private var effectsProgram = 0
    private var externalTexture = 0
    private lateinit var raw: Target
    private lateinit var blurX: Target
    private lateinit var blurY: Target
    private var portraitBlurY: Target? = null
    private lateinit var meter: Target
    private lateinit var portraitFast: Target
    private lateinit var portraitQuality: Target
    private lateinit var objectFast: Target
    private var portraitReadback: ByteBuffer? = null
    private var portraitMaskPixels: ByteBuffer? = null
    private var portraitMaskTexture = 0
    private var uploadedMaskWidth = 0
    private var uploadedMaskHeight = 0
    private var stabilizationFast: Target? = null
    private var stabilizationPrecise: Target? = null
    private var stabilizationPixels: ByteBuffer? = null
    private var stabilizationLuma: FloatArray? = null
    private var stabilizationMaskSnapshot: FloatArray? = null
    private val motionGate = MotionAnalysisGate()
    private val motionExecutor = Executors.newSingleThreadExecutor { task ->
        Thread({
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
            task.run()
        }, "LumaMotion").apply { isDaemon = true }
    }
    private var estimatorGeneration = Long.MIN_VALUE // Accessed only by the serial motion executor.
    private var histogramTarget: Target? = null
    private var histogramPixels: ByteBuffer? = null
    private var lastHistogramTime = 0L
    private var previewLut: CubeLut? = null
    private var previewLutTexture = 0
    private var previewLutDirty = false
    private var lastSubjectTrackTime = 0L
    private val subjectTrackAnalysis = SubjectTrackAnalysis()
    private val motionEstimator = FrameMotionEstimator()
    private val stabilizationPolicy = StabilizationPolicy()
    private var lastMotionTime = 0L
    private var stabilizationStatus = ""
    @Volatile var displayStabilization = StabilizationTransform()
        private set
    private var portraitSegmenter: PortraitSegmenter? = null
    private var objectSegmenter: ObjectSegmenter? = null
    private var portraitMask: PortraitMaskPolicy.Mask? = null
    private var portraitHasSubject = false
    private var portraitMaskPending = false
    private var portraitStatus = ""
    private var portraitFailure = false
    private var focusBackground = 0f
    private var focusDestinationBackground = false
    private var pendingFocusSample = false
    private var lastFocusTime = 0L
    private lateinit var processed: List<Target>
    private var historyIndex = 0
    private var historyValid = false
    private var lastFrameTime: Long? = null
    private var lastMeterTime = 0L
    private var encoderTimeline: RecordingTimeline? = null
    private var settings = CaptureSettings()
    private var effectParams = LocalEffectParams.from(settings)
    private var effectCenter = EffectCoordinates.sourceCenter(effectParams.centerX, effectParams.centerY, rotation, front)
    private var cachedStabilizationTuning = buildStabilizationTuning(settings)
    @Volatile private var closed = false
    private var renderQueued = false
    private val renderLatest = Runnable { renderQueued = false; renderFrame() }
    private val transform = FloatArray(16)
    private val nativeCameraTransform = FloatArray(16)
    private var reportedCameraGeometry = false
    private val previewUvTransform = FrameGeometry.uvTransform(rotation, front)
    private val encoderUvTransform = FrameGeometry.uvTransform(rotation, false)
    private val encodedDimensions = FrameGeometry.orientedSize(width, height, rotation)
    private val rawEncodedDimensions = FrameGeometry.Dimensions(width, height)
    private val surfaceDimensions = IntArray(2)
    private val luminancePixels = ByteBuffer.allocateDirect(16 * 16 * 4)
    private val quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0)
    }

    fun initialize(texture: SurfaceTexture, previewDimensions: Size) {
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY) { "EGL display indisponível" }
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "EGL não inicializou" }
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            val attributes = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
                0x3142, 1, // EGL_RECORDABLE_ANDROID
                EGL14.EGL_NONE
            )
            check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) {
                "Configuração GPU para vídeo indisponível"
            }
            config = configs[0]
            eglContext = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            check(eglContext != EGL14.EGL_NO_CONTEXT) { "Contexto GLES indisponível" }
            anchor = EGL14.eglCreatePbufferSurface(display, config,
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            makeCurrent(anchor)
            val maxTexture = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxTexture, 0)
            check(width <= maxTexture[0] && height <= maxTexture[0]) { "Selecione uma resolução menor para efeitos" }
            cameraProgram = program("camera.frag")
            copyProgram = program("copy.frag")
            use(copyProgram)
            GLES20.glUniform2f(location(copyProgram, "uSourceTexel"), 1f / width, 1f / height)
            blurProgram = program("blur.frag")
            effectsProgram = program("effects.frag")
            externalTexture = texture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
            cameraTexture = SurfaceTexture(externalTexture).apply {
                setDefaultBufferSize(width, height)
                setOnFrameAvailableListener({
                    // Native callbacks can accumulate while the same worker finalizes/copies an MP4.
                    // Consume the latest image once, rather than replaying expensive stale render tasks.
                    if (!closed && !renderQueued) {
                        renderQueued = true
                        if (!worker.post(renderLatest)) renderQueued = false
                    }
                }, worker)
            }
            cameraNative = Surface(cameraTexture)
            raw = target(width, height)
            val scale = minOf(1f, 480f / width)
            val blurWidth = (width * scale).toInt().coerceAtLeast(16)
            val blurHeight = (height * scale).toInt().coerceAtLeast(16)
            blurX = target(blurWidth, blurHeight); blurY = target(blurWidth, blurHeight)
            meter = target(16, 16)
            fun analysisTarget(longEdge: Int): Target {
                val analysisScale = minOf(1f, longEdge.toFloat() / maxOf(width, height))
                return target((width * analysisScale).toInt().coerceAtLeast(16),
                    (height * analysisScale).toInt().coerceAtLeast(16))
            }
            portraitFast = analysisTarget(456)
            portraitQuality = analysisTarget(512)
            objectFast = analysisTarget(256)
            portraitMaskTexture = texture(GLES20.GL_TEXTURE_2D)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, 1, 1, 0,
                GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, ByteBuffer.allocateDirect(1).put(0).apply { rewind() })
            portraitSegmenter = PortraitSegmenter(worker, rotation, { result ->
                if (!closed && settings.subjectMode == SubjectFocusPolicy.PEOPLE &&
                    aiAnalysisEnabled(settings)) {
                    portraitMask = result
                    portraitHasSubject = portraitSegmenter?.subjectPresent == true
                    portraitMaskPending = true
                    portraitFailure = false
                }
            }, { error ->
                if (!closed && settings.subjectMode == SubjectFocusPolicy.PEOPLE) {
                    portraitFailure = true
                    reportPortrait(SubjectFocusPolicy.status(SubjectFocusPolicy.StatusKind.FAILED, settings.subjectMode))
                    android.util.Log.w("LumaPortrait", "On-device segmentation failed", error)
                }
            })
            processed = listOf(target(width, height), target(width, height))
            texture.setDefaultBufferSize(previewDimensions.width, previewDimensions.height)
            previewSize = previewDimensions
            previewNative = Surface(texture)
            preview = window(checkNotNull(previewNative))
            checkGl("Inicialização")
        } catch (error: Exception) { close(); throw error }
    }

    fun update(value: CaptureSettings) {
        val previous = settings
        val nextStabilizationTuning = buildStabilizationTuning(value)
        if (value.histogramEnabled != previous.histogramEnabled || value.waveformEnabled != previous.waveformEnabled ||
            value.rgbParadeEnabled != previous.rgbParadeEnabled || value.vectorscopeEnabled != previous.vectorscopeEnabled) lastHistogramTime = 0L
        val motionChange = MotionAnalysisConfigurationPolicy.change(previous, value)
        // Zoom changes scale rather than rigid scene motion. Reject analyses spanning two zoom
        // configurations, while keeping the existing correction's gradual loss/recovery path.
        if (motionChange.resetReference) motionGate.invalidate()
        if (motionChange.resetCorrection) {
            stabilizationPolicy.reset(); lastMotionTime = 0L
            displayStabilization = StabilizationTransform()
            reportStabilization(if (value.stabilizationEnabled) "Estabilização · preparando" else "")
        }
        cachedStabilizationTuning = nextStabilizationTuning
        portraitSegmenter?.updateTuning(value.portraitStability)
        objectSegmenter?.updateTuning(value.portraitStability)
        val wasPortrait = aiAnalysisEnabled(previous)
        val hasPortrait = aiAnalysisEnabled(value)
        val changedSubject = value.subjectMode != previous.subjectMode ||
            value.objectTapRevision != previous.objectTapRevision ||
            value.objectFocusX != previous.objectFocusX || value.objectFocusY != previous.objectFocusY ||
            value.objectPointSelected != previous.objectPointSelected
        val trackingChanged = value.subjectTrackingEnabled != previous.subjectTrackingEnabled
        val trackingPointChanged = value.subjectTrackingEnabled && value.subjectMode == SubjectFocusPolicy.PEOPLE &&
            value.cinematicTapRevision != previous.cinematicTapRevision
        if (changedSubject || trackingChanged || trackingPointChanged) {
            subjectTrackAnalysis.reset(if (trackingPointChanged) value.cinematicFocusX to value.cinematicFocusY else null)
            lastSubjectTrackTime = 0L; onSubjectTrack(null)
        }
        if ((wasPortrait && !hasPortrait) || changedSubject) {
            portraitSegmenter?.invalidate()
            objectSegmenter?.invalidate()
            if (!hasPortrait || value.subjectMode == SubjectFocusPolicy.PEOPLE) {
                objectSegmenter?.close(); objectSegmenter = null
            }
            portraitMask = null
            portraitHasSubject = false
            portraitMaskPending = false
            portraitFailure = false
            lastSubjectTrackTime = 0L
            onSubjectTrack(null)
            reportPortrait(if (!hasPortrait) "" else SubjectFocusPolicy.status(
                if (SubjectFocusPolicy.needsObjectSelection(value.subjectMode, value.objectPointSelected,
                        value.objectFocusX, value.objectFocusY)) SubjectFocusPolicy.StatusKind.WAITING_POINT
                else SubjectFocusPolicy.StatusKind.ANALYZING, value.subjectMode))
        }
        if ((!wasPortrait || changedSubject) && hasPortrait) {
            focusBackground = if (value.focusBackground) 1f else 0f
            focusDestinationBackground = value.focusBackground
            lastFocusTime = 0L
            reportPortrait(SubjectFocusPolicy.status(
                if (SubjectFocusPolicy.needsObjectSelection(value.subjectMode, value.objectPointSelected,
                        value.objectFocusX, value.objectFocusY)) SubjectFocusPolicy.StatusKind.WAITING_POINT
                else SubjectFocusPolicy.StatusKind.ANALYZING, value.subjectMode))
        }
        val movedFocusPoint = value.cinematicFocusX != previous.cinematicFocusX ||
            value.cinematicFocusY != previous.cinematicFocusY ||
            value.cinematicTapRevision != previous.cinematicTapRevision
        if (value.cinematicEnabled && !value.cinematicAutoFocus && value.cinematicTapFocus &&
            (movedFocusPoint || !previous.cinematicTapFocus || !previous.cinematicEnabled)) pendingFocusSample = true
        else if (value.focusBackground != previous.focusBackground ||
            value.cinematicAutoFocus != previous.cinematicAutoFocus || !value.cinematicTapFocus) {
            focusDestinationBackground = value.focusBackground
            pendingFocusSample = false
        }
        settings = value
        val next = LocalEffectParams.from(value)
        if (effectParams != next) historyValid = false // Retain history across sensor-only edits.
        effectParams = next
        effectCenter = EffectCoordinates.sourceCenter(next.centerX, next.centerY, rotation, front)
    }

    /** Engine calls on its capture worker. The upload runs lazily with a valid current EGL context. */
    fun setPreviewLut(value: CubeLut?) {
        if (closed) return
        previewLut = value; previewLutDirty = true
    }

    fun mapSourcePoint(x: Float, y: Float): Pair<Float, Float>? =
        SubjectTrackDisplayPolicy.point(x, y, displayStabilization)

    private fun aiAnalysisEnabled(value: CaptureSettings) =
        value.portraitEnabled || value.cinematicEnabled || value.subjectTrackingEnabled

    fun attachEncoder(surface: Surface, fps: Int, physicallyRotated: Boolean = true) {
        check(!closed) { "Pipeline de vídeo já encerrada" }
        require(fps > 0) { "FPS deve ser positivo" }
        makeCurrent(anchor)
        detachEncoder()
        encoder = window(surface)
        encoderPhysicallyRotated = physicallyRotated
        encoderTimeline = RecordingTimeline(fps)
    }

    fun detachEncoder() {
        encoderTimeline = null
        val previous = encoder
        encoder = EGL14.EGL_NO_SURFACE // A failed EGL call must never retain a released recorder's surface.
        if (display != EGL14.EGL_NO_DISPLAY && previous != EGL14.EGL_NO_SURFACE) {
            try { makeCurrent(anchor) }
            finally { EGL14.eglDestroySurface(display, previous) }
        }
    }

    private fun renderFrame() {
        if (closed) return
        try {
            makeCurrent(anchor)
            if (previewLutDirty) uploadPreviewLut()
            val input = cameraTexture ?: return
            input.updateTexImage(); input.getTransformMatrix(nativeCameraTransform)
            val normalized = FrameGeometry.canonicalCameraTransform(nativeCameraTransform, transform)
            if (!reportedCameraGeometry) {
                reportedCameraGeometry = true
                android.util.Log.i("LumaGeometry", "Sensor=$sensorOrientation relative=$rotation front=$front " +
                    "source=${width}x$height normalized=$normalized native=" + nativeCameraTransform.contentToString())
            }
            val cameraTimestamp = input.timestamp
            if (!RecordingTimeline.isNewCameraFrame(cameraTimestamp, lastFrameTime)) return
            lastFrameTime = cameraTimestamp
            bind(raw); use(cameraProgram)
            sampler(cameraProgram, "uCamera", externalTexture, 0, GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
            GLES20.glUniformMatrix4fv(location(cameraProgram, "uTransform"), 1, false, transform, 0)
            draw(cameraProgram)
            val params = effectParams
            val now = SystemClock.elapsedRealtime()
            val aiEnabled = aiAnalysisEnabled(settings)
            if (aiEnabled) analyzePortrait(now)
            val mask = portraitMask
            val maskFreshness = if (aiEnabled && mask != null) {
                if (settings.subjectMode == SubjectFocusPolicy.OBJECTS) ObjectMaskPolicy.freshness(now, mask.capturedAtMs)
                else PortraitMaskPolicy.freshness(now, mask.capturedAtMs)
            } else 0f
            val subjectPresent = SubjectFocusPolicy.shouldApplyMask(aiEnabled, settings.subjectMode,
                settings.objectPointSelected, settings.objectFocusX, settings.objectFocusY,
                maskValid = mask != null && maskFreshness > 0f && portraitHasSubject)
            if (settings.subjectTrackingEnabled && (lastSubjectTrackTime == 0L || now - lastSubjectTrackTime >= 250L)) {
                lastSubjectTrackTime = now
                onSubjectTrack(subjectTrackAnalysis.validatedCentroid(mask, subjectPresent, maskFreshness))
            }
            val guide = mask?.takeIf { subjectPresent && maskFreshness >= .8f && settings.stabilizationUseSubjectMask &&
                it.capturedAtMs in 0L..now && now - it.capturedAtMs <= 600L }
            if (settings.stabilizationEnabled) analyzeStabilization(now, guide)
            val stabilization = stabilizationPolicy.transform(now, stabilizationTuning())
            displayStabilization = stabilization
            if (settings.stabilizationEnabled) reportStabilization(when {
                stabilization.reason == "zero_intensity" -> "Estabilização · só recorte; força 0%"
                stabilization.reason == "recovering" -> "Estabilização · retomando suavemente"
                stabilization.reason == "crop_limit" -> "Estabilização · limite da margem de recorte"
                stabilization.reason == "pan" -> "Estabilização · acompanhando movimento intencional"
                stabilization.tracking && guide != null -> "Estabilização · fundo guiado pela IA"
                stabilization.tracking && kotlin.math.abs(stabilization.rotationRadians) > .0008f -> "Estabilização · compensando deslocamento e giro"
                stabilization.tracking -> "Estabilização · compensando movimento"
                stabilization.reason == "first_frame" -> "Estabilização · analisando movimento"
                stabilization.reason == "scene_cut" -> "Estabilização · analisando a nova cena"
                else -> "Estabilização · pouca referência; correção reduzida"
            })
            val portraitAmount = if (subjectPresent && (settings.portraitEnabled || settings.cinematicEnabled) &&
                settings.portraitStrength.isFinite()) settings.portraitStrength.coerceIn(0f, 1f) else 0f
            if (aiEnabled) updatePortraitFocus(now, mask, subjectPresent, maskFreshness)
            if (portraitMaskPending && mask != null) uploadPortraitMask(mask)
            val ovalAmount = if (params.blur) params.blurAmount else 0f
            val focusBlend = focusBackground * focusBackground * (3f - 2f * focusBackground)
            if (ovalAmount > 0f) renderBlur(ovalAmount, blurY)
            // Independent radii: an oval with a high strength must not enlarge a weak AI blur.
            // Reuse the horizontal target; only simultaneous effects need one extra small destination.
            val portraitBlur = if (portraitAmount > 0f) {
                val destination = if (ovalAmount <= 0f) blurY else portraitBlurY ?: target(blurY.width, blurY.height).also {
                    portraitBlurY = it
                }
                renderBlur(portraitAmount, destination, focusBlend,
                    settings.portraitEdgeSoftness.coerceIn(0f, 1f))
                destination.texture
            } else raw.texture
            val outputIndex = 1 - historyIndex
            val output = processed[outputIndex]
            bind(output); use(effectsProgram)
            sampler(effectsProgram, "uImage", raw.texture, 0)
            sampler(effectsProgram, "uBlur", if (ovalAmount > 0f) blurY.texture else raw.texture, 1)
            sampler(effectsProgram, "uHistory", processed[historyIndex].texture, 2)
            sampler(effectsProgram, "uPortraitMask", portraitMaskTexture, 3)
            sampler(effectsProgram, "uPortraitBlur", portraitBlur, 4)
            GLES20.glUniform2f(location(effectsProgram, "uTexel"), 1f / width, 1f / height)
            uniform("uGain", params.gain); uniform("uTemperature", params.temperature)
            uniform("uContrast", params.contrast); uniform("uSaturation", params.saturation)
            uniform("uSharpness", params.sharpness); uniform("uFlat", params.flat)
            uniform("uLogStrength", params.logStrength)
            uniform("uLogProfileVersion", params.logProfileVersion.toFloat())
            uniform("uPortraitEnabled", if (subjectPresent && (settings.portraitEnabled || settings.cinematicEnabled)) maskFreshness else 0f)
            uniform("uPortraitStrength", portraitAmount)
            uniform("uPortraitEdgeSoftness", settings.portraitEdgeSoftness.coerceIn(0f, 1f))
            // Ease the visual transition while retaining an exact user-selected rack duration.
            uniform("uFocusBackground", focusBlend)
            uniform("uBlurEnabled", if (params.blur && params.blurAmount > 0) 1f else 0f)
            val center = effectCenter
            GLES20.glUniform2f(location(effectsProgram, "uCenter"), center.first, center.second)
            val rotated = rotation == 90 || rotation == 270
            GLES20.glUniform2f(location(effectsProgram, "uRadius"),
                params.radius * if (rotated) 1.35f else 1f,
                params.radius * if (rotated) 1f else 1.35f)
            uniform("uTrail", if (historyValid) params.trail else 0f)
            draw(effectsProgram)
            historyIndex = outputIndex; historyValid = true

            // Meter the clean camera signal, before digital gain/creative effects.
            if (settings.isoEnabled != settings.shutterEnabled && now - lastMeterTime >= 250) {
                lastMeterTime = now
                bind(meter); copy(raw.texture)
                luminancePixels.clear()
                GLES20.glReadPixels(0, 0, 16, 16, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, luminancePixels)
                var sum = 0f
                for (i in 0 until 256) {
                    val offset = i * 4
                    sum += (luminancePixels.get(offset).toInt() and 255) * .2126f +
                        (luminancePixels.get(offset + 1).toInt() and 255) * .7152f +
                        (luminancePixels.get(offset + 2).toInt() and 255) * .0722f
                }
                onLuminance(sum / (256 * 255))
            }

            if (settings.histogramEnabled || settings.waveformEnabled || settings.rgbParadeEnabled || settings.vectorscopeEnabled)
                analyzeHistogram(now, output.texture,
                stabilization.takeIf { settings.stabilizationEnabled })

            makeCurrent(preview)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            // Render upright pixels directly into a view-sized native buffer. The UI uses an identity transform.
            surfaceDimensions[0] = previewSize.width; surfaceDimensions[1] = previewSize.height
            EGL14.eglQuerySurface(display, preview, EGL14.EGL_WIDTH, surfaceDimensions, 0)
            EGL14.eglQuerySurface(display, preview, EGL14.EGL_HEIGHT, surfaceDimensions, 1)
            fitOutput(surfaceDimensions[0].takeIf { it > 0 } ?: previewSize.width,
                surfaceDimensions[1].takeIf { it > 0 } ?: previewSize.height)
            copy(output.texture, previewAssist = true, uv = previewUvTransform,
                stabilization = stabilization.takeIf { settings.stabilizationEnabled })
            check(EGL14.eglSwapBuffers(display, preview)) { "Falha de superfície da prévia" }

            // MediaRecorder's audio and start/stop boundary use CLOCK_MONOTONIC. Sensor PTS may
            // use elapsedRealtime/BOOTTIME or an unknown origin: forwarding it can create hours
            // of empty timeline or make the recorder reject every input frame.
            val presentation = if (encoder != EGL14.EGL_NO_SURFACE)
                encoderTimeline?.presentationTime(System.nanoTime()) else null
            if (presentation != null) {
                makeCurrent(encoder)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                val dimensions = if (encoderPhysicallyRotated) encodedDimensions else rawEncodedDimensions
                surfaceDimensions[0] = dimensions.width; surfaceDimensions[1] = dimensions.height
                EGL14.eglQuerySurface(display, encoder, EGL14.EGL_WIDTH, surfaceDimensions, 0)
                EGL14.eglQuerySurface(display, encoder, EGL14.EGL_HEIGHT, surfaceDimensions, 1)
                fitOutput(surfaceDimensions[0].takeIf { it > 0 } ?: dimensions.width,
                    surfaceDimensions[1].takeIf { it > 0 } ?: dimensions.height,
                    outputRotation = if (encoderPhysicallyRotated) rotation else 0)
                // Prefer upright pixels + a neutral MP4 hint. Limited encoders keep canonical raw pixels
                // and use only the recorder's orientation hint; never rotate both pixels and metadata.
                copy(output.texture, uv = if (encoderPhysicallyRotated) encoderUvTransform else null,
                    stabilization = stabilization.takeIf { settings.stabilizationEnabled })
                check(EGLExt.eglPresentationTimeANDROID(display, encoder, presentation)) { "Timestamp do encoder recusado" }
                check(EGL14.eglSwapBuffers(display, encoder)) { "Falha de superfície do vídeo" }
            }
            checkGl("Processamento de frame")
        } catch (error: Exception) { onError(error) }
    }

    private fun fitOutput(surfaceWidth: Int, surfaceHeight: Int, outputRotation: Int = rotation) {
        val fitted = FrameGeometry.fitViewport(width, height, outputRotation, surfaceWidth, surfaceHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glViewport(fitted.x, fitted.y, fitted.width, fitted.height)
    }

    private fun analyzePortrait(now: Long) {
        val objects = settings.subjectMode == SubjectFocusPolicy.OBJECTS
        if (!SubjectFocusPolicy.shouldAnalyze(true, settings.subjectMode, settings.objectPointSelected,
                settings.objectFocusX, settings.objectFocusY)) return
        if (objects && objectSegmenter == null) {
            objectSegmenter = ObjectSegmenter(context, worker, rotation, { result ->
                if (!closed && settings.subjectMode == SubjectFocusPolicy.OBJECTS && settings.objectPointSelected &&
                    aiAnalysisEnabled(settings)) {
                    val foundObject = ObjectMaskPolicy.hasObject(result)
                    portraitMask = result
                    portraitHasSubject = foundObject
                    portraitMaskPending = true
                    portraitFailure = false
                }
            }, { error ->
                if (!closed && settings.subjectMode == SubjectFocusPolicy.OBJECTS && settings.objectPointSelected) {
                    portraitFailure = true
                    reportPortrait(SubjectFocusPolicy.status(SubjectFocusPolicy.StatusKind.FAILED, settings.subjectMode))
                    android.util.Log.w("LumaObject", "On-device object segmentation failed", error)
                }
            })
            objectSegmenter?.updateTuning(settings.portraitStability)
        }
        val quality = !objects && settings.portraitQuality == 1
        if (objects) { if (objectSegmenter?.canSubmit(now) != true) return }
        else if (portraitSegmenter?.canSubmit(now, quality) != true) return
        val target = if (objects) objectFast else if (quality) portraitQuality else portraitFast
        bind(target); copy(raw.texture)
        val size = target.width * target.height * 4
        val pixels = portraitReadback?.takeIf { it.capacity() >= size } ?: ByteBuffer.allocateDirect(size).also {
            portraitReadback = it
        }
        pixels.clear()
        GLES20.glReadPixels(0, 0, target.width, target.height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
        if (objects) objectSegmenter?.submit(pixels, target.width, target.height, now,
            settings.objectFocusX, settings.objectFocusY, settings.objectTapRevision)
        else portraitSegmenter?.submit(pixels, target.width, target.height, now)
    }

    private fun uploadPortraitMask(mask: PortraitMaskPolicy.Mask) {
        val count = mask.width * mask.height
        val pixels = portraitMaskPixels?.takeIf { it.capacity() >= count } ?: ByteBuffer.allocateDirect(count).also {
            portraitMaskPixels = it
        }
        pixels.clear()
        mask.confidence.forEach { pixels.put((it.coerceIn(0f, 1f) * 255f + .5f).toInt().toByte()) }
        pixels.flip()
        GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, portraitMaskTexture)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        if (uploadedMaskWidth == mask.width && uploadedMaskHeight == mask.height) {
            GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, mask.width, mask.height,
                GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, pixels)
        } else {
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, mask.width, mask.height, 0,
                GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, pixels)
            uploadedMaskWidth = mask.width; uploadedMaskHeight = mask.height
        }
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 4)
        portraitMaskPending = false
    }

    private fun renderBlur(amount: Float, destination: Target, focusBlend: Float? = null, edgeSoftness: Float = 0f) {
        val feather = (.14f + .4f * edgeSoftness).coerceIn(.14f, .48f)
        bind(blurX); use(blurProgram)
        sampler(blurProgram, "uImage", raw.texture, 0)
        sampler(blurProgram, "uPortraitMask", portraitMaskTexture, 3)
        GLES20.glUniform3f(location(blurProgram, "uPortraitWeights"), if (focusBlend == null) 0f else 1f,
            focusBlend ?: 0f, feather)
        GLES20.glUniform2f(location(blurProgram, "uStep"), (1f + amount * 6f) / blurX.width, 0f)
        draw(blurProgram)
        bind(destination); use(blurProgram)
        sampler(blurProgram, "uImage", blurX.texture, 0)
        GLES20.glUniform3f(location(blurProgram, "uPortraitWeights"), if (focusBlend == null) 0f else 2f,
            focusBlend ?: 0f, feather)
        GLES20.glUniform2f(location(blurProgram, "uStep"), 0f, (1f + amount * 6f) / destination.height)
        draw(blurProgram)
    }

    private fun updatePortraitFocus(now: Long, mask: PortraitMaskPolicy.Mask?, hasSubject: Boolean, freshness: Float) {
        if (settings.cinematicEnabled && settings.cinematicAutoFocus) {
            focusDestinationBackground = !hasSubject
            pendingFocusSample = false
        } else if (settings.cinematicEnabled && settings.cinematicTapFocus && pendingFocusSample &&
            mask != null && freshness > 0f) {
            val confidence = PortraitMaskPolicy.confidenceAt(mask, settings.cinematicFocusX, settings.cinematicFocusY)
            focusDestinationBackground = PortraitMaskPolicy.targetBackground(confidence, focusDestinationBackground)
            pendingFocusSample = false
        } else if (!settings.cinematicEnabled || !settings.cinematicTapFocus) {
            focusDestinationBackground = settings.focusBackground
        }

        val elapsed = if (lastFocusTime == 0L) 0f else (now - lastFocusTime).coerceAtLeast(0L) / 1_000f
        lastFocusTime = now
        focusBackground = if (settings.cinematicEnabled) PortraitMaskPolicy.advanceFocus(
            focusBackground, focusDestinationBackground, elapsed, settings.cinematicTransitionSeconds)
        else if (focusDestinationBackground) 1f else 0f
        val kind = when {
            SubjectFocusPolicy.needsObjectSelection(settings.subjectMode, settings.objectPointSelected,
                settings.objectFocusX, settings.objectFocusY) -> SubjectFocusPolicy.StatusKind.WAITING_POINT
            portraitFailure && !hasSubject -> SubjectFocusPolicy.StatusKind.FAILED
            mask == null -> SubjectFocusPolicy.StatusKind.ANALYZING
            !hasSubject || freshness <= 0f -> SubjectFocusPolicy.StatusKind.EMPTY
            else -> SubjectFocusPolicy.StatusKind.MASK_VALID
        }
        reportPortrait(if (hasSubject && freshness in 0f..0.6f) {
            if (settings.portraitEnabled || settings.cinematicEnabled) "Desfoque IA · recorte atrasado · efeito reduzido"
            else "Acompanhamento IA · recorte atrasado · acompanhamento limitado"
        } else SubjectFocusPolicy.status(kind, settings.subjectMode,
            focusDestinationBackground, settings.cinematicEnabled))
    }

    private fun reportPortrait(value: String) {
        val description = if (settings.subjectTrackingEnabled && !settings.portraitEnabled && !settings.cinematicEnabled)
            value.replace("Desfoque IA", "Acompanhamento IA").replace("preservado", "localizado").replace("preservada", "localizada") else value
        if (portraitStatus != description) { portraitStatus = description; onPortraitStatus(description) }
    }

    private fun stabilizationTuning() = cachedStabilizationTuning

    private fun buildStabilizationTuning(value: CaptureSettings) = StabilizationTuning(
        enabled = value.stabilizationEnabled,
        intensity = value.stabilizationStrength,
        response = 1f - value.stabilizationResponse,
        cropZoom = 1f + value.stabilizationCrop,
        mode = value.stabilizationMode,
        horizonCorrection = value.stabilizationHorizonCorrection,
        aspectRatio = width.toFloat() / height
    )

    private fun analyzeHistogram(now: Long, image: Int, stabilization: StabilizationTransform?) {
        if (lastHistogramTime != 0L && now - lastHistogramTime < MonitorAnalysis.INTERVAL_MS) return
        lastHistogramTime = now
        val destination = histogramTarget ?: target(MonitorAnalysis.READBACK_WIDTH,
            MonitorAnalysis.READBACK_HEIGHT).also { histogramTarget = it }
        bind(destination)
        // Waveform columns follow the upright, unmirrored recorded image. No display LUT/LOG assist/monitors.
        copy(image, uv = encoderUvTransform, stabilization = stabilization)
        val pixels = histogramPixels ?: ByteBuffer.allocateDirect(
            destination.width * destination.height * 4).also { histogramPixels = it }
        pixels.clear()
        GLES20.glReadPixels(0, 0, destination.width, destination.height,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
        val snapshot = MonitorAnalysis.scopes(pixels, destination.width, destination.height,
            settings.waveformEnabled, settings.rgbParadeEnabled, settings.vectorscopeEnabled)
        if (settings.histogramEnabled) onHistogram(snapshot.histogram)
        if (settings.waveformEnabled || settings.rgbParadeEnabled || settings.vectorscopeEnabled) onScopes(snapshot)
    }

    private fun analyzeStabilization(now: Long, foregroundMask: PortraitMaskPolicy.Mask?) {
        val precise = settings.stabilizationQuality == 1
        if (lastMotionTime != 0L && now - lastMotionTime < if (precise) 67L else 100L) return
        val ticket = motionGate.tryBegin(now) ?: return
        lastMotionTime = now
        // Small readback only when enabled. Segmentation, exposure and the full-resolution frame stay clean.
        try {
            val analysis = if (precise) stabilizationPrecise else stabilizationFast
            val destination = analysis ?: run {
                val scale = minOf(1f, (if (precise) 128f else 96f) / maxOf(width, height))
                target((width * scale).toInt().coerceAtLeast(16), (height * scale).toInt().coerceAtLeast(16)).also {
                    if (precise) stabilizationPrecise = it else stabilizationFast = it
                }
            }
            bind(destination); copy(raw.texture)
            val count = destination.width * destination.height
            val pixels = stabilizationPixels?.takeIf { it.capacity() >= count * 4 }
                ?: ByteBuffer.allocateDirect(count * 4).also { stabilizationPixels = it }
            pixels.clear()
            GLES20.glReadPixels(0, 0, destination.width, destination.height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
            // The gate retains exclusive ownership of this RGBA buffer while the CPU uses absolute reads.
            // Segmenter masks own immutable FloatArrays; the CPU creates a reduced private mask snapshot.
            motionExecutor.execute {
                try {
                    if (!motionGate.isCurrent(ticket)) return@execute
                    if (estimatorGeneration != ticket.generation) {
                        motionEstimator.reset(); estimatorGeneration = ticket.generation
                    }
                    val luma = stabilizationLuma?.takeIf { it.size == count } ?: FloatArray(count).also { stabilizationLuma = it }
                    MotionAnalysisPixels.luma(pixels, destination.width, destination.height, luma)
                    val maskBuffer = stabilizationMaskSnapshot?.takeIf { it.size == count } ?: FloatArray(count).also {
                        stabilizationMaskSnapshot = it
                    }
                    val ownMask = MotionAnalysisPixels.maskSnapshot(foregroundMask, destination.width, destination.height, now, maskBuffer)
                    val estimate = motionEstimator.consume(luma, destination.width, destination.height, now, ownMask)
                    if (!motionGate.shouldAccept(ticket, SystemClock.elapsedRealtime())) {
                        motionEstimator.reset(); estimatorGeneration = Long.MIN_VALUE
                        return@execute
                    }
                    worker.post {
                        if (!closed && motionGate.shouldAccept(ticket, SystemClock.elapsedRealtime()))
                            stabilizationPolicy.update(estimate, ticket.capturedAtMs, stabilizationTuning())
                    }
                } catch (error: Exception) {
                    motionEstimator.reset(); estimatorGeneration = Long.MIN_VALUE
                    android.util.Log.w("LumaMotion", "Motion analysis rejected", error)
                } finally { motionGate.complete(ticket) }
            }
        } catch (_: RejectedExecutionException) { motionGate.complete(ticket) }
        catch (error: Exception) { motionGate.complete(ticket); throw error }
    }

    private fun reportStabilization(value: String) {
        if (stabilizationStatus != value) { stabilizationStatus = value; onStabilizationStatus(value) }
    }

    private fun window(surface: Surface): EGLSurface = EGL14.eglCreateWindowSurface(
        display, config, surface, intArrayOf(EGL14.EGL_NONE), 0
    ).also { check(it != EGL14.EGL_NO_SURFACE) { "Superfície EGL indisponível (${EGL14.eglGetError()})" } }

    private fun makeCurrent(surface: EGLSurface) {
        check(surface != EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(display, surface, surface, eglContext)) {
            "Contexto EGL indisponível (${EGL14.eglGetError()})"
        }
    }

    private fun texture(kind: Int): Int {
        val ids = IntArray(1); GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(kind, ids[0])
        GLES20.glTexParameteri(kind, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(kind, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(kind, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(kind, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    private fun target(w: Int, h: Int): Target {
        val image = texture(GLES20.GL_TEXTURE_2D)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        val framebuffers = IntArray(1); GLES20.glGenFramebuffers(1, framebuffers, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffers[0])
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, image, 0)
        val target = Target(image, framebuffers[0], w, h).also { targets.add(it) }
        check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) {
            "Memória GPU insuficiente; tente 720p"
        }
        checkGl("Framebuffer")
        return target
    }

    private fun bind(target: Target) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, target.framebuffer)
        GLES20.glViewport(0, 0, target.width, target.height)
    }
    private fun use(program: Int) { GLES20.glUseProgram(program) }
    private fun location(program: Int, name: String): Int = programBindings.getValue(program).uniforms
        .getOrPut(name) { GLES20.glGetUniformLocation(program, name) }
    private fun uniform(name: String, value: Float) { GLES20.glUniform1f(location(effectsProgram, name), value) }
    private fun sampler(program: Int, name: String, image: Int, unit: Int, kind: Int = GLES20.GL_TEXTURE_2D) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
        GLES20.glBindTexture(kind, image)
        GLES20.glUniform1i(location(program, name), unit)
    }
    private fun draw(program: Int) {
        val attribute = programBindings.getValue(program).position
        quad.position(0)
        GLES20.glEnableVertexAttribArray(attribute)
        GLES20.glVertexAttribPointer(attribute, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(attribute)
    }
    private fun copy(image: Int, previewAssist: Boolean = false, uv: FloatArray? = null,
        stabilization: StabilizationTransform? = null) {
        use(copyProgram); sampler(copyProgram, "uImage", image, 0)
        val lut = previewLut?.takeIf { previewAssist && settings.previewLutEnabled && previewLutTexture != 0 }
        GLES20.glUniform3f(location(copyProgram, "uLutTuning"), if (lut != null) 1f else 0f,
            if (settings.previewLutStrength.isFinite()) settings.previewLutStrength.coerceIn(0f, 1f) else 1f,
            lut?.size?.toFloat() ?: 17f)
        if (lut != null) {
            sampler(copyProgram, "uPreviewLut", previewLutTexture, 4)
            GLES20.glUniform3f(location(copyProgram, "uLutDomainMin"), lut.domainMin[0], lut.domainMin[1], lut.domainMin[2])
            GLES20.glUniform3f(location(copyProgram, "uLutDomainMax"), lut.domainMax[0], lut.domainMax[1], lut.domainMax[2])
        }
        GLES20.glUniform4f(location(copyProgram, "uDisplayTuning"),
            if (previewAssist && settings.logEnabled && settings.logPreviewAssist) effectParams.logStrength else 0f,
            effectParams.logProfileVersion.toFloat(), if (uv == null) 0f else 1f, if (stabilization == null) 0f else 1f)
        if (uv != null) GLES20.glUniformMatrix3fv(location(copyProgram, "uUvTransform"), 1, false, uv, 0)
        GLES20.glUniform1f(location(copyProgram, "uStabilizationZoom"), stabilization?.zoom ?: 1f)
        GLES20.glUniform2f(location(copyProgram, "uStabilizationOffset"),
            stabilization?.centerOffsetX ?: 0f, stabilization?.centerOffsetY ?: 0f)
        val roll = stabilization?.rotationRadians?.takeIf { it.isFinite() } ?: 0f
        val aspect = stabilization?.aspectRatio?.takeIf { it.isFinite() && it > 0f } ?: 1f
        GLES20.glUniform3f(location(copyProgram, "uStabilizationGeometry"), cos(roll), sin(roll), aspect)
        val monitors = previewAssist && (settings.zebraEnabled || settings.falseColorEnabled || settings.peakingEnabled)
        GLES20.glUniform4f(location(copyProgram, "uMonitorModes"), if (monitors) 1f else 0f,
            if (monitors && settings.zebraEnabled) 1f else 0f,
            if (monitors && settings.falseColorEnabled) 1f else 0f,
            if (monitors && settings.peakingEnabled) 1f else 0f)
        if (monitors) {
            // Inactive copies need only the gate: avoid extra JNI calls for every meter/mask/MP4 pass.
            GLES20.glUniform4f(location(copyProgram, "uMonitorTuning"), settings.zebraThreshold.coerceIn(0f, 1f),
                settings.peakingStrength.coerceIn(0f, 1f), settings.monitorOverlayStrength.coerceIn(0f, 1f),
                if (settings.logEnabled) effectParams.logStrength else 0f)
        }
        draw(copyProgram)
    }

    private fun uploadPreviewLut() {
        previewLutDirty = false
        val lut = previewLut
        if (previewLutTexture != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(previewLutTexture), 0); previewLutTexture = 0
        }
        if (lut == null) return
        GLES20.glActiveTexture(GLES20.GL_TEXTURE4)
        previewLutTexture = texture(GLES20.GL_TEXTURE_2D)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, lut.atlasWidth, lut.atlasHeight,
            0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, lut.atlasRgba())
        checkGl("LUT da prévia")
    }

    private fun shader(kind: Int, source: String): Int {
        val shader = GLES20.glCreateShader(kind)
        GLES20.glShaderSource(shader, source); GLES20.glCompileShader(shader)
        val success = IntArray(1); GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, success, 0)
        if (success[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader); GLES20.glDeleteShader(shader)
            error("Shader recusado: $log")
        }
        return shader
    }
    private fun program(fragmentFile: String): Int {
        fun source(file: String) = context.assets.open("shaders/$file").bufferedReader().use { it.readText() }
        val vertex = shader(GLES20.GL_VERTEX_SHADER, source("fullscreen.vert"))
        var fragment = 0
        var result = 0
        try {
            val fragmentSource = source(fragmentFile)
            fragment = shader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
            result = GLES20.glCreateProgram()
            GLES20.glAttachShader(result, vertex); GLES20.glAttachShader(result, fragment); GLES20.glLinkProgram(result)
            val success = IntArray(1); GLES20.glGetProgramiv(result, GLES20.GL_LINK_STATUS, success, 0)
            check(success[0] != 0) { "Pipeline GPU recusada: ${GLES20.glGetProgramInfoLog(result)}" }
            val uniforms = mutableMapOf<String, Int>()
            Regex("uniform\\s+\\w+\\s+(\\w+)").findAll(fragmentSource).forEach { match ->
                val name = match.groupValues[1]
                uniforms[name] = GLES20.glGetUniformLocation(result, name)
            }
            programBindings[result] = ProgramBindings(GLES20.glGetAttribLocation(result, "aPosition"), uniforms)
            programs.add(result)
            return result
        } catch (error: Exception) { if (result != 0) GLES20.glDeleteProgram(result); throw error }
        finally { GLES20.glDeleteShader(vertex); if (fragment != 0) GLES20.glDeleteShader(fragment) }
    }
    private fun checkGl(stage: String) {
        val error = GLES20.glGetError()
        check(error == GLES20.GL_NO_ERROR) { "$stage: GLES $error" }
    }

    fun close() {
        if (closed) return
        closed = true
        worker.removeCallbacks(renderLatest); renderQueued = false
        portraitSegmenter?.close(); portraitSegmenter = null
        objectSegmenter?.close(); objectSegmenter = null
        portraitMask = null; portraitReadback = null; portraitMaskPixels = null
        motionGate.close(); motionExecutor.shutdownNow()
        stabilizationPixels = null
        histogramPixels = null; histogramTarget = null
        previewLut = null; previewLutDirty = false
        subjectTrackAnalysis.reset()
        onSubjectTrack(null)
        stabilizationPolicy.reset(); displayStabilization = StabilizationTransform()
        runCatching { cameraTexture?.setOnFrameAvailableListener(null) }
        if (display != EGL14.EGL_NO_DISPLAY && eglContext != EGL14.EGL_NO_CONTEXT && anchor != EGL14.EGL_NO_SURFACE) {
            if (runCatching { makeCurrent(anchor) }.isSuccess) {
                targets.forEach {
                    runCatching { GLES20.glDeleteFramebuffers(1, intArrayOf(it.framebuffer), 0) }
                    runCatching { GLES20.glDeleteTextures(1, intArrayOf(it.texture), 0) }
                }
                programs.forEach { runCatching { GLES20.glDeleteProgram(it) } }
                if (externalTexture != 0) runCatching { GLES20.glDeleteTextures(1, intArrayOf(externalTexture), 0) }
                if (portraitMaskTexture != 0) runCatching { GLES20.glDeleteTextures(1, intArrayOf(portraitMaskTexture), 0) }
                if (previewLutTexture != 0) runCatching { GLES20.glDeleteTextures(1, intArrayOf(previewLutTexture), 0) }
            }
        }
        if (display != EGL14.EGL_NO_DISPLAY) {
            runCatching { EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT) }
            if (encoder != EGL14.EGL_NO_SURFACE) runCatching { EGL14.eglDestroySurface(display, encoder) }
            if (preview != EGL14.EGL_NO_SURFACE) runCatching { EGL14.eglDestroySurface(display, preview) }
            if (anchor != EGL14.EGL_NO_SURFACE) runCatching { EGL14.eglDestroySurface(display, anchor) }
            if (eglContext != EGL14.EGL_NO_CONTEXT) runCatching { EGL14.eglDestroyContext(display, eglContext) }
            runCatching { EGL14.eglReleaseThread() }
            runCatching { EGL14.eglTerminate(display) }
        }
        runCatching { cameraNative?.release() }; cameraNative = null
        runCatching { cameraTexture?.release() }; cameraTexture = null
        runCatching { previewNative?.release() }; previewNative = null
        display = EGL14.EGL_NO_DISPLAY
        encoder = EGL14.EGL_NO_SURFACE; preview = EGL14.EGL_NO_SURFACE; anchor = EGL14.EGL_NO_SURFACE
        eglContext = EGL14.EGL_NO_CONTEXT
        targets.clear(); programs.clear(); programBindings.clear()
    }
}
