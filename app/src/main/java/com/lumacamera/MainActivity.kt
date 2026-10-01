package com.lumacamera

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.animation.ValueAnimator
import android.text.TextUtils
import android.view.animation.DecelerateInterpolator
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.*
import android.widget.*
import com.lumacamera.camera.*
import com.lumacamera.core.VideoMode
import com.lumacamera.core.FrameGeometry
import com.lumacamera.core.SubjectFocusPolicy
import com.lumacamera.core.AutomaticBlurPolicy
import com.lumacamera.core.CompositionPolicy
import com.lumacamera.core.ProfessionalCaptureTools
import com.lumacamera.core.CapturePolicy
import com.lumacamera.core.ResponsiveUiPolicy
import com.lumacamera.core.CameraReopenPolicy
import com.lumacamera.core.MediaTime
import com.lumacamera.core.StoragePolicy
import com.lumacamera.core.ToolCatalog
import com.lumacamera.core.CameraTool
import com.lumacamera.core.WorkspacePolicy
import com.lumacamera.core.CameraMotionPolicy
import com.lumacamera.core.RecordingOptions
import com.lumacamera.effects.SimulatedLog
import com.lumacamera.effects.MonitorHistogram
import com.lumacamera.effects.MonitorScopes
import android.text.Editable
import android.text.TextWatcher
import org.json.JSONObject
import com.lumacamera.ui.*
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Camera-first workspace; each control remains independently switchable. */
class MainActivity : Activity(), TextureView.SurfaceTextureListener {
    private enum class Page(val title: String) {
        HOME("Ferramentas"), PRESETS("Presets"), VIDEO("Gravação"), AUDIO("Áudio"), LOOK("Imagem"), MONITOR("Monitores"), SENSOR("Exposição"), FOCUS("Foco"), ZOOM("Zoom"), STABILIZATION("Estabilização"), PROJECT("Projetos"), APP("App")
    }
    private lateinit var workspaceStore: ProfessionalWorkspaceStore
    private lateinit var lutStore: PreviewLutStore
    // One parse in flight and only the latest pending selection; rapid changes stay bounded.
    private val lutLoader = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(1), { task -> Thread(task, "LumaPreviewLut").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardOldestPolicy())
    private var workspace = WorkspaceSettings()
    private var scopeOverlay: ScopeOverlay? = null
    private var menuScopes: ScopeOverlay? = null
    private var lastScopes: MonitorScopes? = null
    private var audioRouteLabel: TextView? = null
    private var partStatusLabel: TextView? = null
    private var savedToastAt = Long.MIN_VALUE
    private var lutLoadRevision = 0L
    private var motion: Runnable? = null
    private var motionKind: String? = null
    private val quickSlots = mutableListOf<LinearLayout>()
    private val sectionAnchors = mutableMapOf<String, View>()
    private var pendingTool: String? = null
    private var searchQuery = ""
    private var submittedWorkspace: WorkspaceSettings? = null
    private lateinit var engine: CameraEngine
    private lateinit var catalog: CameraCatalog
    private lateinit var preferences: CameraPreferences
    private lateinit var appPreferences: AppPreferences
    private lateinit var texture: TextureView
    private lateinit var frame: FrameLayout
    private lateinit var grid: GridOverlay
    private lateinit var histogram: HistogramOverlay
    private lateinit var audioMeter: AudioMeterView
    private lateinit var focusReticle: FocusReticle
    private lateinit var portraitStatus: TextView
    private lateinit var stabilizationStatus: TextView
    private lateinit var lensStatus: TextView
    private lateinit var status: TextView
    private lateinit var meter: TextView
    private lateinit var formatChip: TextView
    private lateinit var lensLabel: TextView
    private lateinit var effectBadge: TextView
    private lateinit var sessionLabel: TextView
    private lateinit var timerLabel: TextView
    private lateinit var recordLabel: TextView
    private lateinit var emptyPreview: LinearLayout
    private lateinit var recordButton: RecordShutterView
    private lateinit var galleryButton: CameraIconButton
    private lateinit var lensButton: CameraIconButton
    private lateinit var gridButton: CameraIconButton
    private lateinit var controls: LinearLayout
    private var controlsScroll: ScrollView? = null
    private var builtPage: Page? = null
    private var uiLayout = ResponsiveUiPolicy.layout(360f, 720f)
    private lateinit var hudTop: LinearLayout
    private lateinit var hudBottom: LinearLayout
    private lateinit var focusStatusRow: LinearLayout
    private lateinit var monitorRow: LinearLayout
    private lateinit var monitorToggle: TextView
    private var monitorLayoutPending = false
    private var menuHistogram: HistogramOverlay? = null
    private var menuAudioMeter: AudioMeterView? = null
    private var lastHistogram: MonitorHistogram? = null
    private var wbButton: TextView? = null
    private var sheet: Dialog? = null
    private var sheetTitle: TextView? = null
    private var page = Page.LOOK
    private var imageAdjustmentsExpanded = false
    private var blurRefinementsExpanded = false
    private val tabButtons = mutableMapOf<Page, TextView>()
    private val switches = mutableListOf<Triple<Switch, () -> Boolean, () -> Boolean>>()
    private val preferenceSwitches = mutableListOf<Triple<Switch, () -> Boolean, () -> Boolean>>()
    private val sliders = mutableListOf<Pair<SeekBar, () -> Boolean>>()
    private val menuButtons = mutableListOf<Pair<View, () -> Boolean>>()
    private var refreshingControls = false
    private var sliderChangeInProgress = false
    private val ui = Handler(Looper.getMainLooper())
    private var cameras = emptyList<CameraInfo>()
    private var allCameras = emptyList<CameraInfo>()
    private var cameraIndex = 0
    private var video: VideoMode? = null
    private var settings = CaptureSettings()
    private var state = EngineState.CLOSED
    private var active = false
    private var savedUri: Uri? = null
    private var startedAt = 0L
    private var pendingAudioRecording = false
    private var permissionInFlight = false
    private var cameraOpening = false
    private enum class RecordingCommand { START, STOP }
    private var recordingCommand: RecordingCommand? = null
    private var audioPrompt: AlertDialog? = null
    private var conditionsDialog: AlertDialog? = null
    private var openedIdentity: CameraReopenPolicy.Identity? = null
    private var openedTexture: SurfaceTexture? = null
    private var volumeKeyHeld = false
    private var lutExportStrength = 1f
    private var lutExportProfileVersion = 2
    private var capturePreferencesDirty = false
    private var lastDisplayedSecond = -1L
    private val sensors by lazy { getSystemService(SensorManager::class.java) }
    private var levelSensor: Sensor? = null
    private var levelListening = false
    private var lastLevelAt = 0L
    private val sensorRotation = FloatArray(9)
    private val filteredGravity = FloatArray(3)
    private val levelListener = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { }
        override fun onSensorChanged(event: SensorEvent) {
            if (!active || !appPreferences.level) return
            val now = SystemClock.elapsedRealtime()
            if (now - lastLevelAt < 100L) return
            lastLevelAt = now
            var x: Float
            var y: Float
            if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
                SensorManager.getRotationMatrixFromVector(sensorRotation, event.values)
                x = sensorRotation[6]; y = sensorRotation[7]
            } else {
                for (index in 0..2) filteredGravity[index] = if (event.sensor.type == Sensor.TYPE_ACCELEROMETER)
                    filteredGravity[index] * .8f + event.values[index] * .2f else event.values[index]
                val length = sqrt(filteredGravity.sumOf { (it * it).toDouble() }).toFloat()
                x = if (length > .01f) filteredGravity[0] / length else Float.NaN
                y = if (length > .01f) filteredGravity[1] / length else Float.NaN
            }
            grid.rollDegrees = CompositionPolicy.horizonRoll(x, y, rotationDegrees()) ?: Float.NaN
        }
    }
    private val saveCapturePreferences = Runnable { flushCapturePreferences() }
    private val accent = CameraPalette.lime
    private val muted = CameraPalette.muted
    private val backgroundColor = CameraPalette.background
    private val panelColor = CameraPalette.surface
    private val selected: CameraInfo? get() = cameras.getOrNull(cameraIndex)
    private val busy: Boolean get() = cameraOpening || recordingCommand != null || state == EngineState.OPENING || state == EngineState.STARTING ||
        state == EngineState.RECORDING || state == EngineState.SAVING
    private val adjustable: Boolean get() = !cameraOpening && recordingCommand == null &&
        (state == EngineState.READY || state == EngineState.RECORDING)

    private val ticker = object : Runnable {
        override fun run() {
            if (state == EngineState.RECORDING) {
                val elapsed = engine.recordingElapsedMs()
                val seconds = elapsed / 1000
                if (seconds != lastDisplayedSecond) {
                    lastDisplayedSecond = seconds
                    timerLabel.text = MediaTime.format(elapsed, allowZero = true)
                    audioRouteLabel?.setTextIfChanged(engine.audioRouteStatus().message)
                    partStatusLabel?.setTextIfChanged("Parte atual: ${engine.recordingSegmentState().index}")
                }
                ui.postDelayed(this, 1000L - elapsed % 1000L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = backgroundColor
        window.navigationBarColor = backgroundColor
        preferences = CameraPreferences(this)
        appPreferences = preferences.loadAppPreferences()
        settings = preferences.loadCaptureSettings()
        workspaceStore = ProfessionalWorkspaceStore(this)
        workspace = workspaceStore.load().copy(audioDeviceId = null)
        lutStore = PreviewLutStore(this)
        lutExportStrength = savedInstanceState?.getFloat("lutExportStrength", settings.logStrength) ?: settings.logStrength
        lutExportProfileVersion = savedInstanceState?.getInt("lutExportProfileVersion", settings.logProfileVersion) ?: settings.logProfileVersion
        applyScreenPreference()
        catalog = CameraCatalog(getSystemService(CameraManager::class.java))
        buildUi()
        engine = CameraEngine(this, { next, message ->
            val wasBusy = busy
            val previousState = state
            state = next
            // Same-state feedback can predate the submitted command. Only its transition acknowledges it.
            if ((recordingCommand == RecordingCommand.START && next != EngineState.READY) ||
                (recordingCommand == RecordingCommand.STOP && next != EngineState.RECORDING)) recordingCommand = null
            if (next == EngineState.OPENING) cameraOpening = true
            else if (next == EngineState.READY || next == EngineState.RECORDING || next == EngineState.ERROR ||
                (next == EngineState.CLOSED && !active)) cameraOpening = false
            status.text = message
            if (next == EngineState.RECORDING && startedAt == 0L) {
                submittedWorkspace?.let { workspace = workspaceStore.recordStarted(it); submittedWorkspace = null }
                startedAt = SystemClock.elapsedRealtime()
                ui.post(ticker)
            }
            if (next != EngineState.RECORDING) {
                startedAt = 0L
                lastDisplayedSecond = -1L
                ui.removeCallbacks(ticker)
            }
            if (next == EngineState.ERROR || next == EngineState.CLOSED) { submittedWorkspace = null; cancelMotion() }
            if (sheet?.isShowing == true && (
                (wasBusy != busy && page in listOf(Page.VIDEO, Page.AUDIO, Page.STABILIZATION, Page.APP)) ||
                (page == Page.VIDEO && previousState != next &&
                    (previousState == EngineState.RECORDING || next == EngineState.RECORDING)))) buildControls()
            refreshUi()
            if (pendingAudioRecording && active && next == EngineState.READY) {
                pendingAudioRecording = false
                requestRecording(appPreferences.microphoneEnabled && hasAudioPermission())
            }
        }, { uri ->
            savedUri = uri
            val now = SystemClock.elapsedRealtime()
            if (active && (savedToastAt == Long.MIN_VALUE || now - savedToastAt >= 5000L)) {
                savedToastAt = now
                Toast.makeText(this, "Vídeo salvo. Abra a biblioteca para assistir.", Toast.LENGTH_SHORT).show()
            }
        }, { iso, exposure ->
            val shutter = exposure?.takeIf { it > 0 }?.let { "1/${(1_000_000_000.0 / it).roundToInt()} s" } ?: "AUTO"
            meter.setTextIfChanged("ISO ${iso ?: "AUTO"}  ·  $shutter")
        }, { reset ->
            cancelMotion()
            settings = settings.withHardwareFrom(reset)
            lensStatus.text = lensModeDescription()
            ui.removeCallbacks(saveCapturePreferences)
            capturePreferencesDirty = false
            preferences.saveCaptureSettings(settings)
            if (sheet?.isShowing == true) buildControls()
            refreshUi()
        }, onPortraitStatus = { message ->
            portraitStatus.text = message + if (settings.subjectTrackingEnabled && (settings.focusEnabled || settings.focusLockEnabled)) " · distância da lente fixa" else ""
        }, onFocus = { message -> lensStatus.text = message },
            onStabilizationStatus = { message -> stabilizationStatus.setTextIfChanged(message) },
            onHistogram = { value -> if (settings.histogramEnabled && adjustable) {
                lastHistogram = value; histogram.update(value); menuHistogram?.update(value)
            } },
            onScopes = { value -> if (scopesEnabled() && adjustable) {
                lastScopes = value; scopeOverlay?.update(value); menuScopes?.update(value)
            } },
            onSubjectTrack = { point ->
                if (settings.subjectTrackingEnabled && adjustable) {
                    val info = selected
                    val mapped = point?.let { engine.mapSourcePointToDisplayed(it) }
                    val visible = if (info != null && mapped != null) FrameGeometry.outputGlAt(mapped.first, mapped.second,
                        FrameGeometry.relativeRotation(info.orientation, rotationDegrees(), info.front), info.front) else null
                    if (visible != null) focusReticle.showAt(visible.first, 1f - visible.second) else focusReticle.clear()
                }
            },
            onAudioLevel = { value -> if (settings.audioMeterEnabled && state == EngineState.RECORDING) {
                audioMeter.update(value); menuAudioMeter?.update(value)
            } })
        reloadPreviewLut()
        if (hasCameraPermission()) scanCameras()
        refreshUi()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
    private fun text(value: String, size: Float, color: Int = Color.WHITE): TextView = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
        gravity = Gravity.CENTER_VERTICAL
    }
    private fun rounded(color: Int, radius: Int = 16): GradientDrawable = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
    private fun touchSurface(color: Int = CameraPalette.elevated, radius: Int = 16, outlined: Boolean = true): RippleDrawable {
        val shape = rounded(color, radius).apply {
            if (outlined) setStroke(dp(1), CameraPalette.border)
        }
        return RippleDrawable(ColorStateList.valueOf(0x24FFFFFF), shape, rounded(Color.WHITE, radius))
    }
    private fun button(value: String, action: () -> Unit): TextView = text(value, 13f).apply {
        gravity = Gravity.CENTER; minHeight = dp(48)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        background = touchSurface()
        isClickable = true; isFocusable = true
        contentDescription = value
        setOnClickListener { action() }
    }
    private fun TextView.setTextIfChanged(value: String) { if (text.toString() != value) text = value }
    private fun View.setAvailable(value: Boolean, disabledAlpha: Float = .42f) {
        if (isEnabled != value) isEnabled = value
        val opacity = if (value) 1f else disabledAlpha
        if (alpha != opacity) alpha = opacity
    }
    private fun shortcut(symbol: CameraIcon, label: String, target: Page): LinearLayout = row().apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = touchSurface(panelColor, 16)
        isClickable = true; isFocusable = true; contentDescription = "Abrir ${target.title}"
        minimumWidth = dp(48); minimumHeight = dp(56); setPadding(dp(2), dp(4), dp(2), dp(4))
        val glyph = CameraIconButton(this@MainActivity, symbol, "").apply {
            isClickable = false; isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = null; tintColor = accent
        }
        addView(glyph, LinearLayout.LayoutParams(dp(24), dp(24)))
        addView(text(label, 11f).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); gravity = Gravity.CENTER; maxLines = 2
        }, LinearLayout.LayoutParams(-1, -2))
        setOnClickListener { showSettings(target) }
    }
    private fun row(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    private fun icon(icon: CameraIcon, description: String, action: () -> Unit) =
        CameraIconButton(this, icon, description).apply { setOnClickListener { action() } }
    private fun LinearLayout.addButton(view: View, weight: Float = 1f, height: Int = 48) {
        addView(view, LinearLayout.LayoutParams(0, dp(height), weight).apply {
            setMargins(dp(4), dp(4), dp(4), dp(4))
        })
    }

    private fun buildUi() {
        val configuration = resources.configuration
        uiLayout = ResponsiveUiPolicy.layout(configuration.screenWidthDp.toFloat(), configuration.screenHeightDp.toFloat(), configuration.fontScale)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(backgroundColor)
        }
        val header = row().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(4), dp(10), dp(4))
        }
        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
            addView(text("Luma", 23f).apply {
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); letterSpacing = -.04f
                contentDescription = "Luma Camera"
            })
            addView(text("CAMERA", 8f, accent).apply { letterSpacing = .25f })
        }
        header.addView(brand, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(12) })
        formatChip = button("VÍDEO · 30 FPS", ::chooseVideo).apply {
            textSize = 11f; minHeight = 0
            setPadding(dp(12), 0, dp(12), 0)
            background = touchSurface(panelColor, 18)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; minimumHeight = dp(48)
        }
        header.addView(formatChip, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
        header.addView(icon(CameraIcon.SETTINGS, "Abrir todas as configurações") {
            showSettings(Page.HOME)
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(header, LinearLayout.LayoutParams(-1, -2))

        val landscape = uiLayout.cameraRail
        val body = LinearLayout(this).apply {
            orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        }
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        texture = TextureView(this).apply { surfaceTextureListener = this@MainActivity }
        texture.setOnTouchListener { view, event ->
            if (event.action == MotionEvent.ACTION_UP && adjustable) {
                val normalizedX = (event.x / view.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                val normalizedY = (event.y / view.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                val info = selected
                val mode = video ?: return@setOnTouchListener false
                val relative = FrameGeometry.relativeRotation(info?.orientation ?: 0, rotationDegrees(), info?.front == true)
                val displayed = FrameGeometry.sourcePoint(event.x, event.y, mode.width, mode.height,
                    relative, info?.front == true, view.width, view.height) ?: return@setOnTouchListener false
                val source = engine.mapDisplayedPoint(displayed.first, displayed.second)
                focusReticle.showAt(normalizedX, normalizedY)
                cancelMotion()
                val selectingObject = settings.subjectMode == SubjectFocusPolicy.OBJECTS &&
                    (settings.portraitEnabled || settings.cinematicEnabled || settings.subjectTrackingEnabled) &&
                    (settings.subjectTrackingEnabled || !settings.objectPointSelected || !settings.cinematicEnabled)
                if (selectingObject) {
                    settings = settings.copy(objectPointSelected = true,
                        objectFocusX = source.first, objectFocusY = source.second,
                        objectTapRevision = nextRevision(settings.objectTapRevision),
                        focusBackground = false, cinematicAutoFocus = false, cinematicTapFocus = false)
                    portraitStatus.text = SubjectFocusPolicy.status(SubjectFocusPolicy.StatusKind.ANALYZING, settings.subjectMode)
                    applySettings()
                } else if (settings.cinematicEnabled || settings.subjectTrackingEnabled) {
                    settings = settings.copy(cinematicAutoFocus = false,
                        cinematicTapFocus = settings.cinematicEnabled,
                        cinematicTapRevision = nextRevision(settings.cinematicTapRevision),
                        cinematicFocusX = source.first, cinematicFocusY = source.second)
                    applySettings()
                }
                engine.focusAt(source.first, 1f - source.second)
                view.performClick()
                true
            } else event.action == MotionEvent.ACTION_DOWN && adjustable
        }
        grid = GridOverlay(this).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            showGrid = appPreferences.grid
        }
        applyCompositionSettings()
        frame.addView(texture, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        frame.addView(grid, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        focusReticle = FocusReticle(this)
        frame.addView(focusReticle, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        hudBottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(0xD9080C10.toInt(), Color.TRANSPARENT))
        }
        portraitStatus = text("Desfoque IA · preparando", 11f, accent).apply {
            setPadding(0, dp(6), 0, dp(6)); minHeight = dp(48)
            maxLines = if (uiLayout.compactControls) 1 else 2; ellipsize = TextUtils.TruncateAt.END
            setShadowLayer(3f, 0f, 1f, Color.BLACK)
            visibility = View.GONE
            contentDescription = "Estado do desfoque IA. Toque para escolher Pessoas ou Objetos."
            setOnClickListener { showSettings(Page.FOCUS) }
        }
        stabilizationStatus = text("Estabilização · preparando", 11f, accent).apply {
            setPadding(0, dp(4), 0, dp(4)); minHeight = dp(48)
            maxLines = if (uiLayout.compactControls) 1 else 2; ellipsize = TextUtils.TruncateAt.END
            setShadowLayer(3f, 0f, 1f, Color.BLACK)
            visibility = View.GONE
            contentDescription = "Estado da estabilização. Toque para ajustar."
            setOnClickListener { showSettings(Page.STABILIZATION) }
        }
        focusStatusRow = row().apply {
                addView(portraitStatus, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
                addView(stabilizationStatus, LinearLayout.LayoutParams(0, -2, 1f))
            }
        hudBottom.addView(focusStatusRow, LinearLayout.LayoutParams(-1, -2))
        lensStatus = text("Lente · foco automático", 11f, Color.WHITE).apply {
            setPadding(0, dp(4), 0, dp(4)); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            setShadowLayer(3f, 0f, 1f, Color.BLACK)
        }
        hudBottom.addView(lensStatus, LinearLayout.LayoutParams(-1, -2))

        hudTop = row().apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(10), dp(8), dp(10))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x99000000.toInt(), Color.TRANSPARENT))
        }
        val readings = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        sessionLabel = text("PRONTO", 10f, accent).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); letterSpacing = .1f
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        readings.addView(sessionLabel)
        meter = text("ISO AUTO  ·  AUTO", 10f, Color.WHITE).apply {
            setPadding(0, dp(5), 0, 0)
            setShadowLayer(3f, 0f, 1f, Color.BLACK)
        }
        readings.addView(meter)
        hudTop.addView(readings, LinearLayout.LayoutParams(0, -2, 1f))
        effectBadge = text("ORIGINAL", 10f, accent).apply {
            letterSpacing = .04f; gravity = Gravity.CENTER
            maxWidth = dp(152); maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = rounded(0xD9141B23.toInt(), 12)
            minimumHeight = dp(48)
            contentDescription = "Abrir ferramentas de monitoramento e imagem"
            setOnClickListener { showSettings(if (settings.histogramEnabled || settings.audioMeterEnabled ||
                settings.zebraEnabled || settings.falseColorEnabled || settings.peakingEnabled) Page.MONITOR else Page.LOOK) }
        }
        hudTop.addView(effectBadge, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        gridButton = icon(CameraIcon.GRID, "Ativar ou desativar grade de composição") {
            appPreferences = appPreferences.copy(grid = !appPreferences.grid)
            saveAppPreferences(); grid.showGrid = appPreferences.grid; refreshUi()
        }
        hudTop.addView(gridButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        frame.addView(hudTop, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        status = text("Permita a câmera para começar", 12f).apply {
            setPadding(0, dp(5), 0, 0); setTextColor(muted)
            maxLines = if (uiLayout.compactControls) 1 else 2; ellipsize = TextUtils.TruncateAt.END
            setShadowLayer(3f, 0f, 1f, Color.BLACK)
        }
        hudBottom.addView(status, LinearLayout.LayoutParams(-1, -2))
        frame.addView(hudBottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        timerLabel = text("00:00", 15f, Color.WHITE).apply {
            typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = rounded(0xED8F2730.toInt(), 12)
            contentDescription = "Tempo de gravação"
            visibility = View.GONE
        }
        histogram = HistogramOverlay(this).apply { visibility = View.GONE }
        audioMeter = AudioMeterView(this).apply { visibility = View.GONE }
        monitorRow = row().apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(audioMeter, LinearLayout.LayoutParams(0, dp(if (configuration.fontScale > 1.3f) 64 else 38), 1f).apply { marginEnd = dp(8) })
            addView(histogram, LinearLayout.LayoutParams(0, dp(if (configuration.fontScale > 1.3f) 120 else if (landscape) 64 else 86), 1f))
        }
        frame.addView(timerLabel, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        frame.addView(monitorRow, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply {
            marginStart = dp(12); marginEnd = dp(12)
        })
        monitorToggle = button("Ver monitores") { showSettings(Page.MONITOR) }.apply {
            textSize = 11f; visibility = View.GONE
        }
        frame.addView(monitorToggle, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { marginEnd = dp(12) })
        scopeOverlay = ScopeOverlay(this).apply { visibility = View.GONE }
        frame.addView(scopeOverlay, FrameLayout.LayoutParams(-1, dp(120), Gravity.TOP).apply {
            marginStart = dp(12); marginEnd = dp(12)
        })
        emptyPreview = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(28), dp(20), dp(28), dp(20))
            background = rounded(0xF2141B23.toInt(), 24).apply { setStroke(dp(1), CameraPalette.border) }
            addView(text("Uma nova\nperspectiva.", 28f).apply {
                gravity = Gravity.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }, LinearLayout.LayoutParams(-1, -2))
            addView(text("Sua câmera. Sua visão.\nPermita o acesso para começar a criar.", 13f, muted).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(18))
            })
            addView(button("Abrir minha câmera", ::recordAction).apply {
                setTextColor(backgroundColor); background = touchSurface(accent, 16, outlined = false)
            },
                LinearLayout.LayoutParams(-1, -2))
        }
        frame.addView(emptyPreview, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply {
            setMargins(dp(24), 0, dp(24), 0)
        })
        if (landscape) body.addView(frame, LinearLayout.LayoutParams(0, -1, 1f))
        else body.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))

        val dock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(backgroundColor)
            val gutter = if (landscape || uiLayout.compactControls) 8 else 16
            setPadding(dp(gutter), dp(6), dp(gutter), dp(8))
            gravity = Gravity.CENTER_VERTICAL
        }
        val modeLine = row().apply { gravity = Gravity.CENTER_VERTICAL }
        modeLine.addView(text("VÍDEO", 10f, accent).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); letterSpacing = .14f
        }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        lensLabel = text("Câmera", 11f, muted).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL; maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        }
        modeLine.addView(lensLabel, LinearLayout.LayoutParams(0, -2, 1f))
        if (!uiLayout.compactControls || landscape) dock.addView(modeLine)
        val quick = row()
        repeat(3) { index ->
            val item = shortcut(CameraIcon.SETTINGS, "Atalho", Page.HOME)
            item.setOnClickListener { favoriteTool(index)?.let { openTool(it) } ?: showSettings(Page.HOME) }
            quickSlots.add(item)
            quick.addView(item, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index < 2) marginEnd = dp(6) })
        }
        refreshQuickAccess()
        if (!landscape && !uiLayout.compactControls)
            dock.addView(quick, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6); bottomMargin = dp(4) })

        val shutterRow = row().apply { gravity = Gravity.CENTER }
        galleryButton = icon(CameraIcon.GALLERY, "Abrir biblioteca de vídeos", ::showVideo)
        lensButton = icon(CameraIcon.FLIP, "Trocar câmera frontal ou traseira", ::switchCamera)
        val galleryColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        galleryColumn.addView(galleryButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        galleryColumn.addView(text("Galeria", 10f, muted).apply { gravity = Gravity.CENTER })
        val shutterColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        recordButton = RecordShutterView(this).apply {
            contentDescription = "Gravar vídeo"; setOnClickListener { recordAction() }
        }
        val shutterSize = if (uiLayout.compactControls && !landscape) 64 else 88
        shutterColumn.addView(recordButton, LinearLayout.LayoutParams(dp(shutterSize), dp(shutterSize)))
        recordLabel = text("Gravar", 11f, muted).apply { gravity = Gravity.CENTER; maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        shutterColumn.addView(recordLabel, LinearLayout.LayoutParams(-1, -2))
        val lensColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        lensColumn.addView(lensButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        lensColumn.addView(text("Inverter", 10f, muted).apply { gravity = Gravity.CENTER })
        if (landscape) {
            shutterRow.addView(shutterColumn, LinearLayout.LayoutParams(-1, -2))
            dock.addView(shutterRow, LinearLayout.LayoutParams(-1, -2))
            dock.addView(row().apply {
                gravity = Gravity.CENTER
                addView(galleryColumn, LinearLayout.LayoutParams(0, -2, 1f))
                addView(lensColumn, LinearLayout.LayoutParams(0, -2, 1f))
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            dock.addView(quick, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        } else if (uiLayout.compactControls) {
            shutterRow.addView(galleryColumn, LinearLayout.LayoutParams(dp(48), -2))
            shutterRow.addView(shutterColumn, LinearLayout.LayoutParams(dp(64), -2))
            shutterRow.addView(lensColumn, LinearLayout.LayoutParams(dp(48), -2))
            while (quick.childCount > 0) {
                val shortcut = quick.getChildAt(0); quick.removeViewAt(0)
                shutterRow.addView(shortcut, LinearLayout.LayoutParams(dp(48), -2))
            }
            dock.addView(HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = true; isFillViewport = true; addView(shutterRow)
            }, LinearLayout.LayoutParams(-1, -2))
        } else {
            shutterRow.addView(galleryColumn, LinearLayout.LayoutParams(dp(56), -2))
            shutterRow.addView(shutterColumn, LinearLayout.LayoutParams(0, -2, 1f))
            shutterRow.addView(lensColumn, LinearLayout.LayoutParams(dp(56), -2))
            dock.addView(shutterRow, LinearLayout.LayoutParams(-1, -2))
        }
        if (landscape) body.addView(ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; addView(dock) },
            LinearLayout.LayoutParams(dp(uiLayout.railWidthDp), -1))
        else body.addView(dock, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        CameraWindowInsets.bind(window, root)
        frame.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            fitPreview(); requestMonitorLayout()
            if (::engine.isInitialized && active && state == EngineState.CLOSED && !cameraOpening) reopen()
        }
        hudTop.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> requestMonitorLayout() }
        hudBottom.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> requestMonitorLayout() }
    }

    private fun showSettings(target: Page) {
        page = target
        if (sheet?.isShowing == true) { buildControls(); refreshTabs(); return }
        val dialog = Dialog(this)
        sheet = dialog
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(panelColor, 28).apply { setStroke(dp(1), CameraPalette.border) }
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        val handle = View(this).apply { background = rounded(Color.rgb(83, 94, 111), 4) }
        container.addView(handle, LinearLayout.LayoutParams(dp(36), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(8)
        })
        val heading = row().apply { gravity = Gravity.CENTER_VERTICAL }
        sheetTitle = text(page.title, 22f).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); maxLines = 2; minimumHeight = dp(48)
        }
        sheetTitle?.setOnClickListener { page = Page.HOME; buildControls(); refreshTabs() }
        heading.addView(sheetTitle,
            LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(icon(CameraIcon.CLOSE, "Fechar configurações") { dialog.dismiss() },
            LinearLayout.LayoutParams(dp(48), dp(48)))
        container.addView(heading)
        val tabs = row()
        tabButtons.clear()
        listOf(Page.HOME, Page.PRESETS, Page.FOCUS, Page.MONITOR).forEach { targetPage ->
            val tab = button(targetPage.title) { page = targetPage; buildControls(); refreshTabs() }.apply {
                minHeight = dp(48); textSize = 12f; setPadding(dp(12), dp(8), dp(12), dp(8)); maxLines = 1
            }
            tabButtons[targetPage] = tab
            tabs.addView(tab, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
        }
        container.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; addView(tabs)
        }, LinearLayout.LayoutParams(-1, -2))
        controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(8))
        }
        builtPage = null
        controlsScroll = ScrollView(this).apply { isFillViewport = false; addView(controls) }
        container.addView(controlsScroll,
            LinearLayout.LayoutParams(-1, 0, 1f))
        dialog.setContentView(container)
        dialog.setOnDismissListener {
            flushCapturePreferences()
            container.animate().cancel()
            if (sheet === dialog) {
                sheet = null; sheetTitle = null; switches.clear(); preferenceSwitches.clear(); sliders.clear(); menuButtons.clear(); wbButton = null
                controls.removeAllViews(); tabButtons.clear(); controlsScroll = null; builtPage = null
                menuHistogram = null; menuAudioMeter = null
            }
        }
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = .42f; gravity = Gravity.BOTTOM }
        }
        dialog.show()
        CameraWindowInsets.sizeDialog(dialog, window)
        buildControls()
        refreshTabs()
        if (state != EngineState.RECORDING && ValueAnimator.areAnimatorsEnabled()) {
            container.translationY = dp(18).toFloat(); container.alpha = .75f
            container.animate().translationY(0f).alpha(1f).setDuration(160L).setInterpolator(DecelerateInterpolator()).start()
        }
    }

    /** Reserve measured HUD space; a cramped preview exposes the live readings in Monitor Pro. */
    private fun requestMonitorLayout() {
        if (!::monitorRow.isInitialized || monitorLayoutPending) return
        monitorLayoutPending = true
        frame.post {
            monitorLayoutPending = false
            if (frame.height <= 0) return@post
            // Estimate the full HUD from its text layouts, even while parents are GONE.
            // Measuring only the visible parent causes hide/show oscillation on short windows.
            fun textHeight(view: TextView): Int = maxOf(view.minimumHeight,
                (view.layout?.height ?: kotlin.math.ceil(view.paint.fontSpacing.toDouble()).toInt()) +
                    view.compoundPaddingTop + view.compoundPaddingBottom)
            val expandedTop = maxOf(textHeight(sessionLabel) + textHeight(meter), textHeight(effectBadge), dp(48)) +
                hudTop.paddingTop + hudTop.paddingBottom
            val aiHeight = maxOf(if (settings.portraitEnabled || settings.cinematicEnabled || settings.subjectTrackingEnabled) textHeight(portraitStatus) else 0,
                if (settings.stabilizationEnabled) textHeight(stabilizationStatus) else 0)
            val messageHeight = if (status.visibility == View.VISIBLE) textHeight(status) else 0
            val expandedBottom = aiHeight + textHeight(lensStatus) + messageHeight +
                hudBottom.paddingTop + hudBottom.paddingBottom
            val timerHeight = if (state == EngineState.RECORDING) maxOf(textHeight(timerLabel), dp(36)) else 0
            val compactHud = expandedTop + expandedBottom + timerHeight + dp(12) > frame.height
            hudTop.visibility = if (compactHud) View.GONE else View.VISIBLE
            focusStatusRow.visibility = if (compactHud) View.GONE else View.VISIBLE
            lensStatus.visibility = if (compactHud) View.GONE else View.VISIBLE
            hudBottom.visibility = if (!compactHud || messageHeight > 0) View.VISIBLE else View.GONE
            val bottom = if (compactHud) {
                if (messageHeight > 0) messageHeight + hudBottom.paddingTop + hudBottom.paddingBottom else 0
            } else expandedBottom
            val top = if (compactHud) dp(4) else expandedTop + dp(4)
            fun place(view: View, margin: Int) {
                val params = view.layoutParams as FrameLayout.LayoutParams
                if (params.topMargin != margin) { params.topMargin = margin; view.layoutParams = params }
            }
            place(timerLabel, top)
            place(monitorToggle, top)
            val hasHistogram = settings.histogramEnabled
            val hasAudio = state == EngineState.RECORDING && settings.audioMeterEnabled && engine.recordingWithAudio()
            val hasScopes = scopesEnabled()
            val hasReadings = hasHistogram || hasAudio || hasScopes
            val desiredHeight = maxOf(if (hasHistogram) histogram.layoutParams.height else 0,
                if (hasAudio) audioMeter.layoutParams.height else 0)
            val start = top + timerHeight + if (timerHeight > 0) dp(4) else 0
            val free = frame.height - bottom - dp(6) - start
            val scopesHeight = if (hasScopes) dp(120) else 0
            val fits = desiredHeight + scopesHeight + (if (hasScopes && desiredHeight > 0) dp(4) else 0) <= free && frame.width >= dp(240)
            monitorRow.visibility = if ((hasHistogram || hasAudio) && fits) View.VISIBLE else View.GONE
            scopeOverlay?.visibility = if (hasScopes && fits) View.VISIBLE else View.GONE
            monitorToggle.visibility = if (hasReadings && !fits && free >= dp(48)) View.VISIBLE else View.GONE
            if (state == EngineState.RECORDING && monitorToggle.visibility == View.VISIBLE) {
                // Stack below the timer when both won't fit side by side; never cover its digits.
                place(monitorToggle, top + timerHeight + dp(4))
            }
            place(monitorRow, start)
            scopeOverlay?.let { place(it, start + desiredHeight + if (desiredHeight > 0) dp(4) else 0) }
        }
    }

    private fun refreshTabs() {
        sheetTitle?.setTextIfChanged(page.title)
        tabButtons.forEach { (target, view) ->
            val chosen = target == page
            view.setTextColor(if (chosen) backgroundColor else Color.WHITE)
            view.background = touchSurface(if (chosen) accent else CameraPalette.elevated, 16, outlined = !chosen)
            view.isSelected = chosen
            if (chosen) view.post { view.requestRectangleOnScreen(Rect(0, 0, view.width, view.height), true) }
        }
    }

    private fun section(title: String, description: String) {
        val sectionHeading = text(title, 16f).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); setPadding(dp(2), dp(12), 0, dp(6))
        }
        controls.addView(sectionHeading)
        sectionAnchors[title] = sectionHeading
        controls.addView(text(description, 12f, muted).apply {
            setPadding(dp(2), 0, 0, dp(10)); setLineSpacing(dp(2).toFloat(), 1f)
        })
    }

    private fun buildControls() {
        if (!::controls.isInitialized) return
        val restore = ResponsiveUiPolicy.restoredScroll(controlsScroll?.scrollY ?: 0, builtPage == page)
        builtPage = page
        val targetPage = page
        menuHistogram = null; menuAudioMeter = null; menuScopes = null; audioRouteLabel = null; partStatusLabel = null; sectionAnchors.clear()
        controls.removeAllViews(); sliders.clear(); switches.clear(); preferenceSwitches.clear(); menuButtons.clear(); wbButton = null
        when (page) {
            Page.HOME -> buildToolHome()
            Page.PRESETS -> buildPresetSettings()
            Page.AUDIO -> buildAudioSettings()
            Page.ZOOM -> buildZoomSettings()
            Page.PROJECT -> buildProjectSettings()
            Page.FOCUS -> buildFocusSettings()
            Page.MONITOR -> buildMonitorSettings()
            Page.STABILIZATION -> buildStabilizationSettings()
            Page.LOOK -> {
                buildLogSettings()
                buildLutSettings()
                controls.addView(button(if (imageAdjustmentsExpanded) "Ocultar ajustes de cor e efeitos ▴" else "Ajustes de cor e efeitos ▾") {
                    imageAdjustmentsExpanded = !imageAdjustmentsExpanded; buildControls()
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
                if (imageAdjustmentsExpanded) {
                section("Cor e textura", "Cada efeito tem sua própria chave. Combine só o que desejar.")
                controls.addView(text("Ganho positivo e contraste alto podem cortar detalhes claros no arquivo. Para uma base neutra, use ‘Preparar imagem para edição’; para regular a luz da câmera, use o ajuste automático em Exposição.", 12f, muted).apply {
                    setPadding(dp(4), 0, dp(4), dp(10))
                })
                addToggle("Flat SDR · suavização simples", { settings.flatEnabled }) { settings = settings.copy(flatEnabled = it) }
                addToggle("Ganho de luz local", { settings.gainEnabled }) {
                    settings = settings.copy(gainEnabled = it)
                }
                addSlider("Ganho · não altera ISO", 120, ((settings.gainStops + 3f) * 20).roundToInt(),
                    { settings.gainEnabled }, { String.format(Locale.getDefault(), "%+.2f stops", it / 20f - 3f) }) {
                    settings = settings.copy(gainStops = it / 20f - 3f); applySettings()
                }
                addToggle("Temperatura local", { settings.temperatureEnabled }) {
                    settings = settings.copy(temperatureEnabled = it)
                }
                addSlider("Frio ← temperatura → quente", 200, ((settings.temperature + 1f) * 100).roundToInt(),
                    { settings.temperatureEnabled }, { String.format(Locale.getDefault(), "%+.0f%%", it - 100f) }) {
                    settings = settings.copy(temperature = it / 100f - 1f); applySettings()
                }
                addToggle("Contraste local", { settings.contrastEnabled }) {
                    settings = settings.copy(contrastEnabled = it)
                }
                addSlider("Contraste", 200, (settings.contrast * 100).roundToInt(), { settings.contrastEnabled }, { "$it%" }) {
                    settings = settings.copy(contrast = it / 100f); applySettings()
                }
                addToggle("Saturação local", { settings.saturationEnabled }) {
                    settings = settings.copy(saturationEnabled = it)
                }
                addSlider("Saturação", 200, (settings.saturation * 100).roundToInt(), { settings.saturationEnabled }, { "$it%" }) {
                    settings = settings.copy(saturation = it / 100f); applySettings()
                }
                addToggle("Nitidez local", { settings.sharpnessEnabled }) {
                    settings = settings.copy(sharpnessEnabled = it)
                }
                addSlider("Nitidez", 100, (settings.sharpness * 100).roundToInt(), { settings.sharpnessEnabled }, { "$it%" }) {
                    settings = settings.copy(sharpness = it / 100f); applySettings()
                }
                section("Desfoque oval opcional", "Efeito geométrico independente do retrato IA.")
                addToggle("Desfoque seletivo local", { settings.blurEnabled }) {
                    settings = settings.copy(blurEnabled = it)
                }
                controls.addView(text("Máscara oval: mantenha o assunto no centro escolhido. Sem recorte por IA.", 10f, muted))
                addSlider("Intensidade do desfoque", 100, (settings.blurAmount * 100).roundToInt(), { settings.blurEnabled }, { "$it%" }) {
                    settings = settings.copy(blurAmount = it / 100f); applySettings()
                }
                addSlider("Área preservada · raio", 60, ((settings.blurRadius - .1f) * 100).roundToInt(), { settings.blurEnabled }, { "${it + 10}%" }) {
                    settings = settings.copy(blurRadius = .1f + it / 100f); applySettings()
                }
                addSlider("Centro horizontal", 100, (settings.blurCenterX * 100).roundToInt(), { settings.blurEnabled }, { "$it%" }) {
                    settings = settings.copy(blurCenterX = it / 100f); applySettings()
                }
                addSlider("Centro vertical", 100, (settings.blurCenterY * 100).roundToInt(), { settings.blurEnabled }, { "$it%" }) {
                    settings = settings.copy(blurCenterY = it / 100f); applySettings()
                }
                section("Movimento", "Crie rastros suaves misturando quadros no aparelho.")
                addToggle("Rastro de movimento local", { settings.trailEnabled }) {
                    settings = settings.copy(trailEnabled = it)
                }
                controls.addView(text("Mistura quadros para um efeito criativo; não altera o obturador físico.", 10f, muted))
                addSlider("Intensidade do rastro", 90, (settings.trailAmount * 100).roundToInt(), { settings.trailEnabled }, { "$it%" }) {
                    settings = settings.copy(trailAmount = it / 100f); applySettings()
                }
                }
            }
            Page.SENSOR -> {
                section("Controles da câmera", "Valores físicos disponíveis nesta lente. Desligue cada chave para voltar ao automático.")
                val info = selected
                if (info == null) controls.addView(text("Permita a câmera para consultar os controles desta lente.", 13f, muted))
                else {
                        controls.addView(button("Ajustar exposição automaticamente") {
                            settings = settings.preparedForSafeAutomaticExposure(info.evRange.lower, info.evRange.upper, info.evStep)
                            normalizeSensorSettings(); applySettings(); buildControls()
                            toast("Exposição automática, ganho local desligado e EV moderado conforme suporte da lente.")
                        }.availableWhen { adjustable && !busy }, LinearLayout.LayoutParams(-1, -2))
                        controls.addView(text("Use para voltar ao automático e reduzir o risco de clarear demais. A câmera mede a luz; realces já cortados não podem ser recuperados pelo Log. Confira a zebra em Monitores.", 12f, muted).apply {
                            setPadding(dp(4), dp(10), dp(4), dp(10))
                        })
                        controls.addView(text(if (info.manualSensor) "ISO e obturador individuais: o outro parâmetro se ajusta automaticamente."
                            else "ISO/obturador manuais indisponíveis. Use ganho de luz em Imagem.", 10f, muted))
                        val isoRange = info.isoRange
                        addToggle("ISO do sensor", { settings.isoEnabled }, { info.manualSensor && isoRange != null }) {
                            settings = settings.copy(isoEnabled = it)
                        }
                        if (isoRange != null) {
                            val lo = ln(isoRange.lower.toDouble()); val hi = ln(isoRange.upper.toDouble())
                            val initial = if (hi > lo) ((ln(settings.iso.coerceIn(isoRange.lower, isoRange.upper).toDouble()) - lo) / (hi - lo) * 1000).roundToInt() else 0
                            addSlider("ISO", 1000, initial, { info.manualSensor && settings.isoEnabled }, { progress ->
                                exp(lo + (hi - lo) * progress / 1000.0).roundToInt().toString()
                            }) { progress -> settings = settings.copy(iso = exp(lo + (hi - lo) * progress / 1000.0).roundToInt()); applySettings() }
                        }
                        val denominators = validShutters(info)
                        addToggle("Obturador do sensor", { settings.shutterEnabled }, { info.manualSensor && denominators.isNotEmpty() }) {
                            settings = settings.copy(shutterEnabled = it)
                        }
                        if (denominators.isNotEmpty()) {
                            val initial = denominators.indexOfFirst { 1_000_000_000L / it <= settings.exposureNs }.coerceAtLeast(0)
                            addSlider("Obturador", denominators.lastIndex, initial, { info.manualSensor && settings.shutterEnabled },
                                { "1/${denominators[it]}s" }, initialDisplay = String.format(Locale.getDefault(), "%.2f ms · %.0f°",
                                    settings.exposureNs / 1_000_000.0, ProfessionalCaptureTools.angleForExposure(settings.exposureNs, video?.fps ?: 30) ?: 0f)) {
                                settings = settings.copy(exposureNs = 1_000_000_000L / denominators[it]); applySettings()
                            }
                        }
                        controls.addView(button("Obturador por ângulo", ::chooseShutterAngle)
                            .availableWhen { adjustable && info.manualSensor && denominators.isNotEmpty() }, LinearLayout.LayoutParams(-1, -2))
                        addToggle("Travar exposição automática · AE", { settings.aeLockEnabled },
                            { info.aeLockAvailable && !settings.isoEnabled && !settings.shutterEnabled }) {
                            settings = settings.copy(aeLockEnabled = it)
                        }
                        val evWidth = info.evRange.upper - info.evRange.lower
                        addToggle("Compensação EV do sensor", { settings.evEnabled }, { evWidth > 0 }) {
                            settings = settings.copy(evEnabled = it)
                        }
                        controls.addView(text("EV orienta o parâmetro automático; ambos manuais fixam a exposição.", 10f, muted))
                        addSlider("Compensação", evWidth, settings.ev - info.evRange.lower,
                            { settings.evEnabled && evWidth > 0 },
                            { String.format(Locale.getDefault(), "%+.1f EV", (it + info.evRange.lower) * info.evStep) }) {
                            settings = settings.copy(ev = it + info.evRange.lower); applySettings()
                        }
                        addToggle("Balanço de branco do sensor", { settings.whiteBalanceEnabled }, { info.awbModes.any { it != CaptureRequest.CONTROL_AWB_MODE_OFF } }) {
                            settings = settings.copy(whiteBalanceEnabled = it)
                        }
                        wbButton = button("WB: Auto", ::chooseWhiteBalance)
                        controls.addView(wbButton!!, LinearLayout.LayoutParams(-1, -2))
                        addToggle("Travar balanço automático · AWB", { settings.awbLockEnabled }, {
                            info.awbLockAvailable && (!settings.whiteBalanceEnabled || settings.whiteBalance == CaptureRequest.CONTROL_AWB_MODE_AUTO)
                        }) { settings = settings.copy(awbLockEnabled = it) }
                        controls.addView(text("AE/AWB só travam o automático quando a lente oferece suporte. O ângulo calcula o tempo pela taxa de quadros e respeita os limites do sensor.", 12f, muted).apply {
                            setPadding(dp(4), dp(12), dp(4), 0)
                        })
                
                }
            }
            Page.VIDEO -> buildVideoSettings()
            Page.APP -> buildAppSettings()
        }
        if (page in listOf(Page.LOOK, Page.MONITOR, Page.FOCUS, Page.SENSOR, Page.STABILIZATION)) {
            controls.addView(button("Restaurar esta categoria") { resetCategory() }.availableWhen {
                when (page) { Page.LOOK, Page.STABILIZATION -> !busy; else -> true }
            },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        }
        refreshUi()
        controlsScroll?.post {
            if (builtPage == targetPage && sheet?.isShowing == true) {
                val key = pendingTool
                val target = toolSectionTitle(key)?.let { sectionAnchors[it] }
                controlsScroll?.scrollTo(0, target?.top ?: restore)
                pendingTool = null
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun addToggle(label: String, checked: () -> Boolean, available: () -> Boolean = { true }, changed: (Boolean) -> Unit) {
        val toggle = Switch(this).apply {
            text = label; textSize = 13f; setTextColor(Color.WHITE)
            minHeight = dp(56)
            contentDescription = label
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = touchSurface(CameraPalette.elevated, 16)
            thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(accent, muted))
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(0x66C7F36B, CameraPalette.border))
            isChecked = checked()
            setOnCheckedChangeListener { _, enabled ->
                if (!refreshingControls) { changed(enabled); applySettings() }
            }
        }
        controls.addView(toggle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(6) })
        switches.add(Triple(toggle, checked, available))
    }

    private fun scopesEnabled() = settings.waveformEnabled || settings.rgbParadeEnabled || settings.vectorscopeEnabled
    private fun saveWorkspace() { workspaceStore.save(workspace); refreshQuickAccess() }
    private fun favoriteTool(index: Int): CameraTool? = ToolCatalog.favorites(workspace.favorites).getOrNull(index)
    private fun refreshQuickAccess() {
        quickSlots.forEachIndexed { index, item ->
            val tool = favoriteTool(index)
            val short = when (tool?.key) { "presets" -> "Presets"; "scopes" -> "Scopes"; "tracking" -> "Seguir IA";
                "projects" -> "Projetos"; "lut" -> "LUT"; "focus" -> "Foco"; "rack" -> "Foco A/B"; "zoom" -> "Zoom"; else -> tool?.title ?: "Ferramentas" }
            (item.getChildAt(1) as? TextView)?.setTextIfChanged(short)
            (item.getChildAt(0) as? CameraIconButton)?.icon = when (tool?.key) {
                "focus", "rack", "tracking", "blur" -> CameraIcon.FOCUS
                "zoom", "stabilization" -> CameraIcon.MOTION
                "lut", "log", "color" -> CameraIcon.COLOR
                "audio" -> CameraIcon.MIC
                "scopes", "exposure" -> CameraIcon.EXPOSURE
                "projects" -> CameraIcon.GALLERY
                "presets" -> CameraIcon.APP
                else -> CameraIcon.SETTINGS
            }
            item.contentDescription = "Atalho: ${tool?.title ?: "Ferramentas"}"
        }
    }
    private fun toolSectionTitle(key: String?): String? = when (key) {
        "log" -> "Perfil para edição"; "lut" -> "LUT na prévia"; "color" -> "Cor e textura";
        "focus" -> "Foco da lente"; "tracking" -> "Acompanhamento por IA"; "rack" -> "Foco A/B";
        "blur" -> "Desfoque por IA"; "guides" -> "Composição"; "parts" -> "Gravações longas em partes";
        "scopes" -> "Scopes de exposição e cor"; "conditions" -> "Condições para gravar"; else -> null
    }
    private fun openTool(tool: CameraTool) {
        if (tool.key == "conditions") { showRecordingConditions(); return }
        pendingTool = tool.key
        if (tool.key == "color") imageAdjustmentsExpanded = true
        showSettings(Page.valueOf(tool.page))
    }
    private fun buildToolHome() {
        section("Sua central de ferramentas", "Busque pelo nome ou pela tarefa. ★ fixa até três atalhos na tela da câmera. Toque no título acima para voltar aqui.")
        val search = EditText(this).apply {
            hint = "Buscar: microfone, Log, foco, zoom…"; textSize = 14f; setSingleLine(true)
            setTextColor(Color.WHITE); setHintTextColor(muted); setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(CameraPalette.elevated, 14); minHeight = dp(48); setText(searchQuery)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            contentDescription = "Buscar funções da câmera"
        }
        controls.addView(search, LinearLayout.LayoutParams(-1, -2))
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        controls.addView(results, LinearLayout.LayoutParams(-1, -2))
        fun populate() {
            results.removeAllViews()
            val query = search.text.toString()
            val favorites = ToolCatalog.favorites(workspace.favorites)
            val tools = if (query.isBlank()) favorites + ToolCatalog.tools.filter { it !in favorites }.sortedBy { Page.valueOf(it.page).ordinal } else ToolCatalog.search(query)
            var category: String? = null
            tools.forEachIndexed { index, tool ->
                val heading = if (query.isNotBlank()) "Resultados" else if (index < favorites.size) "Acesso rápido" else Page.valueOf(tool.page).title
                if (category != heading) {
                    results.addView(text(heading, 13f, accent).apply { setPadding(dp(2), dp(16), 0, dp(4)) }); category = heading
                }
                val line = row().apply { gravity = Gravity.CENTER_VERTICAL }
                line.addView(button(tool.title) { search.clearFocus(); openTool(tool) }.apply {
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL; maxLines = 2
                    contentDescription = "${tool.title}. ${tool.description}"
                }, LinearLayout.LayoutParams(0, -2, 1f))
                val favorite = tool.key in workspace.favorites
                line.addView(button(if (favorite) "★" else "☆") {
                    val keys = workspace.favorites.filter { it != tool.key }
                    if (!favorite && keys.size >= 3) toast("Os três atalhos estão ocupados. Desmarque uma estrela para trocar.")
                    else { workspace = workspace.copy(favorites = if (favorite) keys else keys + tool.key); saveWorkspace(); populate() }
                }.apply { contentDescription = if (favorite) "Remover ${tool.title} dos atalhos" else "Fixar ${tool.title} nos atalhos" },
                    LinearLayout.LayoutParams(dp(48), -2).apply { marginStart = dp(4) })
                results.addView(line, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
                results.addView(text(tool.description, 11f, muted).apply { setPadding(dp(8), dp(3), 0, dp(4)) })
            }
            if (tools.isEmpty()) results.addView(text("Nenhuma função encontrada. Tente outra palavra.", 13f, muted))
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) { }
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { searchQuery = s.toString(); populate() }
            override fun afterTextChanged(s: Editable?) { }
        })
        populate()
    }
    private fun promptText(title: String, initial: String, apply: (String) -> Unit) {
        val input = EditText(this).apply { setText(initial); setSingleLine(true); filters = arrayOf(android.text.InputFilter.LengthFilter(56)); setPadding(dp(20), dp(12), dp(20), dp(12)) }
        val dialog = AlertDialog.Builder(this).setTitle(title).setView(input).setPositiveButton("Salvar", null).setNegativeButton("Cancelar", null).create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = WorkspacePolicy.label(input.text.toString())
            if (value.isBlank()) input.error = "Digite um nome" else { apply(value); dialog.dismiss() }
        }
    }
    private fun buildPresetSettings() {
        section("Presets personalizados", "Salvam controles, cor, monitores, formato e pontos A/B. A câmera escolhida limita os valores aplicados; alvo de IA e trava de foco exigem uma nova seleção.")
        controls.addView(button("Salvar meus ajustes como preset") {
            promptText("Nome do preset", workspace.presetName.ifBlank { "Meu preset" }) { name ->
                if (!workspaceStore.savePreset(name, preferences.captureSnapshot(settings), appPreferences, workspace)) {
                    toast("Limite de 24 presets. Substitua ou remova um existente.")
                    return@promptText
                }
                workspace = workspace.copy(presetName = name); saveWorkspace(); buildControls(); toast("Preset salvo: $name")
            }
        }.availableWhen { !busy }, LinearLayout.LayoutParams(-1, -2))
        section("Pontos de partida", "Aplicações explícitas. Depois você pode ajustar cada função individualmente.")
        controls.addView(button("Original · automático") {
            cancelMotion(); settings = CaptureSettings(); workspace = workspace.copy(presetName = "Original")
            normalizeSensorSettings(); applySettings(); saveWorkspace(); engine.clearFocusTarget(); buildControls()
        }.availableWhen { adjustable && !busy }, LinearLayout.LayoutParams(-1, -2))
        controls.addView(button("Edição · LumaLog v2") {
            settings = settings.preparedForEditing(); workspace = workspace.copy(presetName = "Edição Log")
            applySettings(); saveWorkspace(); buildControls()
        }.availableWhen { adjustable && !busy }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        section("Meus presets", "Toque para aplicar. O botão × remove somente o preset salvo.")
        workspaceStore.presets().forEach { preset ->
            val name = preset.optString("name", "Preset")
            controls.addView(row().apply {
                addButton(button(name) { applyPreset(preset) }.availableWhen { adjustable && !busy })
                addView(button("×") {
                    AlertDialog.Builder(this@MainActivity).setTitle("Remover $name?").setPositiveButton("Remover") { _, _ ->
                        workspaceStore.removePreset(name); buildControls()
                    }.setNegativeButton("Cancelar", null).show()
                }.apply { contentDescription = "Remover preset $name" }, LinearLayout.LayoutParams(dp(48), dp(48)))
            }, LinearLayout.LayoutParams(-1, -2))
        }
        if (workspaceStore.presets().isEmpty()) controls.addView(text("Você ainda não salvou presets.", 12f, muted))
    }
    private fun applyPreset(preset: JSONObject) {
        if (busy || !adjustable) return
        val previousMode = video
        val result = runCatching { preferences.restoreCaptureSnapshot(preset.getJSONObject("capture")) }
        result.onFailure { toast("Preset inválido: ${it.message}") }.onSuccess { restored ->
            cancelMotion(); settings = restored
            val wanted = selected?.modes?.firstOrNull { it.width == preset.optInt("width") && it.height == preset.optInt("height") && it.fps == preset.optInt("fps") }
            if (wanted != null) video = wanted
            appPreferences = appPreferences.copy(microphoneEnabled = preset.optBoolean("microphone", true))
            val lut = preset.optString("lut").takeIf { name -> lutStore.entries().any { it.file == name } }
            fun f(key: String, fallback: Float) = preset.optDouble(key, fallback.toDouble()).toFloat().takeIf { it.isFinite() } ?: fallback
            workspace = workspace.copy(presetName = WorkspacePolicy.label(preset.optString("name")), lutFile = lut,
                zoomA = f("zoomA", 1f), zoomB = f("zoomB", 2f), zoomSeconds = f("zoomSeconds", 3f),
                focusA = f("focusA", 0f), focusB = f("focusB", 1f), focusSeconds = f("focusSeconds", 2f))
            if (lut == null) settings = settings.copy(previewLutEnabled = false)
            normalizeSensorSettings(); rememberSelection(); applySettings(); saveWorkspace(); reloadPreviewLut()
            engine.clearFocusTarget(); buildControls()
            if (previousMode != video) reopen(force = true)
            toast("${workspace.presetName}: ajustes adaptados à lente${if (wanted == null) "; formato atual preservado" else ""}. Alvo de IA requer nova seleção.")
        }
    }
    private fun buildLutSettings() {
        section("LUT na prévia", "Visualize um look somente na tela. A LUT criativa não entra no vídeo; os scopes continuam medindo o sinal gravado.")
        addToggle("Converter Log para SDR na tela", { settings.logPreviewAssist }, { settings.logEnabled }) {
            settings = settings.copy(logPreviewAssist = it)
        }
        val inputLabel = if (!settings.logEnabled) "SDR original" else if (settings.logPreviewAssist) "SDR convertido · para LUT criativa" else "LumaLog gravado · para LUT de conversão"
        controls.addView(text("Imagem enviada à LUT: $inputLabel", 12f, accent).apply { setPadding(0, dp(8), 0, dp(8)) })
        val entry = lutStore.entries().firstOrNull { it.file == workspace.lutFile }
        settingItem("LUT selecionada", entry?.title ?: "Nenhuma", action = ::choosePreviewLut)
        controls.addView(button("Importar LUT .cube · 17³ ou 33³", ::importPreviewLut).availableWhen { !busy }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        addToggle("Usar LUT somente na prévia", { settings.previewLutEnabled }, { entry != null }) { settings = settings.copy(previewLutEnabled = it) }
        addSlider("Intensidade da LUT na tela", 100, (settings.previewLutStrength * 100).roundToInt(), { settings.previewLutEnabled && entry != null }, { "$it%" }) {
            settings = settings.copy(previewLutStrength = it / 100f); applySettings()
        }
        controls.addView(text("Com Log, ligue ‘Monitorar em SDR’ se a LUT espera uma imagem SDR. Uma LUT de conversão para outro perfil Log pode produzir cores incorretas.", 12f, muted))
        if (entry != null) controls.addView(button("Remover LUT da biblioteca") {
            AlertDialog.Builder(this).setTitle("Remover ${entry.title}?").setPositiveButton("Remover") { _, _ ->
                lutStore.remove(entry.file); workspace = workspace.copy(lutFile = null); settings = settings.copy(previewLutEnabled = false)
                saveWorkspace(); applySettings(); reloadPreviewLut(); buildControls()
            }.setNegativeButton("Cancelar", null).show()
        }.availableWhen { !busy }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
    }
    private fun choosePreviewLut() {
        val entries = lutStore.entries()
        AlertDialog.Builder(this).setTitle("LUT de monitoramento")
            .setItems((listOf("Sem LUT") + entries.map { it.title }).toTypedArray()) { _, index ->
                workspace = workspace.copy(lutFile = entries.getOrNull(index - 1)?.file)
                settings = settings.copy(previewLutEnabled = workspace.lutFile != null)
                saveWorkspace(); applySettings(); reloadPreviewLut(); buildControls()
            }.setNegativeButton("Cancelar", null).show()
    }
    private fun importPreviewLut() {
        if (busy) return
        try { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }, 5) }
        catch (_: Exception) { toast("Nenhum seletor de arquivos disponível.") }
    }
    private fun reloadPreviewLut() {
        if (!::engine.isInitialized) return
        val revision = ++lutLoadRevision
        val name = workspace.lutFile
        lutLoader.execute {
            val result = runCatching { lutStore.load(name) }
            runOnUiThread {
                if (isDestroyed || revision != lutLoadRevision) return@runOnUiThread
                result.fold({ engine.setPreviewLut(it) }, {
                    engine.setPreviewLut(null); settings = settings.copy(previewLutEnabled = false); applySettings()
                    toast("LUT indisponível: ${it.message}")
                })
            }
        }
    }
    private fun buildAudioSettings() {
        section("Microfone e áudio", "Escolha uma entrada antes de gravar. A rota efetiva é confirmada durante REC; conexão e suporte dependem do aparelho e acessório.")
        addPreferenceToggle("Gravar áudio", "A permissão do microfone será pedida ao iniciar.", { appPreferences.microphoneEnabled }, { !busy }) {
            appPreferences = appPreferences.copy(microphoneEnabled = it); saveAppPreferences()
        }
        val input = engine.availableAudioInputs().firstOrNull { it.id == workspace.audioDeviceId }
        settingItem("Entrada preferida", input?.label ?: if (workspace.audioDeviceId == null) "Automática · Android escolhe" else "Entrada desconectada", { !busy }, ::chooseAudioInput)
        settingItem("Rota realmente usada", engine.audioRouteStatus().message) { buildControls() }
        audioRouteLabel = (controls.getChildAt(controls.childCount - 1) as? LinearLayout)?.getChildAt(1) as? TextView
        addToggle("Medidor de pico do microfone", { settings.audioMeterEnabled }, { appPreferences.microphoneEnabled }) { settings = settings.copy(audioMeterEnabled = it) }
        menuAudioMeter = AudioMeterView(this)
        controls.addView(menuAudioMeter, LinearLayout.LayoutParams(-1, dp(64)).apply { topMargin = dp(8) })
        controls.addView(text("Faça um clipe curto e confira o áudio na biblioteca antes de uma tomada importante. O medidor indica picos relativos durante REC; não confirma qualidade acústica nem volume calibrado.", 12f, muted))
    }
    private fun chooseAudioInput() {
        if (busy) return
        val inputs = engine.availableAudioInputs()
        val labels = listOf("Automática · escolha do Android") + inputs.map { it.label }
        val current = inputs.indexOfFirst { it.id == workspace.audioDeviceId } + 1
        AlertDialog.Builder(this).setTitle("Entrada de áudio").setSingleChoiceItems(labels.toTypedArray(), current) { dialog, index ->
            workspace = workspace.copy(audioDeviceId = inputs.getOrNull(index - 1)?.id); saveWorkspace(); dialog.dismiss(); buildControls()
        }.setNegativeButton("Cancelar", null).show()
    }
    private fun buildProjectSettings() {
        section("Projetos, cenas e tomadas", "Os nomes identificam os arquivos. Uma ficha JSON guarda a configuração solicitada de cada parte para consultar depois.")
        settingItem("Projeto", workspace.project.ifBlank { "Sem projeto" }, { !busy }) {
            promptText("Nome do projeto", workspace.project) { name ->
                workspace = workspace.copy(project = name, take = workspaceStore.sceneTake(name, workspace.scene)); saveWorkspace(); buildControls()
            }
        }
        settingItem("Cena", workspace.scene.ifBlank { "Sem cena" }, { !busy }) {
            promptText("Nome da cena", workspace.scene) { name ->
                workspace = workspace.copy(scene = name, take = workspaceStore.sceneTake(workspace.project, name)); saveWorkspace(); buildControls()
            }
        }
        settingItem("Próxima tomada", "%03d".format(workspace.take), { !busy }) {
            promptText("Número da próxima tomada", workspace.take.toString()) { number ->
                val take = number.toIntOrNull()?.takeIf { it in 1..999999 }
                if (take == null) toast("Use um número entre 1 e 999999.") else { workspace = workspace.copy(take = take); saveWorkspace(); buildControls() }
            }
        }
        controls.addView(button("Gravar sem identificação de projeto") {
            workspace = workspace.copy(project = "", scene = "", take = 1); saveWorkspace(); buildControls()
        }.availableWhen { !busy }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        controls.addView(text("A tomada avança quando REC realmente começa. As partes da mesma sessão mantêm o número da tomada. Os nomes são limitados para manter os arquivos legíveis.", 12f, muted).apply { setPadding(0, dp(10), 0, dp(8)) })
        controls.addView(button("Exportar ficha dos ajustes atuais") {
            try { startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "application/json"; putExtra(Intent.EXTRA_TITLE, "Luma-ficha-ajustes.json")
            }, 6) } catch (_: Exception) { toast("Nenhum seletor de destino disponível.") }
        }, LinearLayout.LayoutParams(-1, -2))
    }
    private fun captureMetadata(): JSONObject = JSONObject().put("schema", 1).put("kind", "requested_capture_settings")
        .put("project", workspace.project).put("scene", workspace.scene).put("take", workspace.take)
        .put("camera", selected?.id).put("width", video?.width).put("height", video?.height).put("fps", video?.fps)
        .put("capture", preferences.captureSnapshot(settings)).put("previewLutOnly", workspace.lutFile ?: "")
        .put("audioPreferredDevice", workspace.audioDeviceId ?: -1)
    private fun buildZoomSettings() {
        val maximum = engine.maxZoomRatio().coerceAtLeast(1f)
        section("Zoom suave A/B", "Programe um movimento por recorte digital. O vídeo mantém suas dimensões, mas aproximações reduzem o campo de visão e o detalhe disponível.")
        fun zoomLabel(progress: Int) = String.format(Locale.getDefault(), "%.2f×", 1f + (maximum - 1f) * progress / 100f)
        fun progress(value: Float) = if (maximum > 1f) ((value.coerceIn(1f, maximum) - 1f) / (maximum - 1f) * 100f).roundToInt() else 0
        addSlider("Zoom atual", 100, progress(settings.zoomRatio), { maximum > 1f }, ::zoomLabel) {
            cancelMotion(); settings = settings.copy(zoomRatio = 1f + (maximum - 1f) * it / 100f); applySettings()
        }
        addSlider("Ponto A · início", 100, progress(workspace.zoomA), { maximum > 1f }, ::zoomLabel) {
            workspace = workspace.copy(zoomA = 1f + (maximum - 1f) * it / 100f); saveWorkspace()
        }
        addSlider("Ponto B · fim", 100, progress(workspace.zoomB), { maximum > 1f }, ::zoomLabel) {
            workspace = workspace.copy(zoomB = 1f + (maximum - 1f) * it / 100f); saveWorkspace()
        }
        addSlider("Duração do movimento", 195, ((workspace.zoomSeconds - .5f) * 10).roundToInt(), { maximum > 1f }, { String.format(Locale.getDefault(), "%.1f s", .5f + it / 10f) }) {
            workspace = workspace.copy(zoomSeconds = .5f + it / 10f); saveWorkspace()
        }
        addMotionButtons("zoom", maximum > 1f)
        if (maximum <= 1f) controls.addView(text("Esta lente não oferece controle de zoom pelo Android.", 12f, muted))
    }
    private fun buildRecordingPartsSettings() {
        section("Gravações longas em partes", "A mesma sessão continua em novos MP4s. As partes são finalizadas e salvas na galeria ao parar; o espaço disponível também limita a duração.")
        addPreferenceToggle("Dividir gravação em partes", "Desligado: um arquivo até o limite de segurança. Ligado: arquivos do tamanho escolhido, com a mesma tomada e partes numeradas.",
            { workspace.splitEnabled }, { !busy }) { workspace = workspace.copy(splitEnabled = it); saveWorkspace() }
        settingItem("Tamanho alvo de cada parte", "${workspace.splitSizeMb} MiB", { !busy && workspace.splitEnabled }) {
            val sizes = intArrayOf(64, 256, 512, 1024, 2048, 3072)
            AlertDialog.Builder(this).setTitle("Tamanho das partes").setItems(sizes.map { "$it MiB${if (it == 64) " · teste de troca" else ""}" }.toTypedArray()) { _, index ->
                workspace = workspace.copy(splitSizeMb = sizes[index]); saveWorkspace(); buildControls()
            }.setNegativeButton("Cancelar", null).show()
        }
        if (state == EngineState.RECORDING && engine.recordingSegmentState().splitEnabled) {
            partStatusLabel = text("Parte atual: ${engine.recordingSegmentState().index}", 12f, accent)
            controls.addView(partStatusLabel)
        }
        controls.addView(text("Sem espaço para a próxima parte, o app encerra a sessão. Um encerramento abrupto do Android pode deixar a última parte incompleta; partes anteriores são preservadas para recuperação.", 12f, muted))
    }
    private fun buildRackSettings(info: CameraInfo?) {
        section("Foco A/B", "Defina duas distâncias e uma duração. Esta ferramenta move a lente e requer foco manual; o Cinema digital tem seus próprios controles abaixo.")
        val maximum = info?.minFocus?.coerceAtLeast(0f) ?: 0f
        val available = info?.manualFocus == true && maximum > 0f
        addSlider("Distância A · infinito → perto", 100, if (maximum > 0f) (workspace.focusA / maximum * 100).roundToInt() else 0, { available }, { "$it%" }) {
            workspace = workspace.copy(focusA = maximum * it / 100f); saveWorkspace()
        }
        addSlider("Distância B · infinito → perto", 100, if (maximum > 0f) (workspace.focusB / maximum * 100).roundToInt() else 0, { available }, { "$it%" }) {
            workspace = workspace.copy(focusB = maximum * it / 100f); saveWorkspace()
        }
        controls.addView(row().apply {
            addButton(button("Atual → A") { currentLensFocus()?.let { workspace = workspace.copy(focusA = it); saveWorkspace(); buildControls() } }.availableWhen { adjustable && available })
            addButton(button("Atual → B") { currentLensFocus()?.let { workspace = workspace.copy(focusB = it); saveWorkspace(); buildControls() } }.availableWhen { adjustable && available })
        }, LinearLayout.LayoutParams(-1, -2))
        addSlider("Duração A/B", 195, ((workspace.focusSeconds - .5f) * 10).roundToInt(), { available }, { String.format(Locale.getDefault(), "%.1f s", .5f + it / 10f) }) {
            workspace = workspace.copy(focusSeconds = .5f + it / 10f); saveWorkspace()
        }
        addMotionButtons("focus", available)
        if (!available) controls.addView(text("Foco A/B óptico indisponível nesta lente. Desfoque e Cinema continuam disponíveis como efeitos digitais.", 12f, muted))
    }
    private fun currentLensFocus(): Float? = if (settings.focusEnabled) settings.focusDiopters else engine.currentFocusDiopters()
        ?: run { toast("A câmera ainda não informou a distância do foco."); null }
    private fun addMotionButtons(kind: String, available: Boolean) {
        controls.addView(row().apply {
            addButton(button("A → B") { runMotion(kind, false) }.availableWhen { adjustable && available })
            addButton(button("B → A") { runMotion(kind, true) }.availableWhen { adjustable && available })
            addButton(button("Parar") { cancelMotion(); buildControls() }.availableWhen { motion != null })
        }, LinearLayout.LayoutParams(-1, -2))
    }
    private fun runMotion(kind: String, reverse: Boolean) {
        if (!adjustable) return
        cancelMotion()
        val isZoom = kind == "zoom"
        val maximum = if (isZoom) engine.maxZoomRatio() else selected?.minFocus ?: 0f
        val minimum = if (isZoom) 1f else 0f
        if (maximum <= minimum || (!isZoom && selected?.manualFocus != true)) return
        val duration = CameraMotionPolicy.durationMs(if (isZoom) workspace.zoomSeconds else workspace.focusSeconds) ?: return
        val a = if (isZoom) workspace.zoomA else workspace.focusA
        val b = if (isZoom) workspace.zoomB else workspace.focusB
        if (!isZoom) settings = settings.copy(focusEnabled = true, focusLockEnabled = false)
        val started = SystemClock.elapsedRealtime()
        motionKind = kind
        val task = object : Runnable {
            override fun run() {
                if (motion !== this || !active || !adjustable) { if (motion === this) cancelMotion(); return }
                val elapsed = SystemClock.elapsedRealtime() - started
                val value = CameraMotionPolicy.sample(if (reverse) b else a, if (reverse) a else b, elapsed, duration, minimum, maximum)
                    ?: run { cancelMotion(); return }
                settings = if (isZoom) settings.copy(zoomRatio = value) else settings.copy(focusDiopters = value)
                applySettings()
                if (CameraMotionPolicy.completed(elapsed, duration)) { cancelMotion(); flushCapturePreferences(); if (sheet?.isShowing == true) buildControls() }
                else ui.postDelayed(this, 50L)
            }
        }
        motion = task; ui.post(task); refreshUi()
    }
    private fun cancelMotion() { motion?.let { ui.removeCallbacks(it) }; motion = null; motionKind = null }

    private fun validShutters(info: CameraInfo): List<Int> =
        listOf(24, 30, 48, 50, 60, 100, 120, 200, 240, 500, 1000, 2000, 4000).filter {
            info.exposureRange?.contains(1_000_000_000L / it) == true && it >= (video?.fps ?: 30)
        }

    private fun addSlider(label: String, maximum: Int, initial: Int, enabled: () -> Boolean,
        display: (Int) -> String, initialDisplay: String? = null, changed: (Int) -> Unit) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(2))
            background = rounded(0xFF10171E.toInt(), 16)
        }
        val heading = row().apply {
            gravity = Gravity.CENTER_VERTICAL
            if (uiLayout.stackSliderLabels) orientation = LinearLayout.VERTICAL
        }
        heading.addView(text(label, 12f, muted),
            if (uiLayout.stackSliderLabels) LinearLayout.LayoutParams(-1, -2) else LinearLayout.LayoutParams(0, -2, 1f))
        val value = text(initialDisplay ?: display(initial.coerceIn(0, maximum.coerceAtLeast(0))), 12f, accent).apply {
            typeface = Typeface.MONOSPACE; gravity = Gravity.END
            setPadding(dp(8), 0, 0, 0)
        }
        heading.addView(value, LinearLayout.LayoutParams(-2, -2))
        card.addView(heading, LinearLayout.LayoutParams(-1, -2))
        val slider = SeekBar(this).apply {
            minimumHeight = dp(48)
            max = maximum.coerceAtLeast(0); progress = initial.coerceIn(0, max)
            contentDescription = label
            progressTintList = ColorStateList.valueOf(accent)
            thumbTintList = ColorStateList.valueOf(accent)
            progressBackgroundTintList = ColorStateList.valueOf(CameraPalette.border)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    value.setTextIfChanged(display(progress))
                    if (fromUser) {
                        sliderChangeInProgress = true
                        try { changed(progress) } finally { sliderChangeInProgress = false }
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) { }
                override fun onStopTrackingTouch(seekBar: SeekBar) { flushCapturePreferences(); refreshUi() }
            })
        }
        card.addView(slider, LinearLayout.LayoutParams(-1, -2))
        controls.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        sliders.add(slider to enabled)
    }


    private fun buildLogSettings() {
        section("Perfil para edição", "LumaLog v2 reduz contraste e crominância; a LUT correspondente restaura ambos no editor.")
        controls.addView(button("Preparar imagem para edição") {
            settings = settings.preparedForEditing()
            applySettings(); buildControls()
            toast("LumaLog v2 a 100%. Cor criativa, ganho, nitidez, Flat e rastro desligados; controles da lente e retrato preservados.")
        }.apply { setTextColor(backgroundColor); background = touchSurface(accent, 16, outlined = false) }
            .availableWhen { adjustable && !busy }, LinearLayout.LayoutParams(-1, -2))
        addToggle("Gravar em LumaLog", { settings.logEnabled }, { !busy }) {
            settings = settings.copy(logEnabled = it)
        }
        controls.addView(button("Curva: LumaLog v${settings.logProfileVersion}${if (settings.logProfileVersion == 1) " · legado" else " · edição"}") {
            AlertDialog.Builder(this).setTitle("Versão do perfil")
                .setSingleChoiceItems(arrayOf("LumaLog v2 · contraste e cor suaves", "LumaLog v1 · curva anterior"), if (settings.logProfileVersion == 2) 0 else 1) { dialog, index ->
                    settings = settings.copy(logProfileVersion = if (index == 0) 2 else 1)
                    dialog.dismiss(); applySettings(); buildControls()
                }.setNegativeButton("Fechar", null).show()
        }.availableWhen { adjustable && !busy && settings.logEnabled }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        addSlider("Força do perfil", 100, (settings.logStrength * 100).roundToInt(), { settings.logEnabled && !busy }, { "$it%" }) {
            settings = settings.copy(logStrength = it / 100f); applySettings()
        }

        controls.addView(text("A assistência mostra a imagem convertida na tela, enquanto o arquivo continua em Log. Para editar, use a LUT inversa da mesma força gravada.", 12f, muted).apply {
            setPadding(dp(4), dp(12), dp(4), dp(12))
        })
        controls.addView(button("Exportar LUT LumaLog → SDR", ::exportLogLut).availableWhen { !busy },
            LinearLayout.LayoutParams(-1, -2))
        controls.addView(text("Versão e força ficam fixas durante o clipe. O nome do arquivo identifica o perfil. Ajustes de cor abaixo também serão gravados se você os ativar. Evite estourar os realces.", 12f, muted).apply {
            setPadding(dp(4), dp(14), dp(4), 0)
        })
    }

    private fun buildFocusSettings() {
        section("Foco da lente", "Toque na prévia para focar. A lente só responde aos controles que o aparelho oferece.")
        val info = selected
        addToggle("Foco manual da lente", { settings.focusEnabled }, { info?.manualFocus == true }) {
            cancelMotion(); settings = settings.copy(focusEnabled = it, focusLockEnabled = false)
        }
        val maximumFocus = info?.minFocus?.takeIf { it > 0f } ?: 1f
        addSlider("Distância · infinito → perto", 100, (settings.focusDiopters / maximumFocus * 100).roundToInt(),
            { info?.manualFocus == true && settings.focusEnabled }, { "$it%" }) {
            cancelMotion(); settings = settings.copy(focusDiopters = maximumFocus * it / 100f); applySettings()
        }
        addToggle("Travar foco da lente", { settings.focusLockEnabled }, { info?.tapFocusAvailable == true && !settings.focusEnabled }) {
            cancelMotion(); settings = settings.copy(focusLockEnabled = it)
        }
        controls.addView(text("Toque no alvo antes de travar. A trava mantém a distância alcançada pelo autofoco; o foco manual já mantém o valor escolhido. A confirmação depende da resposta da lente.", 12f, muted))
        controls.addView(button("Voltar ao foco automático da lente") {
            cancelMotion(); settings = settings.copy(focusEnabled = false, focusLockEnabled = false, subjectTrackingEnabled = false); applySettings()
            engine.clearFocusTarget()
        }.availableWhen { adjustable }, LinearLayout.LayoutParams(-1, -2))
        val objects = settings.subjectMode == SubjectFocusPolicy.OBJECTS
        controls.addView(button("Assunto: ${if (objects) "Objetos · seleção por toque" else "Pessoas · seleção automática"}", ::chooseSubjectMode)
            .availableWhen { adjustable }, LinearLayout.LayoutParams(-1, -2))
        section("Acompanhamento por IA", "A IA acompanha o recorte selecionado e move a área do autofoco. A lente ajusta a distância quando oferece suporte; a IA não mede profundidade real.")
        addToggle("Acompanhar assunto por IA", { settings.subjectTrackingEnabled }) {
            settings = settings.copy(subjectTrackingEnabled = it)
        }
        controls.addView(text("Escolha Pessoas ou Objetos acima e toque no alvo. Com a lente automática, a IA atualiza a área de foco. Com trava ou foco manual, o alvo continua acompanhado visualmente e a distância fica fixa. Desfoque é opcional.", 12f, muted))
        buildRackSettings(info)
        section("Desfoque por IA", "Escolha o assunto que será preservado no recorte. O foco da lente funciona independentemente desta escolha.")

        controls.addView(button("Ajuste automático · Pessoas") {
            chooseAutomaticBlur(SubjectFocusPolicy.PEOPLE)
        }.availableWhen { adjustable && !busy }, LinearLayout.LayoutParams(-1, -2))
        controls.addView(button("Ajuste automático · Objetos") {
            chooseAutomaticBlur(SubjectFocusPolicy.OBJECTS)
        }.availableWhen { adjustable && !busy }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        controls.addView(text("Escolha o assunto e a aparência. O app ajusta força, contorno e acompanhamento juntos e desliga o desfoque oval. Pessoas são detectadas automaticamente; para objetos, você seleciona o alvo com um toque.", 12f, muted).apply {
            setPadding(dp(4), dp(10), dp(4), dp(10))
        })

        addToggle("Desfoque por IA", { settings.portraitEnabled || settings.cinematicEnabled }) {
            settings = settings.copy(portraitEnabled = it, cinematicEnabled = if (it) settings.cinematicEnabled else false,
                objectPointSelected = if (it || settings.subjectTrackingEnabled) settings.objectPointSelected else false)
        }
        addToggle("Transição dinâmica de foco", { settings.cinematicEnabled }, { settings.portraitEnabled || settings.cinematicEnabled }) {
            settings = settings.copy(portraitEnabled = true, cinematicEnabled = it)
        }
        addSlider("Intensidade do desfoque IA", 100, (settings.portraitStrength * 100).roundToInt(),
            { settings.portraitEnabled || settings.cinematicEnabled }, { "$it%" }) {
            settings = settings.copy(portraitStrength = it / 100f); applySettings()
        }
        controls.addView(button(if (blurRefinementsExpanded) "Ocultar refinamentos do desfoque ▴" else "Refinamentos do desfoque ▾") {
            blurRefinementsExpanded = !blurRefinementsExpanded; buildControls()
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        if (blurRefinementsExpanded) {
            addSlider("Estabilidade do contorno", 100, (settings.portraitStability * 100).roundToInt(),
                { settings.portraitEnabled || settings.cinematicEnabled }, { "$it%" }) {
                settings = settings.copy(portraitStability = it / 100f); applySettings()
            }
            addSlider("Suavidade das bordas", 100, (settings.portraitEdgeSoftness * 100).roundToInt(),
                { settings.portraitEnabled || settings.cinematicEnabled }, { "$it%" }) {
                settings = settings.copy(portraitEdgeSoftness = it / 100f); applySettings()
            }
            controls.addView(text("Mais estabilidade reduz oscilações, mas pode atrasar o contorno. Para movimento, comece com Natural. Bordas muito suaves e desfoque forte podem criar halos em cabelos e detalhes.", 12f, muted).apply {
                setPadding(dp(4), dp(12), dp(4), dp(8))
            })
            if (!objects) controls.addView(button(if (settings.portraitQuality == 0) "Análise IA: leve" else "Análise IA: qualidade") {
                settings = settings.copy(portraitQuality = 1 - settings.portraitQuality)
                applySettings(); buildControls()
            }.availableWhen { adjustable && (settings.portraitEnabled || settings.cinematicEnabled || settings.subjectTrackingEnabled) }, LinearLayout.LayoutParams(-1, -2))
        }
        addSlider("Duração da transição", 28, ((settings.cinematicTransitionSeconds - .2f) * 10).roundToInt(),
            { settings.cinematicEnabled }, { String.format(Locale.getDefault(), "%.1f s", .2f + it / 10f) }) {
            settings = settings.copy(cinematicTransitionSeconds = .2f + it / 10f); applySettings()
        }
        val target = when {
            settings.cinematicAutoFocus -> "Automático · preservar ${if (objects) "objeto" else "pessoas"}"
            settings.cinematicTapFocus -> "Selecionado pelo toque"
            settings.focusBackground -> "Fundo"
            else -> if (objects) "Objeto" else "Pessoa"
        }
        controls.addView(button("Preservar: $target", ::chooseCinematicTarget).availableWhen { adjustable && (settings.portraitEnabled || settings.cinematicEnabled) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        if (objects) controls.addView(button(if (settings.objectPointSelected) "Selecionar outro objeto" else "Selecionar objeto na prévia") {
            settings = settings.copy(objectPointSelected = false, focusBackground = false,
                cinematicTapFocus = false, objectTapRevision = nextRevision(settings.objectTapRevision))
            applySettings(); sheet?.dismiss()
            toast("Toque dentro do objeto. O recorte será calculado no celular.")
        }.availableWhen { adjustable && (settings.portraitEnabled || settings.cinematicEnabled || settings.subjectTrackingEnabled) }, LinearLayout.LayoutParams(-1, -2))
        controls.addView(text(if (objects)
            if (settings.subjectTrackingEnabled) "Toque dentro do objeto para selecionar o alvo. Um novo toque troca o alvo acompanhado; ao perder o recorte, toque novamente."
            else "Toque dentro do objeto para selecionar seu contorno. Com transição ativa, os próximos toques alternam entre o objeto e o fundo. Para trocar o objeto, use ‘Selecionar outro objeto’."
            else "Pessoas são recortadas automaticamente. Com transição ativa, toque na pessoa ou no fundo para mudar o que fica preservado. A lente tenta focar a área tocada quando suportado.", 12f, muted).apply {
            setPadding(dp(4), dp(14), dp(4), dp(14))
        })
        controls.addView(text(if (objects)
            "Recorte local experimental. Objetos pequenos, transparentes ou em movimento rápido podem perder o contorno; selecione novamente. A atualização da máscara pode ser mais lenta no A14."
            else "A transição usa o mesmo recorte do desfoque. Cabelos, mãos e movimento podem dificultar a separação do fundo.", 12f, muted).apply {
            setPadding(dp(4), dp(14), dp(4), 0)
        })
    }

    private fun chooseAutomaticBlur(subjectMode: Int) {
        if (!adjustable || busy) return
        val objects = subjectMode == SubjectFocusPolicy.OBJECTS
        AlertDialog.Builder(this).setTitle("Desfoque automático · ${if (objects) "Objetos" else "Pessoas"}")
            .setItems(arrayOf("Natural · recomendado", "Equilibrado · fundo mais suave", "Forte · efeito mais evidente")) { _, choice ->
                if (!adjustable || busy) return@setItems
                val style = when (choice) {
                    1 -> AutomaticBlurPolicy.BALANCED
                    2 -> AutomaticBlurPolicy.STRONG
                    else -> AutomaticBlurPolicy.NATURAL
                }
                settings = settings.preparedForAutomaticBlur(subjectMode, style)
                portraitStatus.text = SubjectFocusPolicy.status(
                    if (objects && !settings.objectPointSelected) SubjectFocusPolicy.StatusKind.WAITING_POINT
                    else SubjectFocusPolicy.StatusKind.ANALYZING, subjectMode)
                applySettings(); buildControls(); sheet?.dismiss()
                toast(if (objects) "Desfoque ajustado. Toque dentro do objeto para selecionar ou trocar o alvo."
                    else "Desfoque ajustado. A IA identifica e acompanha pessoas na prévia.")
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun chooseCinematicTarget() {
        val objects = settings.subjectMode == SubjectFocusPolicy.OBJECTS
        val labels = arrayOf("Automático · preservar ${if (objects) "objeto" else "pessoas"}",
            "Preservar ${if (objects) "objeto" else "pessoa"}", "Preservar fundo", "Escolher tocando na prévia")
        AlertDialog.Builder(this).setTitle("Alvo do desfoque digital")
            .setItems(labels) { _, choice ->
                settings = settings.copy(cinematicAutoFocus = choice == 0,
                    cinematicTapFocus = choice == 3, focusBackground = choice == 2)
                applySettings(); buildControls()
                if (choice == 3) {
                    settings = settings.copy(portraitEnabled = true, cinematicEnabled = true)
                    applySettings(); sheet?.dismiss()
                    toast(if (objects && !settings.objectPointSelected) "Primeiro toque dentro do objeto para selecioná-lo."
                        else "Toque ${if (objects) "no objeto" else "na pessoa"} ou no fundo da prévia.")
                }
            }.setNegativeButton("Fechar", null).show()
    }

    private fun chooseSubjectMode() {
        AlertDialog.Builder(this).setTitle("Assunto do desfoque IA")
            .setSingleChoiceItems(arrayOf("Pessoas · recorte automático", "Objetos · recorte por toque"), settings.subjectMode) { dialog, choice ->
                settings = settings.copy(subjectMode = choice, objectPointSelected = false,
                    objectTapRevision = nextRevision(settings.objectTapRevision), cinematicTapFocus = false,
                    cinematicAutoFocus = choice == SubjectFocusPolicy.PEOPLE, focusBackground = false)
                portraitStatus.text = SubjectFocusPolicy.status(
                    if (choice == SubjectFocusPolicy.OBJECTS) SubjectFocusPolicy.StatusKind.WAITING_POINT
                    else SubjectFocusPolicy.StatusKind.ANALYZING, choice)
                dialog.dismiss(); applySettings(); buildControls()
            }.setNegativeButton("Fechar", null).show()
    }

    private fun nextRevision(value: Long): Long = if (value == Long.MAX_VALUE) 0L else value + 1L

    private fun lensModeDescription(): String = when {
        settings.focusEnabled -> "Lente · foco manual"
        selected?.afModes?.all { it == CaptureRequest.CONTROL_AF_MODE_OFF } == true -> "Lente · foco fixo"
        else -> "Lente · foco automático"
    }

    private fun exportLogLut() {
        if (busy) return
        lutExportStrength = settings.logStrength
        lutExportProfileVersion = settings.logProfileVersion
        try {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "application/octet-stream"
                putExtra(Intent.EXTRA_TITLE, "LumaLog-v$lutExportProfileVersion-SDR-${(lutExportStrength * 100).roundToInt()}.cube")
            }, 4)
        } catch (_: Exception) { toast("Nenhum aplicativo disponível para salvar a LUT.") }
    }

    private fun buildVideoSettings() {
        section("Gravação", "Selecione um modo oferecido pela câmera. A imagem processada é gravada em MP4.")
        settingItem("Câmera", selected?.label ?: "Aguardando permissão", { !busy && cameras.isNotEmpty() }, ::chooseLens)
        settingItem("Resolução e quadros", video?.let { "${it.width} × ${it.height} · ${it.fps} fps" } ?: "Sem modo disponível",
            { !busy && selected != null }, ::chooseVideo)
        controls.addView(button(if (settings.stabilizationEnabled) "Estabilização: ligada · ajustar" else "Estabilização: desligada · configurar") {
            showSettings(Page.STABILIZATION)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        settingItem("Microfone e áudio", "Entrada, rota e medidor", action = { showSettings(Page.AUDIO) })
        settingItem("Qualidade de compressão", bitratePresetLabel(), { !busy && selected != null }, ::chooseBitrate)
        section("Condições para gravar", "Consulte espaço, bateria e temperatura antes de uma sessão longa.")
        controls.addView(button("Consultar condições", ::showRecordingConditions), LinearLayout.LayoutParams(-1, -2))
        buildRecordingPartsSettings()
        val bitrate = if (state == EngineState.RECORDING) engine.configuredVideoBitrate()
            else video?.let { CapturePolicy.scaledBitrate(it.bitrate, settings.videoBitrateScale) }
        val bitrateLabel = bitrate?.let { String.format(Locale.getDefault(), "%.1f Mbps", it / 1_000_000f) } ?: "automático"
        val audioDescription = when {
            state == EngineState.RECORDING && !engine.recordingWithAudio() -> "sem áudio neste clipe"
            state != EngineState.RECORDING && !appPreferences.microphoneEnabled -> "desligado"
            else -> "AAC, 44,1 kHz · 128 kbps${if (!hasAudioPermission()) " · requer permissão" else ""}"
        }
        controls.addView(text("Formato: MP4 · H.264 · SDR\nÁudio: $audioDescription\nBitrate ${if (state == EngineState.RECORDING) "alvo configurado" else "alvo estimado"}: $bitrateLabel", 12f, muted).apply {
            setPadding(dp(4), dp(14), dp(4), dp(14))
        })
        controls.addView(text("O encoder pode limitar o alvo. O bitrate real varia com a cena; valores altos aumentam o arquivo sem criar detalhe que a câmera não captou. O medidor mostra picos relativos do microfone durante a gravação.", 12f, muted))
        controls.addView(text("Destino: Filmes/LumaCamera\nAbra a biblioteca para assistir, compartilhar ou recuperar gravações preservadas.", 12f, muted))
        controls.addView(button("Abrir biblioteca de vídeos", ::showVideo).apply {
            isEnabled = !busy; alpha = if (isEnabled) 1f else .45f
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        if (busy) controls.addView(text("Aguarde o fim da gravação para trocar o modo ou a câmera.", 12f, accent))
    }

    private fun bitratePresetLabel(): String = when {
        settings.videoBitrateScale < .8f -> "Econômica · 0,6×"
        settings.videoBitrateScale > 1.25f -> "Alta · 1,5×"
        else -> "Equilibrada · 1×"
    }

    private fun chooseBitrate() {
        if (busy) return
        val scales = floatArrayOf(.6f, 1f, 1.5f)
        val selectedIndex = scales.indices.minByOrNull { kotlin.math.abs(scales[it] - settings.videoBitrateScale) } ?: 1
        AlertDialog.Builder(this).setTitle("Qualidade de compressão")
            .setSingleChoiceItems(arrayOf("Econômica · arquivo menor", "Equilibrada · padrão", "Alta · menos compressão"), selectedIndex) { dialog, index ->
                settings = settings.copy(videoBitrateScale = scales[index]); applySettings(); buildControls(); dialog.dismiss()
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun buildMonitorSettings() {
        section("Monitor Pro", "Ferramentas da prévia para decidir exposição, foco e composição. Ative apenas as que precisar.")
        addToggle("Histograma do sinal gravado", { settings.histogramEnabled }) {
            settings = settings.copy(histogramEnabled = it)
        }
        menuHistogram = HistogramOverlay(this).apply { lastHistogram?.let { update(it) } }
        controls.addView(menuHistogram, LinearLayout.LayoutParams(-1, dp(if (resources.configuration.fontScale > 1.3f) 120 else 86)).apply {
            topMargin = dp(8); bottomMargin = dp(8)
        })
        section("Scopes de exposição e cor", "Sinal gravado, antes da assistência SDR e da LUT de prévia. Análise reduzida a 4 atualizações por segundo.")
        addToggle("Waveform · luminância por posição", { settings.waveformEnabled }) { settings = settings.copy(waveformEnabled = it) }
        addToggle("RGB parade · canais separados", { settings.rgbParadeEnabled }) { settings = settings.copy(rgbParadeEnabled = it) }
        addToggle("Vectorscope · crominância", { settings.vectorscopeEnabled }) { settings = settings.copy(vectorscopeEnabled = it) }
        menuScopes = ScopeOverlay(this).apply {
            configure(settings.waveformEnabled, settings.rgbParadeEnabled, settings.vectorscopeEnabled)
            lastScopes?.let { update(it) }
        }
        controls.addView(menuScopes, LinearLayout.LayoutParams(-1, dp(if (resources.configuration.fontScale > 1.3f) 190 else 150)).apply { topMargin = dp(8); bottomMargin = dp(8) })
        controls.addView(text("Waveform mostra onde estão os tons claros e escuros; RGB compara os canais; vectorscope mostra distribuição de cor. São aproximações SDR do sinal codificado, sem calibração de instrumentos externos.", 12f, muted))
        addToggle("Zebra · alerta de altas luzes", { settings.zebraEnabled }) {
            settings = settings.copy(zebraEnabled = it)
        }
        addSlider("Limiar da zebra · luminância SDR", 50, ((settings.zebraThreshold - .5f) * 100).roundToInt(),
            { settings.zebraEnabled }, { "${it + 50}%" }) {
            settings = settings.copy(zebraThreshold = .5f + it / 100f); applySettings()
        }
        addToggle("False color · mapa de luminância", { settings.falseColorEnabled }) {
            settings = settings.copy(falseColorEnabled = it)
        }
        addToggle("Focus peaking · realce de contornos", { settings.peakingEnabled }) {
            settings = settings.copy(peakingEnabled = it)
        }
        addSlider("Sensibilidade dos contornos", 100, (settings.peakingStrength * 100).roundToInt(),
            { settings.peakingEnabled }, { "$it%" }) {
            settings = settings.copy(peakingStrength = it / 100f); applySettings()
        }
        addSlider("Intensidade dos indicadores", 100, (settings.monitorOverlayStrength * 100).roundToInt(),
            { settings.zebraEnabled || settings.falseColorEnabled || settings.peakingEnabled }, { "$it%" }) {
            settings = settings.copy(monitorOverlayStrength = it / 100f); applySettings()
        }
        controls.addView(text("O histograma mede o sinal com seus efeitos e Log, antes da assistência SDR. Zebra e false color analisam a luminância SDR restaurada quando Log está ativo. São referências relativas, sem calibração IRE. Contornos ajudam a avaliar foco e também podem realçar textura e ruído.", 12f, muted).apply {
            setPadding(dp(4), dp(12), dp(4), dp(10))
        })
        controls.addView(text("False color: roxo → sombras profundas; azul/ciano → tons escuros; cinza/rosa → faixa central; verde → meios-tons claros; amarelo/laranja → claros; vermelho → altas luzes.", 12f, muted))
        section("Composição", "Guias visuais preservam o enquadramento e a proporção do arquivo.")
        addPreferenceToggle("Grade de composição", "Atalho da grade na tela principal.", { appPreferences.grid }) {
            appPreferences = appPreferences.copy(grid = it); saveAppPreferences(); refreshUi()
        }
        val gridLabels = arrayOf("Terços", "Cruz central", "Proporção áurea")
        settingItem("Estilo da grade", gridLabels[appPreferences.gridMode]) {
            chooseComposition("Estilo da grade", gridLabels, appPreferences.gridMode) { mode ->
                appPreferences = appPreferences.copy(gridMode = mode)
            }
        }
        val guideLabels = arrayOf("Sem guia", "Quadrado · 1:1", "Vertical · 9:16", "Horizontal · 16:9", "Cinema · 2,39:1")
        settingItem("Guia de proporção", guideLabels[appPreferences.frameGuide]) {
            chooseComposition("Guia de proporção", guideLabels, appPreferences.frameGuide) { mode ->
                appPreferences = appPreferences.copy(frameGuide = mode)
            }
        }
        addPreferenceToggle("Área segura · 90%", "Margem interna da guia, útil para manter elementos longe das bordas.", { appPreferences.safeArea }) {
            appPreferences = appPreferences.copy(safeArea = it); saveAppPreferences()
        }
        addPreferenceToggle("Nível de horizonte", "Alinhe o aparelho. Verde indica até 1,5°; olhando para cima ou para baixo o nível pode ficar indisponível.",
            { appPreferences.level }, { findLevelSensor() != null }) {
            appPreferences = appPreferences.copy(level = it); saveAppPreferences()
        }
        controls.addView(text("Todas estas ferramentas aparecem somente na prévia. As guias não recortam o vídeo e os indicadores não são gravados. Histograma e contornos exigem processamento extra; desligue-os quando não precisar.", 12f, muted).apply {
            setPadding(dp(4), dp(12), dp(4), dp(10))
        })
    }

    private fun chooseComposition(title: String, labels: Array<String>, selectedIndex: Int, changed: (Int) -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setSingleChoiceItems(labels, selectedIndex) { dialog, index ->
            changed(index); saveAppPreferences(); buildControls(); dialog.dismiss()
        }.setNegativeButton("Cancelar", null).show()
    }

    private fun chooseShutterAngle() {
        val info = selected ?: return
        val range = info.exposureRange ?: return
        if (!adjustable || !info.manualSensor) return
        val fps = video?.fps ?: return
        val angles = floatArrayOf(45f, 90f, 180f, 270f, 360f)
        val current = ProfessionalCaptureTools.angleForExposure(settings.exposureNs, fps) ?: 180f
        val index = angles.indices.minByOrNull { kotlin.math.abs(angles[it] - current) } ?: 2
        AlertDialog.Builder(this).setTitle("Ângulo do obturador · $fps fps")
            .setSingleChoiceItems(angles.map { "${it.roundToInt()}°${if (it == 180f) " · movimento natural" else ""}" }.toTypedArray(), index) { dialog, choice ->
                val exposure = ProfessionalCaptureTools.exposureForAngle(angles[choice], fps, range.lower, range.upper)
                if (exposure == null) toast("A lente não oferece tempo de exposição compatível com este modo.")
                else {
                    settings = settings.copy(shutterEnabled = true, exposureNs = exposure)
                    applySettings(); buildControls()
                    val actual = ProfessionalCaptureTools.angleForExposure(exposure, fps) ?: angles[choice]
                    toast("Obturador ${actual.roundToInt()}° · 1/${(1_000_000_000.0 / exposure).roundToInt()} s. ISO mantém sua escolha independente.")
                }
                dialog.dismiss()
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun buildStabilizationSettings() {
        section("Estabilização", "Reduza tremores e pequenos giros na prévia e no vídeo. Escolha o tipo de movimento e ajuste a margem de correção.")
        addToggle("Estabilização digital", { settings.stabilizationEnabled }, { adjustable && !busy }) {
            settings = settings.copy(stabilizationEnabled = it)
        }
        val modes = arrayOf("Equilibrado", "Câmera parada", "Em movimento")
        val modeDescriptions = arrayOf("Uso geral: suaviza tremores e acompanha panorâmicas.",
            "Segurar o celular quase parado: mantém mais correção nas pequenas oscilações.",
            "Panorâmicas e deslocamentos: acompanha a direção intencional mais rapidamente.")
        settingItem("Tipo de movimento", modes[settings.stabilizationMode.coerceIn(0, 2)],
            { adjustable && settings.stabilizationEnabled && !busy }) {
            AlertDialog.Builder(this).setTitle("Tipo de movimento")
                .setSingleChoiceItems(modes, settings.stabilizationMode.coerceIn(0, 2)) { dialog, which ->
                    settings = settings.copy(stabilizationMode = which)
                    dialog.dismiss(); applySettings(); buildControls()
                }.setNegativeButton("Fechar", null).show()
        }
        controls.addView(text(modeDescriptions[settings.stabilizationMode.coerceIn(0, 2)], 12f, muted).apply {
            setPadding(dp(4), dp(6), dp(4), dp(12))
        })
        addToggle("Compensar pequenos giros", { settings.stabilizationHorizonCorrection },
            { settings.stabilizationEnabled && !busy }) {
            settings = settings.copy(stabilizationHorizonCorrection = it)
        }
        addSlider("Força da correção", 100, (settings.stabilizationStrength * 100).roundToInt(),
            { settings.stabilizationEnabled }, { "$it%" }) {
            settings = settings.copy(stabilizationStrength = it / 100f); applySettings()
        }
        addSlider("Suavidade · acompanha → suaviza", 100, (settings.stabilizationResponse * 100).roundToInt(),
            { settings.stabilizationEnabled }, { "$it%" }) {
            settings = settings.copy(stabilizationResponse = it / 100f); applySettings()
        }
        addSlider("Recorte de segurança · zoom", 21, ((settings.stabilizationCrop - .04f) * 100).roundToInt(),
            { settings.stabilizationEnabled && !busy }, { String.format(Locale.getDefault(), "%.2f×", 1.04f + it / 100f) }) {
            settings = settings.copy(stabilizationCrop = .04f + it / 100f); applySettings()
        }
        controls.addView(button(if (settings.stabilizationQuality == 0) "Análise: leve · até 10/s" else "Análise: precisa · até 15/s") {
            settings = settings.copy(stabilizationQuality = 1 - settings.stabilizationQuality)
            applySettings(); buildControls()
        }.availableWhen { adjustable && !busy && settings.stabilizationEnabled }, LinearLayout.LayoutParams(-1, -2))
        addToggle("Priorizar fundo com recorte IA", { settings.stabilizationUseSubjectMask },
            { settings.stabilizationEnabled && (settings.portraitEnabled || settings.cinematicEnabled || settings.subjectTrackingEnabled) }) {
            settings = settings.copy(stabilizationUseSubjectMask = it)
        }
        controls.addView(text("Com acompanhamento ou desfoque IA ativo e recorte recente, evita usar a pessoa ou objeto em movimento como referência. Sem recorte válido, usa a imagem. Você pode acompanhar sem desfocar; em Objetos, toque no alvo.", 12f, muted).apply {
            setPadding(dp(4), dp(10), dp(4), dp(8))
        })
        controls.addView(text("O recorte mantém a proporção e as dimensões do arquivo, mas diminui o campo de visão e pode reduzir detalhe. Quanto maior o zoom, maior a margem para corrigir tremores. Força 0% conserva somente o recorte.", 12f, muted).apply {
            setPadding(dp(4), dp(14), dp(4), dp(8))
        })
        controls.addView(text("Pequenos giros são compensados dentro da margem; não há nivelamento automático do horizonte. Caminhada forte, borrão, pouca luz ou fundo sem textura ainda podem ultrapassar a correção. Na perda de referência, o enquadramento retorna gradualmente. Comece com análise Leve e compare clipes com o recurso ligado e desligado.", 12f, muted).apply {
            setPadding(dp(4), dp(8), dp(4), dp(8))
        })
        if (busy) controls.addView(text("Escolha tipo de movimento, giros, recorte e análise antes de gravar. Força e suavidade continuam ajustáveis durante REC.", 12f, accent))
    }

    private fun buildAppSettings() {
        section("Preferências", "Suas escolhas ficam salvas no aparelho.")
        addPreferenceToggle("Manter tela ligada", "Enquanto o app estiver aberto.", { appPreferences.keepScreenOn }) {
            appPreferences = appPreferences.copy(keepScreenOn = it)
            saveAppPreferences(); applyScreenPreference()
        }
        addPreferenceToggle("Gravar com botão de volume", "Inicie ou pare a gravação na tela da câmera.", { appPreferences.volumeShutter }) {
            appPreferences = appPreferences.copy(volumeShutter = it); saveAppPreferences()
        }
        settingItem("Permissões", when {
            hasCameraPermission() && hasAudioPermission() -> "Câmera e microfone autorizados"
            hasCameraPermission() -> "Câmera autorizada · microfone pendente"
            hasAudioPermission() -> "Microfone autorizado · câmera pendente"
            else -> "Câmera e microfone pendentes"
        }, { !busy }) {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")))
            } catch (_: Exception) { toast("Abra Configurações → Apps → Luma Camera → Permissões.") }
        }
        settingItem("Recursos do aparelho", "Consultar e exportar diagnóstico", { !busy }, ::showDiagnostics)
        settingItem("Guia dos efeitos", "Como cada ajuste transforma a imagem", action = ::showEffects)
        settingItem("Licença e código fonte", "Projeto open source · Apache 2.0", action = ::showOpenSource)
        controls.addView(button("Restaurar controles e efeitos") {
            cancelMotion()
            settings = CaptureSettings()
            applySettings(); buildControls()
            engine.clearFocusTarget()
            toast("Controles e efeitos restaurados. Guias de composição preservadas.")
        }.availableWhen { !busy }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        @Suppress("DEPRECATION")
        val version = packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        controls.addView(text("LUMA CAMERA  $version\nProcessamento local. Sem envio para nuvem.\nISO, foco e obturador físicos dependem da lente.", 11f, muted).apply {
            setPadding(dp(4), dp(18), dp(4), dp(8))
        })
    }

    @Suppress("DEPRECATION")
    private fun addPreferenceToggle(label: String, description: String, checked: () -> Boolean,
        enabled: () -> Boolean = { true }, changed: (Boolean) -> Unit) {
        val toggle = Switch(this).apply {
            text = label; textSize = 13f; setTextColor(Color.WHITE)
            minHeight = dp(56)
            isChecked = checked(); isEnabled = enabled()
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = touchSurface(CameraPalette.elevated, 16)
            thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(accent, muted))
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(0x66C7F36B, CameraPalette.border))
            contentDescription = "$label. $description"
            setOnCheckedChangeListener { _, value -> if (!refreshingControls) { changed(value); refreshUi() } }
        }
        preferenceSwitches.add(Triple(toggle, checked, enabled))
        controls.addView(toggle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        controls.addView(text(description, 12f, muted).apply { setPadding(dp(4), dp(5), dp(4), dp(10)) })
    }

    private fun settingItem(label: String, value: String, enabled: () -> Boolean = { true }, action: () -> Unit) {
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = touchSurface(CameraPalette.elevated, 16)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            addView(text(label, 14f))
            addView(text(value, 12f, muted).apply { setPadding(0, dp(4), 0, 0) })
            isEnabled = enabled(); isClickable = true; isFocusable = true
            contentDescription = "$label: $value"
            alpha = if (enabled()) 1f else .45f
            setOnClickListener { if (enabled()) action() }
        }
        menuButtons.add(item to enabled)
        controls.addView(item, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }

    private fun resetCategory() {
        val defaults = CaptureSettings()
        settings = when (page) {
            Page.LOOK -> settings.copy(flatEnabled = false, gainEnabled = false, temperatureEnabled = false,
                contrastEnabled = false, saturationEnabled = false, sharpnessEnabled = false,
                gainStops = defaults.gainStops, temperature = defaults.temperature,
                contrast = defaults.contrast, saturation = defaults.saturation, sharpness = defaults.sharpness,
                logEnabled = false, logProfileVersion = 2, logStrength = 1f, logPreviewAssist = false, previewLutEnabled = false, previewLutStrength = 1f,
                trailEnabled = false, trailAmount = defaults.trailAmount,
                blurEnabled = false, blurAmount = defaults.blurAmount,
                blurCenterX = defaults.blurCenterX, blurCenterY = defaults.blurCenterY, blurRadius = defaults.blurRadius,
                )
            Page.FOCUS -> settings.copy(focusEnabled = false, focusDiopters = 0f, focusLockEnabled = false, subjectTrackingEnabled = false,
                subjectMode = SubjectFocusPolicy.PEOPLE, objectPointSelected = false,
                objectFocusX = .5f, objectFocusY = .5f, objectTapRevision = nextRevision(settings.objectTapRevision),
                portraitEnabled = false, portraitStrength = defaults.portraitStrength, portraitQuality = defaults.portraitQuality,
                portraitStability = defaults.portraitStability, portraitEdgeSoftness = defaults.portraitEdgeSoftness,
                cinematicEnabled = false, cinematicAutoFocus = true, cinematicTapFocus = false, focusBackground = false,
                cinematicFocusX = .5f, cinematicFocusY = .5f, cinematicTransitionSeconds = 1f)
            Page.SENSOR -> settings.copy(isoEnabled = false, shutterEnabled = false, evEnabled = false,
                aeLockEnabled = false, awbLockEnabled = false,
                whiteBalanceEnabled = false, iso = defaults.iso, exposureNs = defaults.exposureNs,
                ev = defaults.ev, whiteBalance = defaults.whiteBalance)
            Page.STABILIZATION -> settings.copy(stabilizationEnabled = false,
                stabilizationStrength = defaults.stabilizationStrength, stabilizationResponse = defaults.stabilizationResponse,
                stabilizationCrop = defaults.stabilizationCrop, stabilizationQuality = defaults.stabilizationQuality,
                stabilizationUseSubjectMask = defaults.stabilizationUseSubjectMask,
                stabilizationMode = defaults.stabilizationMode, stabilizationHorizonCorrection = defaults.stabilizationHorizonCorrection)
            Page.MONITOR -> settings.copy(histogramEnabled = false, waveformEnabled = false, rgbParadeEnabled = false, vectorscopeEnabled = false, zebraEnabled = false,
                falseColorEnabled = false, peakingEnabled = false,
                zebraThreshold = defaults.zebraThreshold, peakingStrength = defaults.peakingStrength,
                monitorOverlayStrength = defaults.monitorOverlayStrength)
            else -> settings
        }
        if (page == Page.MONITOR) {
            appPreferences = appPreferences.copy(grid = true, gridMode = 0, frameGuide = 0, safeArea = false, level = false)
            saveAppPreferences()
        }
        normalizeSensorSettings(); applySettings(); buildControls()
        if (page == Page.FOCUS) { cancelMotion(); engine.clearFocusTarget() }
    }

    private fun saveAppPreferences() {
        preferences.saveAppPreferences(appPreferences)
        applyCompositionSettings()
        configureLevelSensor()
    }

    private fun applyCompositionSettings() {
        if (!::grid.isInitialized) return
        grid.showGrid = appPreferences.grid
        grid.gridMode = appPreferences.gridMode
        grid.frameGuide = appPreferences.frameGuide
        grid.showSafeArea = appPreferences.safeArea
        grid.showLevel = appPreferences.level
    }

    private fun findLevelSensor(): Sensor? = sensors?.let {
        it.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: it.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: it.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    }

    private fun configureLevelSensor() {
        if (!active || !appPreferences.level) {
            if (levelListening) sensors?.unregisterListener(levelListener)
            levelListening = false
            grid.rollDegrees = Float.NaN
            return
        }
        if (levelListening) return
        levelSensor = findLevelSensor()
        lastLevelAt = 0L
        filteredGravity.fill(0f)
        grid.rollDegrees = Float.NaN
        levelListening = levelSensor?.let { sensors?.registerListener(levelListener, it, 100_000) } == true
    }
    private fun <T : View> T.availableWhen(condition: () -> Boolean): T {
        menuButtons.add(this to condition)
        isEnabled = condition(); alpha = if (isEnabled) 1f else .45f
        return this
    }
    private fun applyScreenPreference() {
        if (appPreferences.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    private fun applySettings() {
        // Only persistence is delayed. The capture worker receives the selected value immediately.
        capturePreferencesDirty = true
        ui.removeCallbacks(saveCapturePreferences)
        ui.postDelayed(saveCapturePreferences, 250L)
        engine.update(settings)
        if (settings.focusEnabled || lensStatus.text.toString() == "Lente · foco manual") lensStatus.setTextIfChanged(lensModeDescription())
        if (sliderChangeInProgress) refreshEffectBadge() else refreshUi()
    }
    private fun flushCapturePreferences() {
        ui.removeCallbacks(saveCapturePreferences)
        if (capturePreferencesDirty) {
            preferences.saveCaptureSettings(settings)
            capturePreferencesDirty = false
        }
    }
    private fun refreshEffectBadge() {
        val count = listOf(settings.flatEnabled, settings.gainEnabled, settings.temperatureEnabled, settings.blurEnabled,
            settings.contrastEnabled, settings.saturationEnabled, settings.sharpnessEnabled, settings.trailEnabled).count { it }
        effectBadge.setTextIfChanged(listOfNotNull(
            if (settings.logEnabled) "LOG v${settings.logProfileVersion} ${(settings.logStrength * 100).roundToInt()}%${if (settings.logPreviewAssist) " · TELA SDR" else ""}" else null,
            if (settings.portraitEnabled || settings.cinematicEnabled)
                "${if (settings.subjectMode == SubjectFocusPolicy.OBJECTS) "OBJETOS" else "PESSOAS"}${if (settings.cinematicEnabled) " · CINEMA" else ""}" else null,
            if (settings.previewLutEnabled) "TELA LUT" else null,
            if (settings.subjectTrackingEnabled) "SEGUIR IA" else null,
            if (settings.focusLockEnabled) "FOCO TRAVADO" else null,
            if (settings.stabilizationEnabled) "ESTAB" else null,
            if (settings.zebraEnabled || settings.falseColorEnabled || settings.peakingEnabled) "MONITOR" else null,
            if (count > 0) "$count EFEITO${if (count == 1) "" else "S"}" else null
        ).joinToString(" · ").ifEmpty { "ORIGINAL" })
    }
    private fun refreshUi() {
        val info = selected
        formatChip.setTextIfChanged(video?.let {
            val name = when (it.height) { 2160 -> "4K"; 1080 -> "1080p"; 720 -> "720p"; else -> "${it.width}×${it.height}" }
            "$name · ${it.fps} FPS"
        } ?: "MODO VÍDEO")
        formatChip.contentDescription = "Formato de gravação: ${formatChip.text}. Abrir resolução e FPS."
        formatChip.setAvailable(!busy && info != null)
        val silent = if (state == EngineState.RECORDING) !engine.recordingWithAudio() else !appPreferences.microphoneEnabled
        lensLabel.setTextIfChanged((info?.label ?: "Câmera") + if (silent) " · sem áudio" else "")
        lensButton.setAvailable(!busy && cameras.size > 1)
        galleryButton.setAvailable(!busy)
        gridButton.accented = appPreferences.grid
        refreshingControls = true
        switches.forEach { (toggle, checked, available) ->
            val wanted = checked()
            if (toggle.isChecked != wanted) toggle.isChecked = wanted
            toggle.setAvailable(adjustable && available())
        }
        preferenceSwitches.forEach { (toggle, checked, available) ->
            val wanted = checked()
            if (toggle.isChecked != wanted) toggle.isChecked = wanted
            toggle.setAvailable(available())
        }
        refreshingControls = false
        sliders.forEach { (slider, enabled) ->
            slider.setAvailable(adjustable && enabled())
        }
        menuButtons.forEach { (view, enabled) ->
            view.setAvailable(enabled())
        }
        wbButton?.let {
            it.setTextIfChanged("Balanço: ${wbName(settings.whiteBalance)}")
            it.setAvailable(adjustable && settings.whiteBalanceEnabled && info?.awbModes?.any { mode -> mode != CaptureRequest.CONTROL_AWB_MODE_OFF } == true)
        }
        refreshEffectBadge()
        audioRouteLabel?.setTextIfChanged(engine.audioRouteStatus().message)
        portraitStatus.visibility = if (settings.portraitEnabled || settings.cinematicEnabled || settings.subjectTrackingEnabled) View.VISIBLE else View.GONE
        stabilizationStatus.visibility = if (settings.stabilizationEnabled) View.VISIBLE else View.GONE
        histogram.visibility = if (settings.histogramEnabled) View.VISIBLE else View.GONE
        menuHistogram?.visibility = if (settings.histogramEnabled) View.VISIBLE else View.GONE
        if (!settings.histogramEnabled || !adjustable) {
            histogram.clear(); menuHistogram?.clear(); lastHistogram = null
        }
        audioMeter.visibility = if (state == EngineState.RECORDING && settings.audioMeterEnabled && engine.recordingWithAudio())
            View.VISIBLE else View.GONE
        menuAudioMeter?.visibility = audioMeter.visibility
        if (state != EngineState.RECORDING || !settings.audioMeterEnabled) { audioMeter.clear(); menuAudioMeter?.clear() }
        if (recordButton.recording != (state == EngineState.RECORDING)) recordButton.recording = state == EngineState.RECORDING
        val shutterBusy = cameraOpening || recordingCommand != null || state == EngineState.STARTING || state == EngineState.SAVING || state == EngineState.OPENING
        if (recordButton.busy != shutterBusy) recordButton.busy = shutterBusy
        scopeOverlay?.configure(settings.waveformEnabled, settings.rgbParadeEnabled, settings.vectorscopeEnabled)
        if (!scopesEnabled()) scopeOverlay?.visibility = View.GONE
        menuScopes?.configure(settings.waveformEnabled, settings.rgbParadeEnabled, settings.vectorscopeEnabled)
        if (!scopesEnabled() || !adjustable) { scopeOverlay?.clear(); menuScopes?.clear(); lastScopes = null }
        recordButton.setAvailable(!permissionInFlight && recordingCommand == null && !cameraOpening && (!busy || state == EngineState.RECORDING))
        recordLabel.setTextIfChanged(when {
            !hasCameraPermission() -> "Permitir câmera"
            recordingCommand == RecordingCommand.START -> "Preparando"
            recordingCommand == RecordingCommand.STOP -> "Salvando"
            state == EngineState.RECORDING -> "Parar"
            state == EngineState.READY -> "Gravar"
            state == EngineState.STARTING -> "Preparando"
            state == EngineState.SAVING -> "Salvando"
            state == EngineState.OPENING -> "Abrindo"
            else -> "Reabrir câmera"
        })
        if (recordButton.contentDescription != recordLabel.text) recordButton.contentDescription = recordLabel.text
        sessionLabel.setTextIfChanged(when (state) {
            EngineState.RECORDING -> "GRAVANDO"
            EngineState.READY -> "PRONTO"
            EngineState.OPENING -> "ABRINDO CÂMERA"
            EngineState.STARTING -> "PREPARANDO"
            EngineState.SAVING -> "SALVANDO"
            EngineState.ERROR -> "VERIFICAR CÂMERA"
            else -> "SUA PRÓXIMA CENA"
        })
        val sessionColor = if (state == EngineState.RECORDING) CameraPalette.red else accent
        if (sessionLabel.currentTextColor != sessionColor) sessionLabel.setTextColor(sessionColor)
        // Routine readiness is already visible above; keep failures and unusual engine messages readable.
        status.visibility = if (status.text.startsWith("Pronto ·") || status.text.startsWith("Gravando ·")) View.GONE else View.VISIBLE
        timerLabel.visibility = if (state == EngineState.RECORDING) View.VISIBLE else View.GONE
        if (state != EngineState.RECORDING) timerLabel.setTextIfChanged("00:00")
        emptyPreview.visibility = if (!hasCameraPermission()) View.VISIBLE else View.GONE
        requestMonitorLayout()
    }

    private fun scanCameras() {
        try {
            allCameras = catalog.scan()
            cameras = allCameras.filter { it.previewSizes.isNotEmpty() && it.modes.isNotEmpty() }
            cameraIndex = cameras.indexOfFirst { it.id == appPreferences.defaultCameraId }.takeIf { it >= 0 }
                ?: cameras.indexOfFirst { !it.front }.coerceAtLeast(0)
            val info = selected
            video = info?.modes?.firstOrNull {
                it.width == appPreferences.videoWidth && it.height == appPreferences.videoHeight && it.fps == appPreferences.videoFps
            } ?: info?.defaultMode()
            normalizeSensorSettings()
            rememberSelection()
            if (sheet?.isShowing == true) buildControls()
            refreshUi()
            if (cameras.isEmpty()) status.text = "Nenhuma câmera compatível. Consulte App → Recursos do aparelho."
        } catch (error: Exception) {
            status.text = "Falha ao consultar a câmera: ${error.message}"
        }
    }

    private fun normalizeSensorSettings() {
        val info = selected ?: return
        val fps = video?.fps ?: 30
        val exposureUpper = minOf(info.exposureRange?.upper ?: Long.MAX_VALUE, 1_000_000_000L / fps)
        val exposureLower = info.exposureRange?.lower ?: 1L
        val physicalExposure = info.manualSensor && exposureLower <= exposureUpper
        settings = settings.copy(
            isoEnabled = settings.isoEnabled && info.manualSensor,
            shutterEnabled = settings.shutterEnabled && physicalExposure,
            aeLockEnabled = settings.aeLockEnabled && info.aeLockAvailable,
            awbLockEnabled = settings.awbLockEnabled && info.awbLockAvailable,
            iso = info.isoRange?.clamp(settings.iso) ?: settings.iso,
            exposureNs = if (exposureLower <= exposureUpper) settings.exposureNs.coerceIn(exposureLower, exposureUpper) else exposureLower,
            focusEnabled = settings.focusEnabled && info.manualFocus,
            focusDiopters = settings.focusDiopters.coerceIn(0f, info.minFocus.coerceAtLeast(0f)),
            evEnabled = settings.evEnabled && info.evRange.upper > info.evRange.lower,
            ev = info.evRange.clamp(settings.ev),
            whiteBalanceEnabled = settings.whiteBalanceEnabled && info.awbModes.contains(settings.whiteBalance),
            whiteBalance = if (info.awbModes.contains(settings.whiteBalance)) settings.whiteBalance else CaptureRequest.CONTROL_AWB_MODE_AUTO
        )
        preferences.saveCaptureSettings(settings)
    }

    private fun rememberSelection() {
        appPreferences = appPreferences.copy(defaultCameraId = selected?.id,
            videoWidth = video?.width ?: 0, videoHeight = video?.height ?: 0, videoFps = video?.fps ?: 0)
        saveAppPreferences()
    }

    private fun chooseVideo() {
        if (busy) return
        val info = selected ?: run { toast("Permita a câmera para escolher o modo."); return }
        val labels = info.modes.map { "${it.width} × ${it.height}   ·   ${it.fps} fps\n${String.format(Locale.getDefault(), "%.1f", it.bitrate / 1_000_000f)} Mbps" }
        val current = info.modes.indexOf(video)
        AlertDialog.Builder(this).setTitle("Resolução e FPS")
            .setSingleChoiceItems(labels.toTypedArray(), current) { dialog, index ->
                val chosen = info.modes[index]
                dialog.dismiss()
                if (video == chosen) return@setSingleChoiceItems
                video = chosen
                normalizeSensorSettings(); rememberSelection()
                if (sheet?.isShowing == true) buildControls()
                refreshUi(); reopen(force = true)
            }.setNegativeButton("Fechar", null).show()
    }

    private fun chooseLens() {
        if (busy || cameras.isEmpty()) return
        AlertDialog.Builder(this).setTitle("Selecionar câmera")
            .setSingleChoiceItems(cameras.map { it.label }.toTypedArray(), cameraIndex) { dialog, index ->
                dialog.dismiss()
                if (cameraIndex != index) selectCamera(index)
            }.setNegativeButton("Fechar", null).show()
    }

    private fun switchCamera() {
        if (busy || cameras.size < 2) return
        selectCamera((cameraIndex + 1) % cameras.size)
    }

    private fun selectCamera(index: Int) {
        val previousMode = video
        cameraIndex = index
        video = selected?.modes?.firstOrNull {
            previousMode != null && it.width == previousMode.width && it.height == previousMode.height && it.fps == previousMode.fps
        } ?: selected?.defaultMode()
        // Local creative settings survive lens changes; physical controls return to auto.
        settings = settings.withHardwareFrom(CaptureSettings())
        normalizeSensorSettings(); rememberSelection()
        if (sheet?.isShowing == true) buildControls()
        refreshUi(); reopen(force = true)
    }

    private fun wbName(mode: Int): String = when (mode) {
        CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT -> "Luz do dia"
        CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT -> "Nublado"
        CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT -> "Tungstênio"
        CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT -> "Fluorescente"
        CaptureRequest.CONTROL_AWB_MODE_WARM_FLUORESCENT -> "Fluorescente quente"
        CaptureRequest.CONTROL_AWB_MODE_TWILIGHT -> "Crepúsculo"
        CaptureRequest.CONTROL_AWB_MODE_SHADE -> "Sombra"
        else -> "Auto"
    }

    private fun chooseWhiteBalance() {
        val modes = selected?.awbModes?.filter { it != CaptureRequest.CONTROL_AWB_MODE_OFF }.orEmpty()
        if (modes.isEmpty()) return
        AlertDialog.Builder(this).setTitle("Balanço de branco")
            .setSingleChoiceItems(modes.map(::wbName).toTypedArray(), modes.indexOf(settings.whiteBalance)) { dialog, index ->
                dialog.dismiss()
                settings = settings.copy(whiteBalance = modes[index]); applySettings()
            }.setNegativeButton("Fechar", null).show()
    }

    private fun recordAction() {
        if (!active || permissionInFlight || recordingCommand != null || cameraOpening) return
        when {
            !hasCameraPermission() -> {
                permissionInFlight = true
                refreshUi()
                requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
            }
            state == EngineState.RECORDING -> {
                recordingCommand = RecordingCommand.STOP
                engine.stopRecording(); refreshUi()
            }
            state == EngineState.READY -> {
                if (!appPreferences.microphoneEnabled || hasAudioPermission()) {
                    requestRecording(appPreferences.microphoneEnabled && hasAudioPermission())
                } else if (audioPrompt?.isShowing != true) {
                    val prompt = AlertDialog.Builder(this).setTitle("Gravar com áudio?")
                    .setMessage("Permita o microfone para captar som. Também é possível gravar este vídeo sem áudio.")
                    .setPositiveButton("Permitir microfone") { _, _ ->
                        permissionInFlight = true
                        refreshUi()
                        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
                    }.setNeutralButton("Gravar sem áudio") { _, _ -> requestRecording(false) }
                    .setNegativeButton("Cancelar", null).create()
                    audioPrompt = prompt
                    prompt.setOnDismissListener { if (audioPrompt === prompt) audioPrompt = null }
                    prompt.show()
                }
            }
            !busy -> {
                if (cameras.isEmpty()) scanCameras()
                reopen()
            }
        }
    }

    private fun requestRecording(audio: Boolean) {
        if (!active || state != EngineState.READY || cameraOpening || recordingCommand != null || !hasCameraPermission()) return
        if (audio && !hasAudioPermission()) {
            toast("Microfone não autorizado. Permita o acesso ou escolha gravar sem áudio."); return
        }
        pendingAudioRecording = false
        submittedWorkspace = workspace
        engine.setRecordingOptions(RecordingOptions(audioDeviceId = workspace.audioDeviceId,
            splitEnabled = workspace.splitEnabled, splitSizeMb = workspace.splitSizeMb,
            projectName = workspace.project, sceneName = workspace.scene, takeNumber = workspace.take,
            captureMetadata = captureMetadata().toString()))
        recordingCommand = RecordingCommand.START
        engine.startRecording(audio)
        refreshUi()
    }

    /** Requested snapshots only: no continuous polling and no automatic capture changes. */
    private fun showRecordingConditions() {
        if (conditionsDialog?.isShowing == true) return
        val content = text("Consultando condições…", 13f).apply { setPadding(dp(16), dp(12), dp(16), dp(16)) }
        val dialog = AlertDialog.Builder(this).setTitle("Condições para gravar")
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton("Atualizar", null).setNegativeButton("Fechar", null).create()
        conditionsDialog = dialog
        dialog.setOnDismissListener { if (conditionsDialog === dialog) conditionsDialog = null }
        dialog.show()
        val refresh = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        fun read() {
            refresh.isEnabled = false
            val mode = video
            val currentState = state
            val session = when (currentState) {
                EngineState.READY -> "pronta"
                EngineState.RECORDING -> "gravando"
                EngineState.OPENING -> "abrindo"
                EngineState.STARTING -> "preparando gravação"
                EngineState.SAVING -> "finalizando arquivo"
                EngineState.ERROR -> "falha · reabra a câmera"
                EngineState.CLOSED -> "pausada"
            }
            val audio = if (currentState == EngineState.RECORDING) engine.recordingWithAudio()
                else appPreferences.microphoneEnabled && hasAudioPermission()
            val modeLine = mode?.let { "${it.width} × ${it.height} · ${it.fps} fps" } ?: "nenhum modo disponível"
            val audioLine = when {
                currentState == EngineState.RECORDING -> if (audio) "ativo neste clipe" else "sem áudio neste clipe"
                !appPreferences.microphoneEnabled -> "desligado"
                !hasAudioPermission() -> "microfone requer permissão antes de gravar"
                else -> "microfone autorizado"
            }
            val heading = "Sessão: $session\nCâmera: ${selected?.label ?: "indisponível"}\nModo: $modeLine\nÁudio: $audioLine\n" +
                if (!hasCameraPermission()) "Permita a câmera para iniciar.\n" else ""
            val requestedBitrate = mode?.let { CapturePolicy.scaledBitrate(it.bitrate, settings.videoBitrateScale) }
            val captureEngine = engine
            Thread({
                val health = SessionHealth.read(this).description()
                val storage = captureEngine.recordingStorageSnapshot()
                val space = if (storage == null) "Armazenamento: leitura indisponível" else buildString {
                    fun size(bytes: Long): String = String.format(Locale.getDefault(), "%.1f MiB", bytes.coerceAtLeast(0L) / 1_048_576.0)
                    append("Espaço livre: ").append(size(storage.availableBytes))
                    if (storage.recording) append("\nOriginais desta tomada: ").append(size(storage.recordedBytes))
                    if (storage.pendingPublicationBytes > 0L)
                        append("\nReserva para vídeos ainda sendo salvos: ").append(size(storage.pendingPublicationBytes))
                    if (!storage.recording && !StoragePolicy.canStart(storage.availableBytes))
                        append("\nEspaço insuficiente para iniciar e salvar com segurança.")
                    val remaining = StoragePolicy.remainingRecordingBytes(storage.availableBytes,
                        storage.recordedBytes, storage.fileLimitBytes, storage.pendingPublicationBytes)
                    val time = StoragePolicy.estimatedRemainingMs(remaining,
                        storage.videoBitrate ?: requestedBitrate ?: 0, if (storage.recording) storage.hasAudio else audio)
                    time?.let { append("\nTempo estimado disponível: ").append(MediaTime.format(it, allowZero = true)) }
                    append("\nEstimativa pelo bitrate alvo; a cena e o encoder alteram o tamanho. A reserva inclui a cópia necessária para salvar.")
                }
                runOnUiThread {
                    if (!isDestroyed && !isFinishing && conditionsDialog === dialog && dialog.isShowing) {
                        content.text = "$heading\n$space\n\n$health\n\nLeitura pontual. Nenhum ajuste de qualidade é alterado automaticamente."
                        refresh.isEnabled = true
                    }
                }
            }, "LumaConditions").start()
        }
        refresh.setOnClickListener { read() }
        read()
    }

    private fun showDiagnostics() {
        if (allCameras.isEmpty()) {
            toast("Permita a câmera para consultar recursos. ${catalog.scanErrors.joinToString()}")
            return
        }
        val content = TextView(this).apply {
            text = catalog.report(allCameras); textSize = 11f; setTextColor(Color.WHITE)
            setPadding(dp(16), dp(8), dp(16), dp(8)); setTextIsSelectable(true)
        }
        AlertDialog.Builder(this).setTitle("Recursos do aparelho")
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton("Exportar JSON") { _, _ ->
                try {
                    startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE); type = "application/json"
                        putExtra(Intent.EXTRA_TITLE, "luma-diagnostico.json")
                    }, 3)
                } catch (_: Exception) { toast("Nenhum app disponível para escolher o destino.") }
            }.setNegativeButton("Fechar", null).show()
    }

    private fun showEffects() {
        AlertDialog.Builder(this).setTitle("Guia dos efeitos")
            .setMessage("IMAGEM\nLog, Flat e cor estão juntos. Preparar imagem para edição ativa LumaLog v2 a 100% e desliga os ajustes que alteram a cor de forma criativa. A LUT correspondente restaura contraste e cor no editor. Monitorar em SDR restaura só a tela; o vídeo mantém Log.\n\nCOR E EFEITOS\nAbra Ajustes de cor e efeitos para ganho, temperatura, contraste, saturação, nitidez, Flat, oval e rastro. Cada chave é independente. Todos os ajustes ativados entram no vídeo.\n\nFOCO E DESFOQUE\nA lente foca a área tocada quando o aparelho permite. Os avisos Lente descrevem apenas o foco físico. Em Desfoque IA, Pessoas usam recorte automático; Objetos exigem um toque dentro do alvo. Transição dinâmica muda suavemente entre assunto e fundo. Use Selecionar outro objeto para trocar o alvo. Os avisos da IA descrevem o recorte, não confirmam foco da lente. O oval é um recorte geométrico diferente e fica em Imagem.\n\nMONITOR PRO\nHistograma do sinal gravado, zebra, false color, contornos, guias e nível aparecem só na prévia. Zebra/false color usam luminância SDR restaurada, inclusive com Log. Ative individualmente e desligue quando não precisar. As guias não recortam o arquivo.\n\nCÂMERA\nISO, obturador, EV e balanço de branco usam os recursos da lente. Desligue a chave para voltar ao automático. Ganho é brilho digital, independente do ISO.\n\nGRAVAÇÃO\nA prévia preserva proporção; o vídeo tem orientação aplicada aos pixels. A frontal aparece espelhada somente na prévia. Log simulado usa o SDR entregue pelo Android e não recupera realces estourados.")
            .setPositiveButton("Entendi", null).show()
    }

    private fun showOpenSource() {
        val license = runCatching { assets.open("licenses/LICENSE").bufferedReader().use { it.readText() } }
            .getOrDefault("Consulte a licença no repositório do projeto.")
        val content = text("Luma Camera · código original sob Apache 2.0.\n\nBibliotecas e modelos mantêm suas próprias condições. Media3 e MediaPipe usam Apache 2.0; ML Kit é um SDK binário Google.\n\n$license", 12f).apply {
            setPadding(dp(16), dp(12), dp(16), dp(16)); setTextIsSelectable(true)
        }
        AlertDialog.Builder(this).setTitle("Luma Camera · Open source")
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton("Ver código") { _, _ ->
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/henryrodrigues-afk/luma-camera"))) }
                catch (_: Exception) { toast("Código: github.com/henryrodrigues-afk/luma-camera") }
            }.setNegativeButton("Fechar", null).show()
    }

    private fun showVideo() {
        if (busy) return
        sheet?.dismiss()
        LibraryActivity.open(this)
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun hasCameraPermission() = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    private fun hasAudioPermission() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    @Suppress("DEPRECATION")
    private fun rotationDegrees(): Int = when (windowManager.defaultDisplay.rotation) {
        Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0
    }

    private fun reopen(force: Boolean = false) {
        if (!active || cameraOpening || !texture.isAvailable || !hasCameraPermission()) return
        val info = selected ?: return
        val mode = video ?: return
        val surface = texture.surfaceTexture ?: return
        val identity = CameraReopenPolicy.Identity(info.id, mode.width, mode.height, mode.fps, rotationDegrees())
        if (!CameraReopenPolicy.shouldOpen(openedIdentity, identity,
            state == EngineState.READY || state == EngineState.RECORDING, openedTexture === surface, force)) return
        cancelMotion()
        cameraOpening = true
        settings = settings.copy(objectPointSelected = false, cinematicTapFocus = false, focusLockEnabled = false)
        lensStatus.text = lensModeDescription()
        portraitStatus.text = SubjectFocusPolicy.status(
            if (settings.subjectMode == SubjectFocusPolicy.OBJECTS) SubjectFocusPolicy.StatusKind.WAITING_POINT
            else SubjectFocusPolicy.StatusKind.ANALYZING, settings.subjectMode)
        engine.update(settings)
        val fitted = fitPreview() ?: run { cameraOpening = false; return }
        openedIdentity = identity; openedTexture = surface
        engine.open(info, mode, surface, fitted, rotationDegrees())
        reloadPreviewLut()
        refreshUi()
    }
    private fun fitPreview(): Size? {
        val info = selected ?: return null
        val mode = video ?: return null
        if (frame.width <= 0 || frame.height <= 0) return null
        val relative = FrameGeometry.relativeRotation(info.orientation, rotationDegrees(), info.front)
        val fitted = FrameGeometry.fitSize(mode.width, mode.height, relative, frame.width, frame.height)
        listOf(texture, grid, focusReticle).forEach { view ->
            if (view.layoutParams.width != fitted.width || view.layoutParams.height != fitted.height) {
                view.layoutParams = FrameLayout.LayoutParams(fitted.width, fitted.height, Gravity.CENTER)
            }
        }
        // The GPU rotates the complete frame once; TextureView only presents its fitted buffer.
        texture.setTransform(Matrix())
        return Size(fitted.width, fitted.height)
    }


    override fun onResume() {
        super.onResume()
        active = true; volumeKeyHeld = false
        configureLevelSensor()
        if (hasCameraPermission() && cameras.isEmpty()) scanCameras()
        if (sheet?.isShowing == true) buildControls()
        reopen(); refreshUi()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putFloat("lutExportStrength", lutExportStrength)
        outState.putInt("lutExportProfileVersion", lutExportProfileVersion)
        super.onSaveInstanceState(outState)
    }
    override fun onPause() {
        cancelMotion()
        active = false; cameraOpening = false; volumeKeyHeld = false
        recordingCommand = null; openedIdentity = null; openedTexture = null
        audioPrompt?.dismiss()
        conditionsDialog?.dismiss()
        configureLevelSensor()
        ui.removeCallbacks(ticker)
        flushCapturePreferences()
        if (!permissionInFlight) pendingAudioRecording = false
        engine.pause()
        super.onPause()
    }
    override fun onDestroy() {
        lutLoadRevision++
        lutLoader.shutdownNow()
        sheet?.dismiss()
        engine.destroy()
        super.onDestroy()
    }
    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) { reopen() }
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) { fitPreview() }
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        cameraOpening = false
        openedIdentity = null; openedTexture = null
        engine.releaseTexture(surface)
        return false
    }
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) { }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) &&
            appPreferences.volumeShutter && sheet?.isShowing != true && active && adjustable) {
            if (!volumeKeyHeld && event.repeatCount == 0) {
                volumeKeyHeld = true; recordAction()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) && volumeKeyHeld) {
            volumeKeyHeld = false
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissionInFlight = false
        if (requestCode == 1) {
            if (hasCameraPermission()) { scanCameras(); cameraOpening = false; reopen() }
            else {
                status.text = "Câmera não autorizada. Acesse App → Permissões para permitir."
                refreshUi()
            }
        } else if (requestCode == 2) {
            if (hasAudioPermission()) {
                pendingAudioRecording = true
                if (active && state == EngineState.READY && !cameraOpening) {
                    pendingAudioRecording = false; requestRecording(true)
                }
            } else {
                pendingAudioRecording = false
                toast("Microfone não autorizado. Escolha Gravar sem áudio ou permita em App → Permissões.")
            }
        }
        refreshUi()
    }

    @Deprecated("Platform callback retained for the native interface")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 5 && resultCode == RESULT_OK) data?.data?.let { uri ->
            toast("Importando LUT de prévia…")
            Thread({
                val result = runCatching { contentResolver.openInputStream(uri)?.use { lutStore.importLut(it) } ?: error("Arquivo indisponível") }
                runOnUiThread {
                    if (!isDestroyed && !isFinishing) result.fold({ entry ->
                        workspace = workspace.copy(lutFile = entry.file); settings = settings.copy(previewLutEnabled = true)
                        saveWorkspace(); applySettings(); reloadPreviewLut(); if (sheet?.isShowing == true) buildControls()
                        toast("${entry.title}: ativa somente na prévia.")
                    }, { toast("LUT recusada: ${it.message}") })
                }
            }, "LumaLutImport").start()
        }
        if (requestCode == 6 && resultCode == RESULT_OK) data?.data?.let { uri ->
            val content = captureMetadata().toString(2)
            Thread({
                val result = runCatching { contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) } ?: error("Destino indisponível") }
                runOnUiThread { if (!isDestroyed && !isFinishing) result.fold({ toast("Ficha dos ajustes exportada.") }, { toast("Falha ao exportar ficha: ${it.message}") }) }
            }, "LumaCaptureReceipt").start()
        }
        if (requestCode == 3 && resultCode == RESULT_OK) data?.data?.let { uri ->
            try {
                contentResolver.openOutputStream(uri)?.use { it.write(catalog.report(allCameras).toByteArray(Charsets.UTF_8)) }
                    ?: error("Destino indisponível")
                toast("Diagnóstico exportado.")
            } catch (error: Exception) { toast("Falha ao exportar: ${error.message}") }
        }
        if (requestCode == 4 && resultCode == RESULT_OK) data?.data?.let { uri ->
            val strength = lutExportStrength
            val profileVersion = lutExportProfileVersion
            toast("Salvando LUT LumaLog…")
            Thread({
                val result = runCatching {
                    val size = if (strength > 0f && strength < 1f) 65 else 33
                    contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use {
                        it.write(SimulatedLog.decodeCube(size = size, strength = strength, profileVersion = profileVersion))
                    } ?: error("Destino indisponível")
                }
                runOnUiThread {
                    if (!isDestroyed && !isFinishing) result.fold(
                        onSuccess = { toast("LUT LumaLog salva. Use em vídeos gravados com a mesma força do perfil.") },
                        onFailure = { toast("Falha ao exportar LUT: ${it.message}") }
                    )
                }
            }, "LumaLutExport").start()
        }
    }
}
