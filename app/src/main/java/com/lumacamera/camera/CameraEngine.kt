package com.lumacamera.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import android.media.MediaRecorder
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRouting
import android.net.Uri
import android.os.*
import android.util.Size
import android.view.Surface
import com.lumacamera.core.CapturePolicy
import com.lumacamera.core.RecordingTimeline
import com.lumacamera.core.StoragePolicy
import com.lumacamera.core.RecordingStorageSnapshot
import com.lumacamera.core.VideoMode
import com.lumacamera.core.ExposureAutomation
import com.lumacamera.core.FocusBounds
import com.lumacamera.core.FocusMeteringPolicy
import com.lumacamera.core.FrameGeometry
import com.lumacamera.core.CameraZoomPolicy
import com.lumacamera.core.RecordingOptions
import com.lumacamera.core.RecordingSegmentPolicy
import com.lumacamera.core.RecordingSegmentState
import com.lumacamera.core.AudioInputOption
import com.lumacamera.core.AudioRouteStatus
import com.lumacamera.core.AudioRoutePolicy
import com.lumacamera.core.FocusTrackingPolicy
import com.lumacamera.effects.GpuPipeline
import com.lumacamera.effects.MonitorHistogram
import com.lumacamera.effects.MonitorScopes
import com.lumacamera.effects.CubeLut
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.Executors

enum class EngineState { CLOSED, OPENING, READY, STARTING, RECORDING, SAVING, ERROR }

/** All camera and recorder ownership stays on one worker. Generation rejects stale async sessions. */
class CameraEngine(
    private val context: Context,
    private val onState: (EngineState, String) -> Unit,
    private val onSaved: (Uri) -> Unit,
    private val onMeter: (Int?, Long?) -> Unit,
    private val onSettingsReset: (CaptureSettings) -> Unit,
    private val onPortraitStatus: (String) -> Unit = {},
    private val onFocus: (String) -> Unit = {},
    private val onStabilizationStatus: (String) -> Unit = {},
    private val onAudioLevel: (Float) -> Unit = {},
    private val onHistogram: (MonitorHistogram) -> Unit = {},
    private val onScopes: (MonitorScopes) -> Unit = {},
    private val onSubjectTrack: (Pair<Float, Float>?) -> Unit = {}
) {
    private val thread = HandlerThread("LumaCapture").apply { start() }
    private val worker = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(CameraManager::class.java)
    private val audioManager = context.getSystemService(AudioManager::class.java)
    // MP4 inspection and MediaStore copies never run on the GPU/camera worker.
    private val publisher = Executors.newSingleThreadExecutor { task ->
        Thread(task, "LumaPublish").apply { priority = Thread.MIN_PRIORITY }
    }
    private var previewLut: CubeLut? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    @Volatile private var pipeline: GpuPipeline? = null
    private var recorder: MediaRecorder? = null
    @Volatile private var recordingHasAudio = false
    @Volatile private var recordingBitrate: Int? = null
    @Volatile private var recordingStartedAtNs: Long? = null
    @Volatile private var audioMonitorRevision = 0
    @Volatile private var output: File? = null
    private var recordingOptions = RecordingOptions()
    private var activeOptions = RecordingOptions()
    private var recordingPrefix = ""
    private var nextOutput: File? = null
    private data class CompletedPart(val file: File, val index: Int, val durationMs: Long?,
        val audioAtEnd: String? = null)
    private val completedParts = mutableListOf<CompletedPart>()
    private var segmentStartedAtNs: Long? = null
    @Volatile private var segmentSnapshot = RecordingSegmentState()
    @Volatile private var audioRoute = AudioRouteStatus()
    private var routingListener: AudioRouting.OnRoutingChangedListener? = null
    private var routeSelectionWarning: String? = null
    private val trackingPolicy = FocusTrackingPolicy()
    private var lastTrackingLensAt: Long? = null
    private var trackingFocusTarget = false
    @Volatile private var focusStatus = "Automático"
    @Volatile private var lensFocusDistance: Float? = null
    private var currentAfState: Int? = null
    private var lockedFocusDistance: Float? = null
    @Volatile private var zoomLimit = 1f
    @Volatile private var retainedRecordingBytes = 0L
    private var settings = CaptureSettings()
    private val settingsRevision = AtomicLong(0L)
    private var camera: CameraInfo? = null
    private var mode: VideoMode? = null
    @Volatile private var state = EngineState.CLOSED
    @Volatile private var generation = 0
    @Volatile private var destroyed = false
    private var pendingOpens = 0
    private var sessionGeneration = 0
    private var orientation = 0
    private var lastMeterAt = 0L
    // Worker-owned HUD cache. Sensor values below still update from every capture result.
    private var meterSnapshotSent = false
    private var lastMeterIso: Int? = null
    private var lastMeterExposure: Long? = null
    private var currentIso = 200
    private var currentExposure = 16_666_666L
    private var autoIso = 200
    private var autoExposure = 16_666_666L
    private var focusTarget: Pair<Float, Float>? = null
    private var focusRevision = 0
    private var lastFocusResultRevision = -1
    private var sensorCrop: Rect? = null
    private var distortionMode: Int? = null
    @Volatile private var recordingFileLimit = StoragePolicy.MAX_FILE_BYTES
    private val storageMonitor = object : Runnable {
        override fun run() {
            if (destroyed || state != EngineState.RECORDING) return
            val file = output ?: return
            val remaining = runCatching {
                val retained = retainedBytes()
                val pending = VideoStore.pendingPublicationBytes()
                if (activeOptions.splitEnabled)
                    RecordingSegmentPolicy.remainingSessionBytes(StatFs(file.parentFile!!.path).availableBytes, retained, pending)
                else StoragePolicy.remainingRecordingBytes(StatFs(file.parentFile!!.path).availableBytes,
                    retained, recordingFileLimit, pending)
            }.getOrNull()
            if (remaining == null || remaining <= 0L) {
                stopInternal()
                emit(state, "Gravação finalizada para preservar espaço de armazenamento. Confira o vídeo na biblioteca.")
            } else worker.postDelayed(this, 1_000L)
        }
    }
    private val audioMonitor = object : Runnable {
        override fun run() {
            if (destroyed || state != EngineState.RECORDING || !settings.audioMeterEnabled || !recordingHasAudio) return
            val active = recorder ?: return
            val amplitude = try { active.maxAmplitude } catch (_: Exception) { stopAudioMonitoring(); return }
            emitAudioLevel(CapturePolicy.audioPeak(amplitude))
            // Schedule after each sample, never accumulate catch-up polls when the capture worker is busy.
            worker.postDelayed(this, 250L)
        }
    }

    /** The target actually passed to the prepared recorder; VBR output still varies by content/driver. */
    fun configuredVideoBitrate(): Int? = recordingBitrate

    /** Actual clip configuration, independent of a stored microphone preference. Safe to read on the UI thread. */
    fun recordingWithAudio(): Boolean = state == EngineState.RECORDING && recordingHasAudio

    fun setRecordingOptions(options: RecordingOptions) {
        worker.post {
            if (!destroyed && state != EngineState.STARTING && state != EngineState.RECORDING && state != EngineState.SAVING)
                recordingOptions = options.sanitized()
        }
    }

    fun availableAudioInputs(): List<AudioInputOption> = runCatching {
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).filter {
            it.isSource && it.type in listOf(AudioDeviceInfo.TYPE_BUILTIN_MIC, AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL)
        }.map { AudioInputOption(it.id, audioInputLabel(it), it.type, it.type != AudioDeviceInfo.TYPE_BUILTIN_MIC) }
    }.getOrDefault(emptyList())

    fun audioRouteStatus(): AudioRouteStatus = audioRoute
    fun recordingSegmentState(): RecordingSegmentState = segmentSnapshot
    fun maxZoomRatio(): Float = zoomLimit
    fun focusLockStatus(): String = focusStatus
    fun currentFocusDiopters(): Float? = lensFocusDistance

    fun setPreviewLut(lut: CubeLut?) {
        worker.post { if (!destroyed) { previewLut = lut; pipeline?.setPreviewLut(lut) } }
    }

    private fun audioInputLabel(device: AudioDeviceInfo): String {
        val kind = when (device.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Microfone interno"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Microfone com fio"
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "Microfone USB"
            else -> "Entrada externa"
        }
        val name = device.productName?.toString()?.let(RecordingSegmentPolicy::cleanLabel).orEmpty()
        return if (name.isBlank() || device.type == AudioDeviceInfo.TYPE_BUILTIN_MIC) kind else "$kind · $name"
    }

    private fun refreshAudioRoute(source: MediaRecorder) {
        if (recorder !== source || state != EngineState.RECORDING || !recordingHasAudio) return
        val routed = runCatching { source.routedDevice }.getOrNull()
        val preferred = activeOptions.audioDeviceId
        audioRoute = AudioRoutePolicy.status(preferred, routed?.id, routed?.let(::audioInputLabel),
            state == EngineState.RECORDING, recordingHasAudio)
    }

    private fun retainedBytes(): Long {
        // Only filesystem size is inspected while the recorder owns these files; no native parser opens them.
        var total = output?.length() ?: 0L
        completedParts.forEach { total = StoragePolicy.saturatedSum(total, it.file.length()) }
        nextOutput?.let { total = StoragePolicy.saturatedSum(total, it.length()) }
        retainedRecordingBytes = total
        return total
    }

    /** Starts only after recorder.start succeeds; preparation and previous clips never enter the counter. */
    fun recordingElapsedMs(): Long = RecordingTimeline.elapsedMs(recordingStartedAtNs, System.nanoTime(),
        state == EngineState.RECORDING)

    /** A short filesystem snapshot for an explicit conditions panel; never read by the frame loop. */
    fun recordingStorageSnapshot(): RecordingStorageSnapshot? = runCatching {
        val file = output
        val recording = state == EngineState.RECORDING
        val free = StatFs((file?.parentFile ?: context.filesDir).path).availableBytes
        val pending = VideoStore.pendingPublicationBytes()
        RecordingStorageSnapshot(free, if (recording) retainedRecordingBytes else 0L,
            if (recording && segmentSnapshot.splitEnabled) Long.MAX_VALUE else
                if (recording) recordingFileLimit else StoragePolicy.maxRecordingFileBytes((free - pending).coerceAtLeast(0L)),
            if (recording) recordingBitrate else null, recording && recordingHasAudio, recording, pending)
    }.getOrNull()

    private fun emitAudioLevel(level: Float) {
        val token = generation
        val revision = audioMonitorRevision
        main.post { if (!destroyed && token == generation && revision == audioMonitorRevision) onAudioLevel(level) }
    }

    private fun stopAudioMonitoring() {
        worker.removeCallbacks(audioMonitor)
        audioMonitorRevision++
        emitAudioLevel(0f)
    }

    private fun refreshAudioMonitoring() {
        stopAudioMonitoring()
        if (state != EngineState.RECORDING || !settings.audioMeterEnabled || !recordingHasAudio || recorder == null) return
        // Enabling a meter midway through a clip must not show a peak accumulated while it was hidden.
        try { recorder?.maxAmplitude } catch (_: Exception) { return }
        worker.postDelayed(audioMonitor, 250L)
    }

    private fun focusFeedback(message: String) {
        focusStatus = message
        val token = generation
        main.post { if (!destroyed && token == generation) onFocus("Lente · $message") }
    }

    private fun emit(next: EngineState, message: String) {
        state = next
        val token = generation
        main.post { if (!destroyed && token == generation) onState(next, message) }
    }

    @SuppressLint("MissingPermission")
    fun open(info: CameraInfo, video: VideoMode, texture: SurfaceTexture, preview: Size, rotationDegrees: Int) {
        worker.post {
            if (destroyed) return@post
            closeInternal()
            camera = info; mode = video
            zoomLimit = info.maxDigitalZoom
            lensFocusDistance = null
            if (settings.focusLockEnabled && !settings.focusEnabled) {
                if (info.tapFocusAvailable) {
                    focusTarget = .5f to .5f
                    focusRevision++
                    focusFeedback("nova câmera · buscando foco para travar…")
                } else focusFeedback("trava de foco físico indisponível nesta câmera")
            }
            currentIso = 200; autoIso = 200
            currentExposure = 16_666_666L; autoExposure = currentExposure
            lastMeterAt = 0L
            meterSnapshotSent = false
            lastMeterIso = null; lastMeterExposure = null
            orientation = FrameGeometry.relativeRotation(info.orientation, rotationDegrees, info.front)
            if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                emit(EngineState.ERROR, "Permita o uso da câmera para continuar."); return@post
            }
            val token = generation
            var awaitingDevice = false
            fun finishOpening() {
                if (!awaitingDevice) return
                awaitingDevice = false
                pendingOpens--
                quitWhenClosed()
            }
            emit(EngineState.OPENING, "Abrindo ${info.label}…")
            try {
                val gpu = GpuPipeline(context, worker, video.width, video.height, orientation, info.front,
                    { luminance -> if (token == generation) adaptExposure(luminance) }, { error ->
                        if (token == generation) {
                            closeInternal()
                            emit(EngineState.ERROR, "Falha no processamento GPU. Tente 720p: ${error.message}")
                        }
                    }, onPortraitStatus = { message ->
                        main.post { if (!destroyed && token == generation) onPortraitStatus(message) }
                    }, sensorOrientation = info.orientation, onStabilizationStatus = { message ->
                        main.post { if (!destroyed && token == generation) onStabilizationStatus(message) }
                    }, onHistogram = { histogram ->
                        main.post { if (!destroyed && token == generation) onHistogram(histogram) }
                    }, onScopes = { scopes ->
                        main.post { if (!destroyed && token == generation) onScopes(scopes) }
                    }, onSubjectTrack = { point ->
                        if (!destroyed && token == generation) {
                            if (point == null) trackingPolicy.update(null, SystemClock.elapsedRealtime()) else
                                trackFocusAt(point.first, point.second)
                            main.post { if (!destroyed && token == generation) onSubjectTrack(point) }
                        }
                    })
                pipeline = gpu
                gpu.initialize(texture, preview)
                gpu.setPreviewLut(previewLut)
                gpu.update(settings)
                awaitingDevice = true
                pendingOpens++
                manager.openCamera(info.id, object : CameraDevice.StateCallback() {
                    override fun onOpened(opened: CameraDevice) {
                        finishOpening()
                        if (destroyed || token != generation) { opened.close(); return }
                        device = opened
                        configure(false, token)
                    }
                    override fun onDisconnected(disconnected: CameraDevice) {
                        finishOpening()
                        disconnected.close()
                        if (token == generation) { closeInternal(); emit(EngineState.ERROR, "A câmera foi desconectada.") }
                    }
                    override fun onError(failed: CameraDevice, error: Int) {
                        finishOpening()
                        failed.close()
                        if (token == generation) { closeInternal(); emit(EngineState.ERROR, "Câmera indisponível ($error). Toque em reabrir.") }
                    }
                }, worker)
            } catch (error: Exception) {
                finishOpening()
                closeInternal(); emit(EngineState.ERROR, "Falha ao abrir câmera: ${error.message}")
            }
        }
    }

    /** Canonical displayed UV → sampled source. The snapshot is immutable and published by the capture worker. */
    fun mapDisplayedPoint(x: Float, y: Float): Pair<Float, Float> {
        val correction = pipeline?.displayStabilization ?: return x to y
        return correction.sourcePoint(x, y)
    }

    /** Source GLES coordinates to the stabilized view. Rotation/mirroring is handled by the UI geometry. */
    fun mapSourcePointToDisplayed(point: Pair<Float, Float>): Pair<Float, Float>? {
        val gpu = pipeline ?: return point
        return gpu.mapSourcePoint(point.first, point.second)
    }

    fun update(value: CaptureSettings) {
        val revision = settingsRevision.incrementAndGet()
        worker.post {
            if (destroyed) return@post
            val previous = settings
            val previousFocusTarget = focusTarget
            val previousTrackingTarget = trackingFocusTarget
            val previousLockedDistance = lockedFocusDistance
            // A stored slider value has no sensor effect until its switch is enabled.
            val hardwareChanged = previous.isoEnabled != value.isoEnabled ||
                (value.isoEnabled && previous.iso != value.iso) ||
                previous.shutterEnabled != value.shutterEnabled ||
                (value.shutterEnabled && previous.exposureNs != value.exposureNs) ||
                previous.focusEnabled != value.focusEnabled ||
                (value.focusEnabled && previous.focusDiopters != value.focusDiopters) ||
                previous.evEnabled != value.evEnabled ||
                (value.evEnabled && previous.ev != value.ev) ||
                previous.whiteBalanceEnabled != value.whiteBalanceEnabled ||
                (value.whiteBalanceEnabled && previous.whiteBalance != value.whiteBalance) ||
                previous.aeLockEnabled != value.aeLockEnabled || previous.awbLockEnabled != value.awbLockEnabled ||
                previous.zoomRatio != value.zoomRatio || previous.focusLockEnabled != value.focusLockEnabled ||
                previous.subjectTrackingEnabled != value.subjectTrackingEnabled
            settings = value
            if (previous.focusEnabled != value.focusEnabled ||
                (previous.focusLockEnabled && !value.focusLockEnabled) ||
                (previous.subjectTrackingEnabled != value.subjectTrackingEnabled && !value.focusLockEnabled)) {
                // Switching manual focus off explicitly returns to continuous autofocus.
                focusTarget = null
                trackingFocusTarget = false
                trackingPolicy.reset()
                lastTrackingLensAt = null
                lockedFocusDistance = null
                focusRevision++
            }
            if (value.focusLockEnabled && !value.focusEnabled && (!previous.focusLockEnabled || previous.focusEnabled)) {
                focusTarget = focusTarget ?: (.5f to .5f)
                trackingFocusTarget = false
                trackingPolicy.reset()
                // When the driver exposes manual lens control, freeze its reported optical position.
                lockedFocusDistance = lensFocusDistance?.takeIf { camera?.manualFocus == true }
                focusRevision++
                focusFeedback(if (lockedFocusDistance != null) "solicitando manter a posição óptica da lente…" else
                    if (currentAfState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED) "foco já travado pelo sensor" else
                    if (camera?.tapFocusAvailable == true) "buscando foco para travar…" else
                    "trava de foco físico indisponível nesta câmera")
            }
            if (previous.isoEnabled != value.isoEnabled || previous.shutterEnabled != value.shutterEnabled) {
                autoIso = currentIso; autoExposure = currentExposure
            }
            pipeline?.update(value)
            if (previous.audioMeterEnabled != value.audioMeterEnabled) refreshAudioMonitoring()
            if (hardwareChanged && (state == EngineState.READY || state == EngineState.RECORDING)) {
                try { repeat(state == EngineState.RECORDING,
                    (previous.focusEnabled && !value.focusEnabled) || (previous.focusLockEnabled != value.focusLockEnabled &&
                        (!value.focusLockEnabled || (lockedFocusDistance == null &&
                            currentAfState != CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED))) ||
                        (previous.subjectTrackingEnabled != value.subjectTrackingEnabled && !value.focusLockEnabled))
                    if (previous.focusLockEnabled && !value.focusLockEnabled)
                        focusFeedback(if (value.focusEnabled) "foco manual ativo" else "foco automático ativo")
                }
                catch (error: Exception) {
                    // Reject only this hardware edit; other controls and local effects remain independent.
                    settings = value.withHardwareFrom(previous)
                    focusTarget = previousFocusTarget
                    trackingFocusTarget = previousTrackingTarget
                    lockedFocusDistance = previousLockedDistance
                    trackingPolicy.reset()
                    focusRevision++
                    pipeline?.update(settings)
                    val reset = settings
                    try { repeat(state == EngineState.RECORDING) } catch (_: Exception) { }
                    val current = state
                    val token = generation
                    main.post {
                        if (!destroyed && token == generation && revision == settingsRevision.get()) {
                            onSettingsReset(reset)
                            onState(current, "O sensor recusou esse ajuste; valor anterior restaurado. ${error.message}")
                        }
                    }
                }
            }
        }
    }

    /** Top-left normalized coordinates in the unrotated video, independent of the portrait mask. */
    fun focusAt(sourceX: Float, sourceY: Float) {
        worker.post {
            if (destroyed) return@post
            if (state != EngineState.READY && state != EngineState.RECORDING) {
                focusFeedback("aguardando a câmera ficar pronta"); return@post
            }
            val info = camera ?: return@post
            if (settings.focusEnabled) {
                focusFeedback("foco manual ativo; desative-o para focar ao toque"); return@post
            }
            val keys = info.characteristics.availableCaptureRequestKeys.toSet()
            if (!info.afModes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) ||
                !keys.contains(CaptureRequest.CONTROL_AF_MODE) || !keys.contains(CaptureRequest.CONTROL_AF_TRIGGER)) {
                focusFeedback("foco ao toque indisponível nesta câmera")
                return@post
            }
            val previous = focusTarget
            val previousLockedDistance = lockedFocusDistance
            lockedFocusDistance = null // A new intentional tap acquires a fresh AF lock.
            focusTarget = FocusMeteringPolicy.normalizedPoint(sourceX, sourceY)
            trackingFocusTarget = false
            trackingPolicy.reset()
            lastTrackingLensAt = null
            focusRevision++
            try {
                val areaSupported = (info.characteristics[CameraCharacteristics.CONTROL_MAX_REGIONS_AF] ?: 0) > 0 &&
                    keys.contains(CaptureRequest.CONTROL_AF_REGIONS) && meteringRegion(info) != null
                focusFeedback(if (areaSupported) "focando na área tocada…" else
                    "refocando; seleção da área física indisponível")
                repeat(state == EngineState.RECORDING, true)
            } catch (error: Exception) {
                focusTarget = previous
                lockedFocusDistance = previousLockedDistance
                focusRevision++
                runCatching { repeat(state == EngineState.RECORDING) }
                focusFeedback("a câmera recusou o foco ao toque. ${error.message}")
            }
        }
    }

    /** A trusted mask centroid arrives in source GL coordinates (bottom-left); sensor mapping flips Y once. */
    fun trackFocusAt(sourceX: Float, sourceY: Float) {
        worker.post {
            if (destroyed || !settings.subjectTrackingEnabled || settings.focusEnabled || settings.focusLockEnabled ||
                (state != EngineState.READY && state != EngineState.RECORDING)) return@post
            val info = camera ?: return@post
            val keys = info.characteristics.availableCaptureRequestKeys.toSet()
            if ((info.characteristics[CameraCharacteristics.CONTROL_MAX_REGIONS_AF] ?: 0) <= 0 ||
                !keys.contains(CaptureRequest.CONTROL_AF_REGIONS)) return@post
            val now = SystemClock.elapsedRealtime()
            val tracked = trackingPolicy.update(sourceX to sourceY, now) ?: return@post
            // Mask loss/reacquisition never bypasses the physical lens's global rate limit.
            if (lastTrackingLensAt?.let { now < it || now - it < FocusTrackingPolicy.MIN_INTERVAL_MS } == true) return@post
            val continuous = info.afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO) ||
                info.afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            if (!continuous && !info.tapFocusAvailable) return@post
            val previous = focusTarget
            val previousTracking = trackingFocusTarget
            focusTarget = FocusMeteringPolicy.normalizedPoint(tracked.first, 1f - tracked.second)
            trackingFocusTarget = true
            focusRevision++
            try {
                // Continuous AF follows the new metering region without CANCEL/START hunting.
                repeat(state == EngineState.RECORDING, !continuous)
                lastTrackingLensAt = now
                focusFeedback("IA acompanhando a área do alvo; ajuste da lente pelo sensor")
            } catch (_: Exception) {
                focusTarget = previous; trackingFocusTarget = previousTracking
                focusRevision++
                runCatching { repeat(state == EngineState.RECORDING) }
                focusFeedback("o sensor recusou acompanhar a área; foco anterior mantido")
            }
        }
    }

    /** Clear the tap lock and let the lens resume its normal continuous AF mode. */
    fun clearFocusTarget() {
        worker.post {
            if (destroyed) return@post
            if (settings.focusLockEnabled && !settings.focusEnabled) {
                focusFeedback("desative Travar foco para retornar ao automático"); return@post
            }
            focusTarget = null
            trackingFocusTarget = false
            trackingPolicy.reset()
            lastTrackingLensAt = null
            lockedFocusDistance = null
            focusRevision++
            if (settings.focusEnabled) {
                focusFeedback("foco manual ativo; ponto ao toque removido"); return@post
            }
            if (state != EngineState.READY && state != EngineState.RECORDING) return@post
            try {
                repeat(state == EngineState.RECORDING, true)
                focusFeedback("foco automático ativo")
            } catch (error: Exception) {
                focusFeedback("a câmera recusou o retorno ao foco automático. ${error.message}")
            }
        }
    }

    private fun meteringRegion(info: CameraInfo): MeteringRectangle? {
        val target = focusTarget ?: return null
        val video = mode ?: return null
        val c = info.characteristics
        val sensor = if (distortionMode == CaptureRequest.DISTORTION_CORRECTION_MODE_OFF &&
            (c[CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES]?.size ?: 0) > 0) {
            c[CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE]
                ?: c[CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE]
        } else c[CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE]
        sensor ?: return null
        fun bounds(rect: Rect): FocusBounds? = if (rect.width() > 0 && rect.height() > 0)
            FocusBounds(rect.left, rect.top, rect.right, rect.bottom) else null
        val sensorBounds = bounds(sensor) ?: return null
        val visible = FocusMeteringPolicy.visibleBounds(sensorBounds, video.width, video.height, sensorCrop?.let(::bounds))
        val area = FocusMeteringPolicy.region(visible, target.first, target.second)
        return MeteringRectangle(Rect(area.left, area.top, area.right, area.bottom), MeteringRectangle.METERING_WEIGHT_MAX)
    }

    private data class EncoderSelection(val name: String, val bitrate: Int)

    /** Validate the final output geometry and clamp the requested budget against that AVC candidate. */
    private fun selectEncoder(width: Int, height: Int, fps: Int, requestedBitrate: Int): EncoderSelection? =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.asSequence()
            .filter { it.isEncoder && it.supportedTypes.any { type -> type.equals("video/avc", true) } }
            .sortedBy { if (it.isHardwareAccelerated) 0 else 1 }
            .mapNotNull { codec -> runCatching {
                val capabilities = codec.getCapabilitiesForType("video/avc")
                val video = capabilities.videoCapabilities ?: return@runCatching null
                if (!capabilities.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) ||
                    !video.areSizeAndRateSupported(width, height, fps.toDouble())) return@runCatching null
                val bitrate = CapturePolicy.scaledBitrate(requestedBitrate, 1f, video.bitrateRange.lower, video.bitrateRange.upper)
                val format = MediaFormat.createVideoFormat("video/avc", width, height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                    setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                }
                if (capabilities.isFormatSupported(format)) EncoderSelection(codec.name, bitrate) else null
            }.getOrNull() }.firstOrNull()

    fun startRecording(audio: Boolean) {
        worker.post {
            if (destroyed || state != EngineState.READY) return@post
            val video = mode ?: return@post
            activeOptions = recordingOptions.sanitized()
            completedParts.clear()
            nextOutput = null
            retainedRecordingBytes = 0L
            segmentSnapshot = RecordingSegmentState(1, activeOptions.splitEnabled)
            audioRoute = AudioRouteStatus(activeOptions.audioDeviceId)
            routeSelectionWarning = null
            emit(EngineState.STARTING, "Preparando gravação…")
            try {
                // Rotate pixels in the GPU, keeping the MP4 display matrix neutral in every player.
                val orientedDimensions = FrameGeometry.orientedSize(video.width, video.height, orientation)
                val requestedBitrate = CapturePolicy.scaledBitrate(video.bitrate, settings.videoBitrateScale)
                val orientedEncoder = selectEncoder(orientedDimensions.width, orientedDimensions.height, video.fps, requestedBitrate)
                val physicallyRotated = orientedEncoder != null
                val dimensions = if (physicallyRotated) orientedDimensions else FrameGeometry.Dimensions(video.width, video.height)
                val encoder = orientedEncoder ?: selectEncoder(video.width, video.height, video.fps, requestedBitrate)
                    ?: error("Encoder não oferece este modo de vídeo. Tente 720p/30.")
                android.util.Log.i("LumaGeometry", "EncoderCandidate=${encoder.name} ${dimensions.width}x${dimensions.height} bitrate=${encoder.bitrate} physicalRotation=$physicallyRotated hint=${if (physicallyRotated) 0 else orientation}")
                val dir = File(context.filesDir, "recordings")
                check(dir.isDirectory || dir.mkdirs()) { "Não foi possível abrir a pasta de gravações." }
                val free = (StatFs(dir.path).availableBytes - VideoStore.pendingPublicationBytes()).coerceAtLeast(0L)
                check(StoragePolicy.canStart(free)) { "Libere pelo menos 160 MB para gravar e salvar." }
                val profileSuffix = when {
                    settings.logEnabled -> "_LOGv${settings.logProfileVersion}_${(settings.logStrength * 100f).toInt()}pct"
                    settings.flatEnabled -> "_FLAT"
                    else -> ""
                }
                // One stable session prefix groups parts. createNewFile reserves every path atomically.
                val identification = RecordingSegmentPolicy.filenameIdentity(activeOptions.projectName,
                    activeOptions.sceneName, activeOptions.takeNumber)
                recordingPrefix = "LUMA_${identification}_${SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())}${profileSuffix}_" +
                    UUID.randomUUID().toString().replace("-", "").take(12)
                output = reservePart(dir, 1)
                @Suppress("DEPRECATION")
                val newRecorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
                recorder = newRecorder
                val hasAudio = audio && context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                recordingHasAudio = hasAudio
                if (hasAudio) newRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
                newRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
                newRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                newRecorder.setOutputFile(output!!.absolutePath)
                newRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                newRecorder.setVideoSize(dimensions.width, dimensions.height)
                newRecorder.setVideoFrameRate(video.fps)
                newRecorder.setVideoEncodingBitRate(encoder.bitrate)
                newRecorder.setOrientationHint(if (physicallyRotated) 0 else orientation)
                if (hasAudio) {
                    newRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    newRecorder.setAudioEncodingBitRate(128_000)
                    newRecorder.setAudioSamplingRate(44_100)
                }
                recordingFileLimit = RecordingSegmentPolicy.segmentLimitBytes(activeOptions, free)
                newRecorder.setMaxFileSize(recordingFileLimit)
                newRecorder.setOnInfoListener { source, what, _ -> recorderCallback(source) {
                    when (what) {
                        MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING -> queueNextPart(source)
                        MediaRecorder.MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED -> confirmNextPart()
                        MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED -> {
                            stopInternal()
                            emit(state, "Limite seguro alcançado; partes preservadas para salvar. Confira a biblioteca.")
                        }
                    }
                } }
                newRecorder.setOnErrorListener { source, what, extra -> recorderCallback(source) {
                    stopInternal()
                    emit(state, "O gravador interrompeu o vídeo ($what/$extra). Confira o arquivo na biblioteca.")
                } }
                newRecorder.prepare()
                if (hasAudio) prepareAudioRoute(newRecorder)
                // Camera session stays unchanged. GPU feeds the recorder's surface.
                pipeline?.attachEncoder(newRecorder.surface, video.fps, physicallyRotated)
                    ?: error("Processamento GPU indisponível")
                newRecorder.start()
                recordingStartedAtNs = System.nanoTime()
                segmentStartedAtNs = recordingStartedAtNs
                repeat(true)
                recordingBitrate = encoder.bitrate
                emit(EngineState.RECORDING, "Gravando · efeitos locais no MP4 · ${video.fps} fps alvo")
                if (hasAudio) {
                    refreshAudioRoute(newRecorder)
                    worker.postDelayed({ refreshAudioRoute(newRecorder) }, 700L)
                    routeSelectionWarning?.let { emit(EngineState.RECORDING, it) }
                } else audioRoute = AudioRouteStatus(activeOptions.audioDeviceId, label = "Sem áudio",
                    message = "Esta tomada está sendo gravada sem áudio.")
                refreshAudioMonitoring()
                worker.removeCallbacks(storageMonitor)
                worker.postDelayed(storageMonitor, 1_000L)
            } catch (error: Exception) { recoverRecording("Falha ao preparar vídeo. Tente 720p/30: ${error.message}") }
        }
    }

    /** Recorder callbacks already use this looper. Re-posting lets an enqueued STOP overtake a switch. */
    private fun recorderCallback(source: MediaRecorder, callback: () -> Unit) {
        val event = Runnable {
            if (!destroyed && recorder === source && state == EngineState.RECORDING) callback()
        }
        if (Looper.myLooper() == worker.looper) event.run() else worker.post(event)
    }

    private fun reservePart(directory: File, index: Int): File {
        val file = File(directory, recordingPrefix + RecordingSegmentPolicy.partSuffix(index) + ".mp4")
        check(file.createNewFile()) { "A saída dessa parte já existe; original preservado." }
        VideoStore.beginRecording(file)
        runCatching {
            VideoStore(context).writeRecordingMetadata(file, activeOptions, recordingPrefix, index,
                "Rota ainda não confirmada")
        }.onFailure { android.util.Log.w("LumaRecording", "Receipt could not be saved", it) }
        return file
    }

    private fun prepareAudioRoute(source: MediaRecorder) {
        val preferred = activeOptions.audioDeviceId?.let { id -> runCatching {
            audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == id && it.isSource }
        }.getOrNull() }
        val accepted = runCatching { source.setPreferredDevice(preferred) }.getOrDefault(false)
        if (activeOptions.audioDeviceId != null && (preferred == null || !accepted)) {
            runCatching { source.setPreferredDevice(null) }
            routeSelectionWarning = "Seleção do microfone recusada ou desconectada; REC usa a rota automática. Confira a entrada real em Áudio."
        }
        val listener = AudioRouting.OnRoutingChangedListener {
            if (recorder === source && state == EngineState.RECORDING) {
                refreshAudioRoute(source)
                if (audioRoute.preferredDeviceId != null && audioRoute.confirmed &&
                    audioRoute.actualDeviceId != audioRoute.preferredDeviceId)
                    emit(EngineState.RECORDING, audioRoute.message)
            }
        }
        routingListener = listener
        runCatching { source.addOnRoutingChangedListener(listener, worker) }
    }

    private fun queueNextPart(source: MediaRecorder) {
        if (!activeOptions.splitEnabled || nextOutput != null) return
        val current = output ?: return
        val free = runCatching { StatFs(current.parentFile!!.path).availableBytes }.getOrNull() ?: return
        // Retained files stay closed to readers until recorder.stop(), as required by setNextOutputFile.
        if (!RecordingSegmentPolicy.canQueueNext(free, retainedBytes(), recordingFileLimit, VideoStore.pendingPublicationBytes())) {
            emit(EngineState.RECORDING, "Pouco espaço para outra parte: esta tomada terminará no próximo limite seguro.")
            return
        }
        val index = segmentSnapshot.index + 1
        var reserved: File? = null
        try {
            reserved = reservePart(current.parentFile!!, index)
            // Preserve ownership even if the vendor throws after accepting a descriptor.
            nextOutput = reserved
            source.setNextOutputFile(reserved)
            segmentSnapshot = segmentSnapshot.copy(queuedNext = true)
        } catch (error: Exception) {
            // Pending files are never opened or removed before native stop/release.
            if (reserved == null) nextOutput = null
            emit(EngineState.RECORDING, "O gravador recusou a próxima parte; finalizará no limite atual. ${error.message}")
        }
    }

    private fun confirmNextPart() {
        val next = nextOutput ?: return
        val previous = output ?: return
        val now = System.nanoTime()
        val duration = RecordingTimeline.elapsedMs(segmentStartedAtNs, now, true)
        recorder?.let(::refreshAudioRoute)
        completedParts += CompletedPart(previous, segmentSnapshot.index, duration.takeIf { it > 0L }, audioRoute.message)
        output = next
        nextOutput = null
        segmentStartedAtNs = now
        segmentSnapshot = RecordingSegmentState(segmentSnapshot.index + 1, true, false, completedParts.size)
        retainedBytes()
        emit(EngineState.RECORDING, "Gravando parte ${segmentSnapshot.index} · ${completedParts.size} parte(s) preservada(s)")
    }

    @Suppress("DEPRECATION")
    private fun configure(recording: Boolean, token: Int) {
        val opened = device ?: return
        val surface = pipeline?.cameraSurface ?: return
        val sessionToken = ++sessionGeneration
        session?.close(); session = null
        val surfaces = mutableListOf(surface)
        try {
            opened.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(configured: CameraCaptureSession) {
                    if (destroyed || token != generation || sessionToken != sessionGeneration || device !== opened) { configured.close(); return }
                    session = configured
                    try {
                        repeat(recording, true)
                        emit(EngineState.READY, "Pronto · efeitos locais disponíveis")
                    } catch (error: Exception) {
                        if (recording) recoverRecording("Gravação recusada. Tente um modo menor: ${error.message}")
                        else { closeInternal(); emit(EngineState.ERROR, "Falha na prévia: ${error.message}") }
                    }
                }
                override fun onConfigureFailed(failed: CameraCaptureSession) {
                    failed.close()
                    if (token != generation || sessionToken != sessionGeneration) return
                    if (recording) recoverRecording("Essa combinação foi recusada. Selecione 720p/30 e tente novamente.")
                    else { closeInternal(); emit(EngineState.ERROR, "A câmera recusou a prévia. Toque em reabrir.") }
                }
            }, worker)
        } catch (error: Exception) {
            if (recording) recoverRecording("Sessão de vídeo indisponível: ${error.message}")
            else { closeInternal(); emit(EngineState.ERROR, "Sessão de câmera indisponível: ${error.message}") }
        }
    }

    private fun repeat(recording: Boolean, triggerAutoFocus: Boolean = false) {
        val info = checkNotNull(camera) { "Câmera ainda não selecionada" }
        val video = checkNotNull(mode) { "Modo de vídeo indisponível" }
        val opened = checkNotNull(device) { "Câmera já encerrada" }
        val active = checkNotNull(session) { "Sessão de câmera já encerrada" }
        val request = opened.createCaptureRequest(if (recording) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW)
        request.addTarget(checkNotNull(pipeline) { "Prévia GPU já encerrada" }.cameraSurface)
        val keys = info.characteristics.availableCaptureRequestKeys.toSet()
        var needsAutoFocusTrigger = false
        var hasFocusRegion = false
        fun <T> set(key: CaptureRequest.Key<T>, value: T) { if (keys.contains(key)) request.set(key, value) }
        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        val c = info.characteristics
        val sensor = if (distortionMode == CaptureRequest.DISTORTION_CORRECTION_MODE_OFF)
            c[CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE]
                ?: c[CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE]
            else c[CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE]
        if (sensor != null && sensor.width() > 0 && sensor.height() > 0 && keys.contains(CaptureRequest.SCALER_CROP_REGION)) {
            val crop = CameraZoomPolicy.crop(FocusBounds(sensor.left, sensor.top, sensor.right, sensor.bottom),
                settings.zoomRatio, info.maxDigitalZoom)
            val rect = Rect(crop.left, crop.top, crop.right, crop.bottom)
            set(CaptureRequest.SCALER_CROP_REGION, rect)
            // Focus rectangles use the crop of this request, not a stale capture result during a zoom move.
            sensorCrop = rect
        }
        val fpsRange = info.fpsRanges.filter { it.contains(video.fps) }
            .minWithOrNull(compareBy<android.util.Range<Int>> { it.upper - it.lower }.thenBy { it.upper })
        if (fpsRange != null) set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
        val manualExposure = (settings.isoEnabled || settings.shutterEnabled) && info.manualSensor
        if (manualExposure) {
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            set(CaptureRequest.SENSOR_SENSITIVITY, info.isoRange!!.clamp(if (settings.isoEnabled) settings.iso else autoIso))
            val exposure = CapturePolicy.clampExposureNs(if (settings.shutterEnabled) settings.exposureNs else autoExposure, info.exposureRange!!.lower,
                info.exposureRange.upper, video.fps)
            set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure)
            set(CaptureRequest.SENSOR_FRAME_DURATION, CapturePolicy.frameDurationNs(video.fps))
        } else {
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, info.evRange.clamp(if (settings.evEnabled) settings.ev else 0))
        }
        if (info.aeLockAvailable) set(CaptureRequest.CONTROL_AE_LOCK,
            CapturePolicy.autoLock(settings.aeLockEnabled, info.aeLockAvailable, !manualExposure))
        if ((settings.focusEnabled || (settings.focusLockEnabled && lockedFocusDistance != null)) && info.manualFocus) {
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            set(CaptureRequest.LENS_FOCUS_DISTANCE,
                (if (settings.focusEnabled) settings.focusDiopters else checkNotNull(lockedFocusDistance)).coerceIn(0f, info.minFocus))
        } else {
            val af = when {
                trackingFocusTarget && !settings.focusLockEnabled &&
                    info.afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO) -> CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                trackingFocusTarget && !settings.focusLockEnabled &&
                    info.afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) -> CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                focusTarget != null && info.afModes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) -> CaptureRequest.CONTROL_AF_MODE_AUTO
                info.afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO) -> CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                info.afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) -> CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                info.afModes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) -> CaptureRequest.CONTROL_AF_MODE_AUTO
                else -> CaptureRequest.CONTROL_AF_MODE_OFF
            }
            set(CaptureRequest.CONTROL_AF_MODE, af)
            needsAutoFocusTrigger = af == CaptureRequest.CONTROL_AF_MODE_AUTO
            val area = meteringRegion(info)
            if (area != null) {
                if ((info.characteristics[CameraCharacteristics.CONTROL_MAX_REGIONS_AF] ?: 0) > 0 &&
                    keys.contains(CaptureRequest.CONTROL_AF_REGIONS)) {
                    set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(area))
                    hasFocusRegion = true
                }
                if (!settings.isoEnabled && !settings.shutterEnabled &&
                    (info.characteristics[CameraCharacteristics.CONTROL_MAX_REGIONS_AE] ?: 0) > 0)
                    set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(area))
            }
        }
        val wb = if (settings.whiteBalanceEnabled) settings.whiteBalance else CaptureRequest.CONTROL_AWB_MODE_AUTO
        if (info.awbModes.contains(wb)) set(CaptureRequest.CONTROL_AWB_MODE, wb)
        if (info.awbLockAvailable) set(CaptureRequest.CONTROL_AWB_LOCK,
            CapturePolicy.autoLock(settings.awbLockEnabled, info.awbLockAvailable,
                wb == CaptureRequest.CONTROL_AWB_MODE_AUTO && info.awbModes.contains(wb)))
        if (triggerAutoFocus && !settings.focusEnabled &&
            !(settings.focusLockEnabled && lockedFocusDistance != null) && keys.contains(CaptureRequest.CONTROL_AF_TRIGGER)) {
            // CANCEL and START are single captures. Repeating START would keep restarting the lens.
            request.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
            active.capture(request.build(), null, worker)
            if (needsAutoFocusTrigger) {
                request.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                active.capture(request.build(), null, worker)
            }
            request.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
        }
        val token = generation
        val focusToken = focusRevision
        active.setRepeatingRequest(request.build(), object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                if (token != generation || this@CameraEngine.session !== session) return
                val now = SystemClock.elapsedRealtime()
                result[CaptureResult.SCALER_CROP_REGION]?.let { sensorCrop = Rect(it) }
                result[CaptureResult.DISTORTION_CORRECTION_MODE]?.let { distortionMode = it }
                result[CaptureResult.LENS_FOCUS_DISTANCE]?.takeIf { it.isFinite() && it >= 0f }?.let { lensFocusDistance = it }
                currentAfState = result[CaptureResult.CONTROL_AF_STATE]
                if (settings.focusLockEnabled && lockedFocusDistance != null && focusToken == focusRevision &&
                    lastFocusResultRevision != focusToken && result[CaptureResult.LENS_STATE] != CaptureResult.LENS_STATE_MOVING &&
                    result[CaptureResult.LENS_FOCUS_DISTANCE]?.let {
                        kotlin.math.abs(it - checkNotNull(lockedFocusDistance)) <= .05f
                    } == true) {
                    lastFocusResultRevision = focusToken
                    focusFeedback("foco travado · posição óptica confirmada pelo sensor")
                }
                if (focusTarget != null && focusToken == focusRevision && lastFocusResultRevision != focusToken) {
                    when (result[CaptureResult.CONTROL_AF_STATE]) {
                        CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED -> {
                            lastFocusResultRevision = focusToken
                            focusFeedback(if (hasFocusRegion) "foco travado e confirmado na área" else
                                "foco confirmado; seleção da área física indisponível")
                        }
                        CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED -> if (trackingFocusTarget) {
                            lastFocusResultRevision = focusToken
                            focusFeedback("IA acompanhando · foco da lente confirmado pelo sensor")
                        }
                        CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> {
                            lastFocusResultRevision = focusToken
                            focusFeedback("foco não confirmado; toque novamente ou retorne ao automático")
                        }
                    }
                }
                result[CaptureResult.SENSOR_SENSITIVITY]?.let { currentIso = it }
                result[CaptureResult.SENSOR_EXPOSURE_TIME]?.let { currentExposure = it }
                if (now - lastMeterAt >= 500) {
                    lastMeterAt = now
                    val iso = result[CaptureResult.SENSOR_SENSITIVITY]
                    val exposure = result[CaptureResult.SENSOR_EXPOSURE_TIME]
                    if (!meterSnapshotSent || iso != lastMeterIso || exposure != lastMeterExposure) {
                        meterSnapshotSent = true
                        lastMeterIso = iso; lastMeterExposure = exposure
                        main.post { if (!destroyed && token == generation) onMeter(iso, exposure) }
                    }
                }
            }
        }, worker)
    }

    fun stopRecording() { worker.post { if (state == EngineState.RECORDING) stopInternal() } }

    private fun adaptExposure(luminance: Float) {
        val info = camera ?: return
        val video = mode ?: return
        if (!info.manualSensor || settings.isoEnabled == settings.shutterEnabled ||
            (state != EngineState.READY && state != EngineState.RECORDING)) return
        try {
            val target = if (settings.evEnabled) (.45f * Math.pow(2.0, (settings.ev * info.evStep).toDouble()).toFloat()).coerceIn(.1f, .85f) else .45f
            val next = ExposureAutomation.next(settings.isoEnabled, settings.shutterEnabled,
                if (settings.isoEnabled) settings.iso else currentIso,
                if (settings.shutterEnabled) settings.exposureNs else currentExposure,
                luminance, target, info.isoRange!!.lower, info.isoRange.upper,
                info.exposureRange!!.lower, info.exposureRange.upper, CapturePolicy.frameDurationNs(video.fps))
            autoIso = next.iso; autoExposure = next.exposureNs
            repeat(state == EngineState.RECORDING)
        } catch (_: Exception) { /* Keep the last working capture request. */ }
    }

    private fun stopInternal(restorePreview: Boolean = true) {
        val finalPartDurationMs = RecordingTimeline.elapsedMs(segmentStartedAtNs, System.nanoTime(),
            state == EngineState.RECORDING)
        worker.removeCallbacks(storageMonitor)
        stopAudioMonitoring()
        recorder?.let(::refreshAudioRoute)
        emit(EngineState.SAVING, "Finalizando e salvando…")
        var stopError: Exception? = null
        try {
            recorder?.stop()
        } catch (error: Exception) { stopError = error }
        // The worker is serial: no new GPU frame can be submitted while stop finalizes the muxer.
        // Keep the producer surface alive until the native recorder has drained its final samples.
        try { pipeline?.detachEncoder() } catch (_: Exception) { }
        val route = audioRoute.message
        releaseRecorder()
        val parts = drainRecordingParts(finalPartDurationMs)
        enqueuePublication(parts, activeOptions, recordingPrefix, route, stopError != null)
        if (restorePreview && device != null) {
            try { repeat(false); emit(EngineState.READY, "Pronto · ${parts.size} parte(s) sendo salva(s) em segundo plano") }
            catch (error: Exception) { closeInternal(); emit(EngineState.ERROR, "Falha ao retomar prévia: ${error.message}") }
        }
    }

    private fun recoverRecording(message: String) {
        val finalPartDurationMs = RecordingTimeline.elapsedMs(segmentStartedAtNs, System.nanoTime(),
            state == EngineState.RECORDING)
        worker.removeCallbacks(storageMonitor)
        stopAudioMonitoring()
        try { recorder?.stop() } catch (_: Exception) { }
        try { pipeline?.detachEncoder() } catch (_: Exception) { }
        val route = audioRoute.message
        releaseRecorder()
        val parts = drainRecordingParts(finalPartDurationMs)
        enqueuePublication(parts, activeOptions, recordingPrefix, route, true)
        val recoveryMessage = "$message ${parts.size} saída(s) serão verificadas; originais não vazios preservados."
        try { repeat(false); emit(EngineState.READY, recoveryMessage) }
        catch (error: Exception) { closeInternal(); emit(EngineState.ERROR, "Falha ao restaurar câmera: ${error.message}") }
    }

    private fun drainRecordingParts(finalPartDurationMs: Long): List<CompletedPart> {
        val parts = completedParts.toMutableList()
        val finalEstimate = RecordingSegmentPolicy.finalPartDurationEstimate(finalPartDurationMs, nextOutput != null)
        output?.let { parts += CompletedPart(it, segmentSnapshot.index.coerceAtLeast(1), finalEstimate) }
        // A final native event can be delayed behind STOP. Inspect the queued file only now, after stop/release.
        nextOutput?.let { parts += CompletedPart(it, segmentSnapshot.index + 1, null) }
        completedParts.clear()
        output = null; nextOutput = null
        segmentStartedAtNs = null
        retainedRecordingBytes = 0L
        segmentSnapshot = segmentSnapshot.copy(queuedNext = false, completedParts = parts.count { it.file.length() > 0L })
        return parts.distinctBy { it.file.absolutePath }
    }

    private fun enqueuePublication(parts: List<CompletedPart>, options: RecordingOptions, sessionId: String,
        route: String, interrupted: Boolean) {
        if (parts.isEmpty()) return
        parts.forEach { VideoStore.queuePublication(it.file) }
        val token = generation
        publisher.execute {
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
            val store = VideoStore(context.applicationContext)
            var saved = 0
            var preserved = 0
            var discarded = 0
            parts.forEach { part ->
                try {
                    if (StoragePolicy.canDiscardIncomplete(part.file.length())) {
                        part.file.delete(); discarded++
                    } else {
                        runCatching { store.writeRecordingMetadata(part.file, options, sessionId, part.index,
                            part.audioAtEnd ?: route, part.durationMs) }
                        if (store.hasVideoSamples(part.file)) {
                            val uri = store.publish(part.file, part.durationMs)
                            saved++
                            main.post { if (!destroyed) onSaved(uri) }
                        } else preserved++
                    }
                } catch (error: Exception) {
                    preserved++
                    android.util.Log.w("LumaRecording", "Part ${part.index} retained: ${part.file.name}", error)
                } finally { VideoStore.finishPublication(part.file) }
            }
            worker.post {
                if (!destroyed && token == generation) {
                    val message = when {
                        preserved > 0 -> "$saved parte(s) salva(s); $preserved original(is) preservado(s) para recuperar na biblioteca."
                        saved > 0 && interrupted -> "$saved parte(s) recuperada(s) após interrupção. Confira imagem e áudio na biblioteca."
                        saved > 0 -> "$saved parte(s) salva(s) na biblioteca."
                        discarded > 0 -> "Saídas vazias descartadas. Aguarde alguns segundos antes de parar a próxima gravação."
                        else -> "Confira as saídas da tomada na biblioteca."
                    }
                    emit(state, message)
                }
            }
        }
    }

    private fun releaseRecorder() {
        worker.removeCallbacks(storageMonitor)
        stopAudioMonitoring()
        recordingHasAudio = false
        recordingBitrate = null
        recordingStartedAtNs = null
        val previous = recorder
        recorder = null // Already invalidates any delayed listener from this recorder.
        runCatching { previous?.setOnInfoListener(null) }
        runCatching { previous?.setOnErrorListener(null) }
        routingListener?.let { listener -> runCatching { previous?.removeOnRoutingChangedListener(listener) } }
        routingListener = null
        runCatching { previous?.release() }
        audioRoute = AudioRouteStatus(recordingOptions.audioDeviceId)
    }

    private fun closeInternal() {
        generation++
        sessionGeneration++
        focusTarget = null
        trackingFocusTarget = false
        trackingPolicy.reset()
        lastTrackingLensAt = null
        lockedFocusDistance = null
        currentAfState = null
        focusStatus = "Automático"
        lensFocusDistance = null
        focusRevision++
        sensorCrop = null
        distortionMode = null
        if (state == EngineState.RECORDING) stopInternal(false)
        val previousSession = session; session = null
        runCatching { previousSession?.close() }
        val previousDevice = device; device = null
        runCatching { previousDevice?.close() }
        releaseRecorder()
        // Also preserve/inspect preparations and queued parts after all native ownership is released.
        if (output != null || nextOutput != null || completedParts.isNotEmpty())
            enqueuePublication(drainRecordingParts(0L), activeOptions, recordingPrefix, audioRoute.message, true)
        val previousPipeline = pipeline; pipeline = null
        runCatching { previousPipeline?.close() }
        emit(EngineState.CLOSED, "Câmera pausada")
    }

    fun pause() { worker.post { closeInternal() } }
    fun releaseTexture(texture: SurfaceTexture) {
        if (!worker.post { closeInternal(); texture.release() }) texture.release()
    }
    fun destroy() {
        destroyed = true
        worker.post { closeInternal(); publisher.shutdown(); quitWhenClosed() }
    }

    private fun quitWhenClosed() {
        // An in-flight open owns a future CameraDevice. Keep its callback worker alive
        // so it can close that device even after the Activity has been destroyed.
        if (destroyed && pendingOpens == 0) thread.quitSafely()
    }
}
