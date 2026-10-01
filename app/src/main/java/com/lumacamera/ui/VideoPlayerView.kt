package com.lumacamera.ui

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.AudioManager
import android.media.AudioFocusRequest
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.SurfaceView
import android.view.SurfaceHolder
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.text.TextUtils
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.ui.AspectRatioFrameLayout
import com.lumacamera.core.PlaybackCheckpoint
import com.lumacamera.core.VideoPlaybackPolicy
import com.lumacamera.core.VideoPlaybackPolicy.Backend
import com.lumacamera.core.MediaTime
import com.lumacamera.core.ResponsiveUiPolicy
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.concurrent.Executors

/** Main-thread controls; bounded internal decoder/surface alternatives preserve one user timeline. */
@OptIn(UnstableApi::class)
class VideoPlayerView(context: Context) : LinearLayout(context) {
    var onStatus: (String) -> Unit = {}
    var onOpenExternal: () -> Unit = {}
    var muted: Boolean = false
        set(value) {
            field = value
            nativePlayer?.volume = if (value) 0f else 1f
            platformPlayer?.let { runCatching { it.setVolume(if (value) 0f else 1f, if (value) 0f else 1f) } }
            refreshControls()
        }
    var compatibilityMode: Boolean = false
        private set
    private val handler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val viewport = FrameLayout(context).apply { setBackgroundColor(Color.BLACK); minimumHeight = dp(64) }
    private val aspect = AspectRatioFrameLayout(context).apply { resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT }
    private val surface = SurfaceView(context).apply {
        setZOrderOnTop(false)
        contentDescription = "Imagem do vídeo"
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        if (Build.VERSION.SDK_INT >= 34) setSurfaceLifecycle(SurfaceView.SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT)
    }
    private val texture = TextureView(context).apply {
        isOpaque = true; visibility = View.GONE
        contentDescription = "Imagem do vídeo em modo compatível"
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val buffering = ProgressBar(context).apply {
        indeterminateTintList = ColorStateList.valueOf(CameraPalette.lime)
        visibility = View.GONE
        contentDescription = "Carregando vídeo"
    }
    private val failureContent = LinearLayout(context).apply {
        orientation = VERTICAL; gravity = Gravity.CENTER
        setPadding(dp(20), dp(16), dp(20), dp(16))
    }
    private val failure = ScrollView(context).apply {
        isFillViewport = true; setBackgroundColor(CameraPalette.surface); visibility = View.GONE
        addView(failureContent, FrameLayout.LayoutParams(-1, -2))
    }
    private val errorText = TextView(context).apply {
        textSize = 13f; setTextColor(CameraPalette.muted); gravity = Gravity.CENTER
        setPadding(0, dp(8), 0, dp(16))
    }
    private val stateText = TextView(context).apply {
        textSize = 11f; setTextColor(CameraPalette.muted)
        maxLines = 2; setPadding(dp(12), dp(6), dp(12), dp(2))
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val currentTime = timeLabel()
    private val totalTime = timeLabel()
    private val seek = SeekBar(context).apply {
        max = VideoPlaybackPolicy.SEEK_STEPS; minimumHeight = dp(48)
        progressTintList = ColorStateList.valueOf(CameraPalette.lime)
        thumbTintList = ColorStateList.valueOf(CameraPalette.lime)
        progressBackgroundTintList = ColorStateList.valueOf(CameraPalette.elevated)
        contentDescription = "Posição de reprodução"
    }
    private val play = action("Reproduzir", true) { togglePlay() }
    private val back = action("−5 s", false) { skip(-5_000L) }.apply { contentDescription = "Voltar cinco segundos" }
    private val forward = action("+5 s", false) { skip(5_000L) }.apply { contentDescription = "Avançar cinco segundos" }
    private val audio = action("Som", false) { muted = !muted }
    private var nativePlayer: ExoPlayer? = null
    private var platformPlayer: MediaPlayer? = null
    private var platformSurface: Surface? = null
    private var platformPrepared = false
    private var platformFailed = false
    private var lastKnownDuration: Long? = null
    private var platformSeeking = false
    private var platformPendingSeek: Long? = null
    private var platformSeekAt = 0L
    private var platformState = Player.STATE_IDLE
    private var platformBuffering = false
    private var platformBufferedPercent = 0
    private var platformFrames = 0
    private var platformWidth = 0
    private var platformHeight = 0
    private var platformRotation = 0
    private var nativeFocusSuppressed = false
    private var focusGranted = false
    private var noisyRegistered = false
    private var backend = Backend.MEDIA3_SURFACE
    private var firstFrameSeen = false
    private var initializedAt = 0L
    private var surfaceMissingSince = -1L
    private val metadataWorker = Executors.newSingleThreadExecutor { work -> Thread(work, "LumaPlaybackMetadata") }
    private val platformAudioAttributes = android.media.AudioAttributes.Builder()
        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE).build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(platformAudioAttributes).setAcceptsDelayedFocusGain(true)
        .setOnAudioFocusChangeListener({ change -> handleNativeAudioFocus(change) }, handler).build()
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && hasPlayer()) {
                checkpoint = snapshot().copy(playWhenReady = false)
                if (scrubbing) scrubResume = false
                nativePlayer?.pause()
                audioPauseReason = "Fones desconectados · toque em Reproduzir"
                applyPlatformPlay(); refreshControls()
            }
        }
    }
    private var listener: Player.Listener? = null
    private var analytics: AnalyticsListener? = null
    private var uri: Uri? = null
    private var checkpoint = PlaybackCheckpoint()
    private var hostStarted = false
    private var hostResumed = false
    @Volatile private var disposed = false
    @Volatile private var revision = 0
    private var scrubbing = false
    private var scrubPosition = 0L
    private var scrubResume = false
    private var recovering = false
    private var bufferingSince = -1L
    private var lastPosition = 0L
    private var lastPositionAt = 0L
    private var lastVideoFrames = -1
    private var lastVideoFrameAt = 0L
    private var audioPauseReason: String? = null
    private val events = ArrayDeque<JSONObject>()
    private var openedAt = SystemClock.elapsedRealtime()
    private var videoDecoder: String? = null
    private var audioDecoder: String? = null
    private var audioUnderruns = 0
    private var droppedFrames = 0
    private var lastCounters = JSONObject()
    private var tickerScheduled = false
    private var maximumControlsHeight = Int.MAX_VALUE
    private val controlsBody = LinearLayout(context).apply { orientation = VERTICAL }
    private val controlsScroll = object : ScrollView(context) {
        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val height = MeasureSpec.getSize(heightSpec)
            val bounded = if (maximumControlsHeight != Int.MAX_VALUE &&
                (maximumControlsHeight < height || MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED))
                MeasureSpec.makeMeasureSpec(maximumControlsHeight, MeasureSpec.AT_MOST) else heightSpec
            super.onMeasure(widthSpec, bounded)
        }
    }.apply { isFillViewport = false; addView(controlsBody, FrameLayout.LayoutParams(-1, -2)) }
    private val ticker = object : Runnable {
        override fun run() {
            tickerScheduled = false
            if (disposed || !hostStarted || !hostResumed || !hasPlayer()) return
            checkPlaybackProgress()
            refreshControls()
        }
    }

    init {
        orientation = VERTICAL; setBackgroundColor(CameraPalette.surface)
        minimumHeight = dp(196)
        aspect.addView(surface, FrameLayout.LayoutParams(-1, -1))
        aspect.addView(texture, FrameLayout.LayoutParams(-1, -1))
        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { record("surface_created"); refreshControls() }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                record("surface_size", "${width}x$height"); refreshControls()
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) { record("surface_destroyed"); refreshControls() }
        })
        viewport.addView(aspect, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        viewport.addView(buffering, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER))
        failureContent.addView(TextView(context).apply {
            text = "Reprodução interrompida"; textSize = 19f; gravity = Gravity.CENTER
            setTextColor(CameraPalette.text); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, LayoutParams(-1, -2))
        failureContent.addView(errorText, LayoutParams(-1, -2))
        failureContent.addView(action("Tentar novamente", true) { retry() }, LayoutParams(-1, -2))
        failureContent.addView(action("Reproduzir em modo compatível", false) { useCompatibilityMode(true, true) },
            LayoutParams(-1, -2).apply { topMargin = dp(8) })
        failureContent.addView(action("Abrir em outro player", false) { onOpenExternal() },
            LayoutParams(-1, -2).apply { topMargin = dp(8) })
        viewport.addView(failure, FrameLayout.LayoutParams(-1, -1))
        addView(viewport, LayoutParams(-1, 0, 1f))
        controlsBody.addView(stateText, LayoutParams(-1, -2))
        val configuration = resources.configuration
        val compact = ResponsiveUiPolicy.layout(configuration.screenWidthDp.toFloat(),
            configuration.screenHeightDp.toFloat(), configuration.fontScale).cameraRail && configuration.fontScale <= 1.3f
        val timeline = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, dp(12), dp(if (compact) 8 else 0))
            if (compact) {
                addView(back, LayoutParams(dp(52), -2))
                addView(play, LayoutParams(dp(112), -2).apply { leftMargin = dp(6); rightMargin = dp(6) })
                addView(forward, LayoutParams(dp(52), -2).apply { rightMargin = dp(10) })
            }
            addView(currentTime, LayoutParams(-2, -2))
            addView(seek, LayoutParams(0, -2, 1f))
            addView(totalTime, LayoutParams(-2, -2))
            if (compact) addView(audio, LayoutParams(dp(52), -2).apply { leftMargin = dp(10) })
        }
        controlsBody.addView(timeline, LayoutParams(-1, -2))
        if (!compact) {
            val largeFont = configuration.fontScale > 1.3f
            val actions = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), 0, dp(10), dp(10))
            addView(back, LayoutParams(if (largeFont) -2 else dp(52), -2))
            addView(play, LayoutParams(if (largeFont) -2 else 0, -2, if (largeFont) 0f else 1f).apply { leftMargin = dp(6) })
            addView(forward, LayoutParams(if (largeFont) -2 else dp(52), -2).apply { leftMargin = dp(6) })
            addView(audio, LayoutParams(if (largeFont) -2 else dp(52), -2).apply { leftMargin = dp(6) })
            }
            if (largeFont) controlsBody.addView(HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = true; addView(actions, FrameLayout.LayoutParams(-2, -2))
            }, LayoutParams(-1, -2))
            else controlsBody.addView(actions, LayoutParams(-1, -2))
        }
        addView(controlsScroll, LayoutParams(-1, -2))
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar: SeekBar) {
                if (!hasPlayer()) return
                scrubbing = true; scrubResume = checkpoint.playWhenReady
                scrubPosition = position()
                nativePlayer?.pause()
                platformPlayer?.let { if (platformPrepared) runCatching { it.pause() } }
            }
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val target = VideoPlaybackPolicy.seekPosition(progress, duration()) ?: return
                if (scrubbing) {
                    scrubPosition = target; setText(currentTime, MediaTime.format(target, allowZero = true))
                } else commitSeek(target, checkpoint.playWhenReady) // TalkBack/keyboard seek has no touch-drag callback.
            }
            override fun onStopTrackingTouch(bar: SeekBar) {
                if (!scrubbing) return
                val requested = scrubResume
                val target = scrubPosition
                scrubbing = false
                commitSeek(target, requested)
            }
        })
        refreshControls()
    }

    fun bind(source: Uri, initial: PlaybackCheckpoint) {
        check(!disposed) { "Reprodutor já encerrado" }
        releaseNative(); uri = source
        checkpoint = VideoPlaybackPolicy.restore(initial)
        lastKnownDuration = null
        backend = if (compatibilityMode) Backend.MEDIA3_TEXTURE else Backend.MEDIA3_SURFACE
        recovering = false; audioPauseReason = null
        events.clear(); openedAt = SystemClock.elapsedRealtime(); audioUnderruns = 0; droppedFrames = 0
        videoDecoder = null; audioDecoder = null; lastCounters = JSONObject()
        hideFailure(); record("bind", source.scheme ?: "unknown")
        if (hostStarted) initialize() else refreshControls()
    }

    fun startHost() {
        if (disposed) return
        hostStarted = true; record("host_start")
        if (!hasPlayer() && uri != null && failure.visibility != View.VISIBLE) initialize()
    }

    fun resumeHost() {
        if (disposed) return
        hostResumed = true; record("host_resume"); resetProgressClock()
        if (hostStarted && !hasPlayer() && failure.visibility != View.VISIBLE) initialize()
        nativePlayer?.playWhenReady = VideoPlaybackPolicy.shouldPlay(hostStarted, hostResumed, checkpoint.playWhenReady)
        applyPlatformPlay()
        refreshControls()
    }

    fun pauseHost() {
        if (disposed) return
        checkpoint = snapshot()
        hostResumed = false; record("host_pause")
        if (scrubbing) { scrubbing = false; seekBackend(checkpoint.positionMs) }
        nativePlayer?.pause(); applyPlatformPlay(); abandonNativeFocus(); refreshControls()
    }

    fun stopHost() {
        if (disposed) return
        checkpoint = snapshot(); hostStarted = false; hostResumed = false
        scrubbing = false; record("host_stop"); releaseNative(); refreshControls()
    }

    fun snapshot(): PlaybackCheckpoint {
        if (scrubbing) return PlaybackCheckpoint(scrubPosition, scrubResume)
        if (!hasPlayer()) return checkpoint
        return VideoPlaybackPolicy.snapshot(position(), duration(), requestedPlayback(),
            hostResumed, checkpoint, playbackState() == Player.STATE_ENDED)
    }

    fun release() {
        if (disposed) return
        checkpoint = snapshot(); disposed = true; hostStarted = false; hostResumed = false
        scrubbing = false; releaseNative(); uri = null
        metadataWorker.shutdownNow()
        onStatus = {}; onOpenExternal = {}
    }

    fun useCompatibilityMode(enabled: Boolean, playNow: Boolean = false) {
        if (disposed) return
        checkpoint = snapshot().let { if (playNow) it.copy(playWhenReady = true) else it }
        compatibilityMode = enabled
        backend = if (enabled) Backend.MEDIA3_TEXTURE else Backend.MEDIA3_SURFACE
        record("compatibility", enabled.toString()); releaseNative()
        if (hostStarted) initialize()
    }

    /** Actual player events/counters only. No camera benchmark, frame estimate or video bytes are exported. */
    fun diagnostics(): String {
        captureCounters()
        return JSONObject().put("schema", 2).put("player", backend.name)
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}").put("androidApi", Build.VERSION.SDK_INT)
            .put("sourceScheme", uri?.scheme).put("positionMs", snapshot().positionMs)
            .put("requestedPlayback", snapshot().playWhenReady).put("muted", muted)
            .put("mediaVolume", audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
            .put("mediaVolumeMax", audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
            .put("compatibilityMode", compatibilityMode).put("videoDecoder", videoDecoder)
            .put("strategy", when (backend) {
                Backend.MEDIA3_SURFACE -> "Media3 1.4.1 / SurfaceView / MP4 edit lists respected"
                Backend.MEDIA3_TEXTURE -> "Media3 1.4.1 / TextureView / synchronous codec / ignore MP4 edit lists"
                Backend.ANDROID_NATIVE -> "Android MediaPlayer / TextureView / platform extractor"
            }).put("durationMs", duration()).put("surfaceReady", outputReady()).put("firstFrameSeen", firstFrameSeen)
            .put("audioDecoder", audioDecoder).put("audioUnderruns", audioUnderruns)
            .put("droppedVideoFrames", droppedFrames).put("latestDecoderCounters", lastCounters)
            .put("events", JSONArray(events.toList())).toString(2)
    }

    private fun initialize() {
        val source = uri ?: return
        if (disposed || !hostStarted || hasPlayer()) return
        if (backend == Backend.ANDROID_NATIVE) { initializePlatform(source); return }
        val token = ++revision
        hideFailure(); resetProgressClock(); firstFrameSeen = false; initializedAt = SystemClock.elapsedRealtime()
        bufferingSince = initializedAt
        try {
            val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
            if (backend == Backend.MEDIA3_TEXTURE) renderers.forceDisableMediaCodecAsynchronousQueueing()
            val extractors = DefaultExtractorsFactory()
            if (backend == Backend.MEDIA3_TEXTURE) {
                // An explicit fallback only: ignoring valid edit lists can change legitimate A/V alignment.
                extractors.setMp4ExtractorFlags(Mp4Extractor.FLAG_WORKAROUND_IGNORE_EDIT_LISTS)
            }
            val player = ExoPlayer.Builder(context)
                .setLooper(Looper.getMainLooper())
                .setRenderersFactory(renderers)
                .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context), extractors))
                .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(5_000, 15_000, 500, 1_000)
                    .setTargetBufferBytes(24 * 1_024 * 1_024).setBackBuffer(0, false).build())
                .setSeekBackIncrementMs(5_000).setSeekForwardIncrementMs(5_000).build()
            nativePlayer = player
            val callbacks = object : Player.Listener {
                private fun current() = !disposed && token == revision && nativePlayer === player
                override fun onPlaybackStateChanged(state: Int) {
                    if (!current()) return
                    record("state", state.toString())
                    if (state == Player.STATE_BUFFERING) {
                        if (bufferingSince < 0L) bufferingSince = SystemClock.elapsedRealtime()
                    } else {
                        bufferingSince = -1L; resetProgressClock()
                        if (state == Player.STATE_ENDED) checkpoint = PlaybackCheckpoint(0L, false)
                        if (state == Player.STATE_READY) recovering = false
                    }
                    refreshControls()
                }
                override fun onPlayWhenReadyChanged(ready: Boolean, reason: Int) {
                    if (!current()) return
                    record("play_intent", "$ready / reason=$reason")
                    val interrupted = !ready && (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY ||
                        reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS)
                    if (interrupted) {
                        checkpoint = checkpoint.copy(playWhenReady = false)
                        if (scrubbing) scrubResume = false
                        audioPauseReason = if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY)
                            "Fones desconectados · toque em Reproduzir" else "Áudio interrompido por outro app · toque em Reproduzir"
                    } else if (hostResumed && !scrubbing && player.playbackState != Player.STATE_ENDED) {
                        checkpoint = checkpoint.copy(playWhenReady = ready)
                        audioPauseReason = when (reason) {
                            Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> "Fones desconectados · toque em Reproduzir"
                            Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> "Áudio interrompido por outro app · toque em Reproduzir"
                            else -> null
                        }
                    }
                    resetProgressClock(); refreshControls()
                }
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (current()) { resetProgressClock(); refreshControls() }
                }
                override fun onPlaybackSuppressionReasonChanged(reason: Int) {
                    if (current()) { record("suppression", reason.toString()); refreshControls() }
                }
                override fun onTracksChanged(tracks: Tracks) {
                    if (!current()) return
                    record("tracks", "video=${tracks.isTypeSelected(C.TRACK_TYPE_VIDEO)} audio=${tracks.isTypeSelected(C.TRACK_TYPE_AUDIO)} hasAudio=${tracks.containsType(C.TRACK_TYPE_AUDIO)}")
                    refreshControls()
                }
                override fun onVideoSizeChanged(size: VideoSize) {
                    if (!current()) return
                    aspect.setAspectRatio(VideoPlaybackPolicy.displayAspectRatio(size.width, size.height,
                        size.pixelWidthHeightRatio, size.unappliedRotationDegrees))
                    record("video_size", "${size.width}x${size.height} rotation=${size.unappliedRotationDegrees}")
                }
                override fun onRenderedFirstFrame() {
                    if (current()) { firstFrameSeen = true; lastVideoFrameAt = SystemClock.elapsedRealtime(); record("first_frame"); refreshControls() }
                }
                override fun onPositionDiscontinuity(old: Player.PositionInfo, next: Player.PositionInfo, reason: Int) {
                    if (current()) { record("position_change", "$reason / ${next.positionMs}"); resetProgressClock(); refreshControls() }
                }
                override fun onPlayerError(error: PlaybackException) {
                    if (!current()) return
                    checkpoint = snapshot(); record("error", "${error.errorCodeName}: ${error.cause?.javaClass?.simpleName}: ${error.cause?.message?.take(500)}")
                    val recoverable = error.errorCode != PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND &&
                        error.errorCode != PlaybackException.ERROR_CODE_IO_NO_PERMISSION
                    recoverInternally(errorMessage(error), recoverable)
                }
            }
            listener = callbacks; player.addListener(callbacks)
            val metrics = object : AnalyticsListener {
                private fun current() = !disposed && token == revision && nativePlayer === player
                override fun onVideoDecoderInitialized(time: AnalyticsListener.EventTime, name: String, at: Long, duration: Long) {
                    if (current()) { videoDecoder = name; record("video_decoder", "$name / ${duration}ms") }
                }
                override fun onAudioDecoderInitialized(time: AnalyticsListener.EventTime, name: String, at: Long, duration: Long) {
                    if (current()) { audioDecoder = name; record("audio_decoder", "$name / ${duration}ms") }
                }
                override fun onAudioUnderrun(time: AnalyticsListener.EventTime, size: Int, ms: Long, sinceFeed: Long) {
                    if (current()) { audioUnderruns++; record("audio_underrun", "${size}bytes / ${ms}ms / lastFeed=${sinceFeed}ms") }
                }
                override fun onDroppedVideoFrames(time: AnalyticsListener.EventTime, count: Int, elapsed: Long) {
                    if (current()) { droppedFrames += count; record("dropped_frames", "$count / ${elapsed}ms") }
                }
                override fun onAudioSinkError(time: AnalyticsListener.EventTime, error: Exception) {
                    if (current()) record("audio_sink_error", "${error.javaClass.simpleName}: ${error.message?.take(500)}")
                }
                override fun onAudioCodecError(time: AnalyticsListener.EventTime, error: Exception) {
                    if (current()) record("audio_codec_error", "${error.javaClass.simpleName}: ${error.message?.take(500)}")
                }
                override fun onVideoCodecError(time: AnalyticsListener.EventTime, error: Exception) {
                    if (current()) record("video_codec_error", "${error.javaClass.simpleName}: ${error.message?.take(500)}")
                }
            }
            analytics = metrics; player.addAnalyticsListener(metrics)
            player.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            player.setHandleAudioBecomingNoisy(true)
            // Also observe while a timeline drag temporarily pauses Media3 and its own noisy receiver.
            registerNoisyReceiver()
            player.volume = if (muted) 0f else 1f
            if (backend == Backend.MEDIA3_TEXTURE) {
                player.setVideoTextureView(texture)
                player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
            } else player.setVideoSurfaceView(surface)
            player.setMediaItem(MediaItem.Builder().setUri(source).setMimeType(MimeTypes.VIDEO_MP4).build(), checkpoint.positionMs)
            player.playWhenReady = VideoPlaybackPolicy.shouldPlay(hostStarted, hostResumed, checkpoint.playWhenReady)
            player.prepare(); refreshControls()
        } catch (error: Exception) {
            record("initialize_error", "${error.javaClass.simpleName}: ${error.message?.take(500)}")
            recoverInternally("Não foi possível abrir este vídeo no reprodutor interno.")
        }
    }

    private fun initializePlatform(source: Uri) {
        val token = ++revision
        hideFailure(); resetProgressClock(); firstFrameSeen = false
        initializedAt = SystemClock.elapsedRealtime(); bufferingSince = initializedAt
        platformPrepared = false; platformFailed = false; platformSeeking = false; platformPendingSeek = null
        platformState = Player.STATE_BUFFERING; platformBuffering = false; platformBufferedPercent = 0
        platformFrames = 0; platformWidth = 0; platformHeight = 0; platformRotation = 0
        nativeFocusSuppressed = false
        videoDecoder = null; audioDecoder = null
        try {
            val player = MediaPlayer()
            platformPlayer = player
            fun current() = !disposed && token == revision && platformPlayer === player
            player.setAudioAttributes(platformAudioAttributes)
            player.setVolume(if (muted) 0f else 1f, if (muted) 0f else 1f)
            player.setOnPreparedListener {
                if (!current()) return@setOnPreparedListener
                platformPrepared = true; platformState = Player.STATE_READY; bufferingSince = -1L
                platformWidth = player.videoWidth; platformHeight = player.videoHeight
                updatePlatformAspect(); record("native_prepared", "duration=${duration()}")
                checkpoint = VideoPlaybackPolicy.restore(checkpoint, duration())
                if (checkpoint.positionMs > 0L) issuePlatformSeek(checkpoint.positionMs) else applyPlatformPlay()
                refreshControls()
            }
            player.setOnVideoSizeChangedListener { _, width, height ->
                if (current()) { platformWidth = width; platformHeight = height; updatePlatformAspect() }
            }
            player.setOnInfoListener { _, what, extra ->
                if (current()) {
                    record("native_info", "$what / $extra")
                    when (what) {
                        MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START -> {
                            firstFrameSeen = true; recovering = false; lastVideoFrameAt = SystemClock.elapsedRealtime()
                        }
                        MediaPlayer.MEDIA_INFO_BUFFERING_START -> {
                            platformBuffering = true; bufferingSince = SystemClock.elapsedRealtime()
                        }
                        MediaPlayer.MEDIA_INFO_BUFFERING_END -> {
                            platformBuffering = false; bufferingSince = -1L; resetProgressClock()
                        }
                    }
                    refreshControls()
                }
                true
            }
            player.setOnBufferingUpdateListener { _, percent ->
                if (current()) { platformBufferedPercent = percent.coerceIn(0, 100); refreshControls() }
            }
            player.setOnSeekCompleteListener {
                if (!current()) return@setOnSeekCompleteListener
                platformSeeking = false
                val pending = platformPendingSeek; platformPendingSeek = null
                if (pending != null) issuePlatformSeek(pending) else {
                    resetProgressClock(); applyPlatformPlay(); refreshControls()
                }
            }
            player.setOnCompletionListener {
                if (current()) {
                    platformState = Player.STATE_ENDED; checkpoint = PlaybackCheckpoint(0L, false)
                    recovering = false; abandonNativeFocus(); record("native_ended"); refreshControls()
                }
            }
            player.setOnErrorListener { _, what, extra ->
                if (current()) {
                    platformFailed = true // Getters are invalid in Error; snapshot uses the last healthy progress.
                    checkpoint = snapshot(); record("native_error", "$what / $extra")
                    recoverInternally("O Android não conseguiu reproduzir este arquivo. Uma gravação anterior com tempos inválidos pode precisar ser gravada novamente.")
                }
                true
            }
            texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(image: SurfaceTexture, width: Int, height: Int) {
                    if (current()) {
                        val attached = runCatching { attachPlatformSurface(image) }
                        if (attached.isFailure) recoverInternally("Não foi possível conectar a imagem do vídeo.")
                        else { applyPlatformPlay(); refreshControls() }
                    }
                }
                override fun onSurfaceTextureSizeChanged(image: SurfaceTexture, width: Int, height: Int) = Unit
                override fun onSurfaceTextureDestroyed(image: SurfaceTexture): Boolean {
                    if (current()) {
                        runCatching { if (platformPrepared && player.isPlaying) player.pause() }
                        runCatching { player.setSurface(null) }
                        platformSurface?.release(); platformSurface = null
                        record("native_surface_destroyed"); refreshControls()
                    }
                    return true // TextureView owns the SurfaceTexture; we own only its Surface wrapper.
                }
                override fun onSurfaceTextureUpdated(image: SurfaceTexture) {
                    if (current() && platformPrepared) {
                        platformFrames++; lastVideoFrameAt = SystemClock.elapsedRealtime()
                        if (platformFrames >= 2 && runCatching { player.isPlaying }.getOrDefault(false)) {
                            firstFrameSeen = true; recovering = false
                        }
                    }
                }
            }
            if (texture.isAvailable) texture.surfaceTexture?.let(::attachPlatformSurface)
            registerNoisyReceiver()
            player.setDataSource(context, source)
            player.prepareAsync()
            // Metadata is read off the UI thread; only the exact active generation can apply rotation.
            metadataWorker.execute {
                if (disposed || token != revision) return@execute
                var metadata: MediaMetadataRetriever? = null
                val geometry = runCatching {
                    val retriever = MediaMetadataRetriever().also { metadata = it }
                    retriever.setDataSource(context, source)
                    intArrayOf(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0,
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0,
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0)
                }.getOrNull()
                runCatching { metadata?.release() }
                handler.post {
                    if (current() && geometry != null) {
                        if (geometry[0] > 0 && geometry[1] > 0) {
                            platformWidth = geometry[0]; platformHeight = geometry[1]
                        }
                        platformRotation = geometry[2]; updatePlatformAspect()
                    }
                }
            }
            refreshControls()
        } catch (error: Exception) {
            record("native_initialize_error", "${error.javaClass.simpleName}: ${error.message?.take(500)}")
            recoverInternally("Não foi possível ler o vídeo no reprodutor do Android.", false)
        }
    }

    private fun attachPlatformSurface(image: SurfaceTexture) {
        val player = platformPlayer ?: return
        val output = Surface(image)
        try { player.setSurface(output) }
        catch (error: Exception) { output.release(); throw error }
        platformSurface?.release(); platformSurface = output
        record("native_surface_ready")
    }

    private fun updatePlatformAspect() {
        aspect.setAspectRatio(VideoPlaybackPolicy.displayAspectRatio(platformWidth, platformHeight, 1f, platformRotation))
    }

    private fun handleNativeAudioFocus(change: Int) {
        if (disposed || platformPlayer == null) return
        if (!hostStarted || !hostResumed) {
            runCatching { audioManager.abandonAudioFocusRequest(focusRequest) }
            focusGranted = false; nativeFocusSuppressed = false
            return
        }
        record("native_audio_focus", change.toString())
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> { focusGranted = true; nativeFocusSuppressed = false; audioPauseReason = null }
            AudioManager.AUDIOFOCUS_LOSS -> {
                focusGranted = false; nativeFocusSuppressed = false
                checkpoint = snapshot().copy(playWhenReady = false)
                if (scrubbing) scrubResume = false
                audioPauseReason = "Áudio interrompido por outro app · toque em Reproduzir"
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                focusGranted = false; nativeFocusSuppressed = true
            }
        }
        applyPlatformPlay(); resetProgressClock(); refreshControls()
    }

    private fun applyPlatformPlay() {
        val player = platformPlayer ?: return
        if (!platformPrepared || platformSeeking) return
        try {
            val requested = VideoPlaybackPolicy.shouldPlay(hostStarted, hostResumed, checkpoint.playWhenReady)
            if (!VideoPlaybackPolicy.shouldRunNative(hostStarted, hostResumed, checkpoint.playWhenReady,
                    scrubbing, platformSeeking, outputReady(), nativeFocusSuppressed)) {
                if (player.isPlaying) player.pause()
                if (!requested) abandonNativeFocus()
                return
            }
            if (!focusGranted) {
                val result = audioManager.requestAudioFocus(focusRequest)
                focusGranted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                nativeFocusSuppressed = !focusGranted
                if (!focusGranted) return
            }
            player.start(); platformState = Player.STATE_READY
        } catch (error: Exception) {
            record("native_play_error", error.javaClass.simpleName)
            recoverInternally("O vídeo não pôde continuar no reprodutor do Android.")
        }
    }

    private fun abandonNativeFocus() {
        if (focusGranted || nativeFocusSuppressed) runCatching { audioManager.abandonAudioFocusRequest(focusRequest) }
        focusGranted = false; nativeFocusSuppressed = false
    }

    private fun registerNoisyReceiver() {
        if (noisyRegistered) return
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(noisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else {
            @Suppress("DEPRECATION")
            context.registerReceiver(noisyReceiver, filter)
        }
        noisyRegistered = true
    }

    private fun recoverInternally(message: String, recoverable: Boolean = true) {
        if (disposed) return
        checkpoint = snapshot()
        val next = VideoPlaybackPolicy.nextBackend(backend, recoverable)
        record("recovery", "${backend.name} -> ${next?.name ?: "failed"} / $message")
        releaseNative()
        if (next == null) { recovering = false; showFailure(message); return }
        backend = next; compatibilityMode = true; recovering = true
        if (hostStarted) initialize() else refreshControls()
    }

    private fun togglePlay() {
        if (!hasPlayer() || !hostResumed) return
        val player = nativePlayer
        if (suppressed()) {
            checkpoint = snapshot().copy(playWhenReady = true)
            nativeFocusSuppressed = false
            player?.pause(); player?.play(); applyPlatformPlay(); audioPauseReason = null
        } else if (playbackState() == Player.STATE_ENDED) {
            checkpoint = VideoPlaybackPolicy.play(snapshot(), true, duration())
            seekBackend(checkpoint.positionMs); player?.play(); applyPlatformPlay(); audioPauseReason = null
        } else if (requestedPlayback()) {
            checkpoint = snapshot().copy(playWhenReady = false); player?.pause(); applyPlatformPlay()
        } else {
            checkpoint = VideoPlaybackPolicy.play(snapshot(), false, duration())
            // Resume an ordinary pause without flushing the decoder through an unnecessary seek.
            player?.play(); applyPlatformPlay(); audioPauseReason = null
        }
        resetProgressClock(); refreshControls()
    }

    private fun skip(delta: Long) {
        if (!hasPlayer()) return
        commitSeek(VideoPlaybackPolicy.skip(position(), delta, duration()), checkpoint.playWhenReady)
    }

    private fun commitSeek(target: Long, requested: Boolean) {
        if (!hasPlayer()) return
        checkpoint = PlaybackCheckpoint(VideoPlaybackPolicy.restore(PlaybackCheckpoint(target), duration()).positionMs, requested)
        seekBackend(checkpoint.positionMs)
        nativePlayer?.playWhenReady = VideoPlaybackPolicy.shouldPlay(hostStarted, hostResumed, requested)
        applyPlatformPlay()
        record("seek", checkpoint.positionMs.toString()); resetProgressClock(); refreshControls()
    }

    private fun retry() {
        if (disposed) return
        checkpoint = snapshot().copy(playWhenReady = true)
        record("manual_retry"); releaseNative()
        if (hostStarted) initialize()
    }

    private fun checkPlaybackProgress() {
        if (!hasPlayer()) return
        val now = SystemClock.elapsedRealtime()
        val position = position()
        if (position != lastPosition) { lastPosition = position; lastPositionAt = now }
        val counter = nativePlayer?.videoDecoderCounters?.also { it.ensureUpdated() }?.renderedOutputBufferCount
            ?: platformPlayer?.let { platformFrames }
        if (counter != null && counter != lastVideoFrames) { lastVideoFrames = counter; lastVideoFrameAt = now }
        val suppressed = suppressed()
        val surfaceReady = outputReady()
        if (surfaceReady) surfaceMissingSince = -1L
        else if (surfaceMissingSince < 0L) surfaceMissingSince = now
        // READY + requested intent is watched even when isPlaying stays false without an error.
        val shouldAdvance = playbackState() == Player.STATE_READY && requestedPlayback() && !platformSeeking && !suppressed
        val stall = VideoPlaybackPolicy.stall(hostStarted && hostResumed && !scrubbing && !suppressed, checkpoint.playWhenReady,
            shouldAdvance, bufferingSince.takeIf { it >= 0L }?.let { now - it }, now - lastPositionAt,
            now - lastVideoFrameAt, selectedVideo(), surfaceReady,
            surfaceMissingSince.takeIf { it >= 0L }?.let { now - it }, firstFrameSeen,
            if (platformSeeking) now - platformSeekAt else null)
            ?: return
        checkpoint = snapshot(); record("stall", stall.name)
        recoverInternally("O vídeo continua sem avançar. A posição foi preservada. Se este arquivo foi gravado com tempos inválidos, faça uma nova gravação após atualizar o app.")
    }

    private fun refreshControls() {
        val player = nativePlayer
        val knownDuration = duration()
        val stateCode = playbackState()
        val ended = stateCode == Player.STATE_ENDED
        val suppressed = suppressed()
        val usable = hasPlayer() && failure.visibility != View.VISIBLE && hostResumed
        play.isEnabled = usable && !scrubbing; audio.isEnabled = usable
        setText(play, if (ended) "Rever" else if (suppressed) "Retomar áudio" else if (requestedPlayback() && !scrubbing) "Pausar" else "Reproduzir")
        play.contentDescription = play.text
        setText(audio, if (muted) "Mudo" else "Som")
        audio.contentDescription = if (muted) "Ativar áudio" else "Desativar áudio"
        val seekable = usable && knownDuration != null && (player?.isCurrentMediaItemSeekable == true || platformPrepared)
        seek.isEnabled = seekable; back.isEnabled = seekable && !scrubbing; forward.isEnabled = seekable && !scrubbing
        val position = VideoPlaybackPolicy.displayPosition(if (scrubbing) scrubPosition else position(), knownDuration)
        setText(currentTime, MediaTime.format(position, allowZero = true))
        setText(totalTime, MediaTime.format(knownDuration))
        if (!scrubbing) seek.progress = VideoPlaybackPolicy.seekProgress(position, knownDuration)
        val bufferedPosition = player?.bufferedPosition ?: knownDuration?.let { (it.toDouble() * platformBufferedPercent / 100).toLong() } ?: 0L
        seek.secondaryProgress = VideoPlaybackPolicy.seekProgress(bufferedPosition, knownDuration)
        buffering.visibility = if ((stateCode == Player.STATE_BUFFERING || platformSeeking) && failure.visibility != View.VISIBLE) View.VISIBLE else View.GONE
        val audioState = if (player != null && !player.currentTracks.isEmpty) when {
            !player.currentTracks.containsType(C.TRACK_TYPE_AUDIO) -> " · Sem áudio na gravação"
            !player.currentTracks.isTypeSelected(C.TRACK_TYPE_AUDIO) -> " · Áudio não selecionado ou incompatível"
            muted -> " · Mudo"
            audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0 -> " · Volume do celular em zero"
            else -> ""
        } else ""
        val state = when {
            failure.visibility == View.VISIBLE -> "Reprodução interrompida · posição preservada"
            !hasPlayer() -> "Abrindo vídeo…"
            suppressed -> "Outro app interrompeu o áudio · toque em Retomar áudio"
            audioPauseReason != null -> audioPauseReason!!
            recovering -> "Testando reprodução interna compatível…"
            stateCode == Player.STATE_BUFFERING -> "Carregando vídeo…"
            platformSeeking -> "Buscando a posição…"
            ended -> "Cena concluída · toque em Rever"
            scrubbing -> "Escolha a posição na barra"
            isPlaying() -> "Reproduzindo${if (backend == Backend.ANDROID_NATIVE) " · Android" else if (backend == Backend.MEDIA3_TEXTURE) " · Compatível" else ""}$audioState"
            else -> "Pausado$audioState"
        }
        if (stateText.text.toString() != state) { stateText.text = state; onStatus(state) }
        keepScreenOn = VideoPlaybackPolicy.shouldKeepScreenOn(hostStarted, hostResumed,
            requestedPlayback() && checkpoint.playWhenReady, hasPlayer(), ended, suppressed,
            failure.visibility == View.VISIBLE)
        val watch = !disposed && hostStarted && hostResumed && !scrubbing && hasPlayer() &&
            requestedPlayback() && checkpoint.playWhenReady && !suppressed &&
            (stateCode == Player.STATE_READY || stateCode == Player.STATE_BUFFERING || stateCode == Player.STATE_IDLE)
        if (watch && !tickerScheduled) { tickerScheduled = true; handler.postDelayed(ticker, 250L) }
        else if (!watch) stopTicker()
    }

    fun durationMs(): Long? = duration()
    private fun duration(): Long? {
        val value = (nativePlayer?.duration ?: platformPlayer?.takeIf { platformPrepared && !platformFailed }
            ?.let { runCatching { it.duration.toLong() }.getOrNull() })?.takeIf { it != C.TIME_UNSET && it > 0L }
        if (value != null) lastKnownDuration = value
        return value ?: lastKnownDuration
    }
    private fun hasPlayer() = nativePlayer != null || platformPlayer != null
    private fun playbackState(): Int = nativePlayer?.playbackState ?: if (platformBuffering) Player.STATE_BUFFERING else platformState
    private fun position(): Long = VideoPlaybackPolicy.displayPosition(nativePlayer?.currentPosition ?: when {
        platformFailed -> VideoPlaybackPolicy.failurePosition(checkpoint, lastPosition, platformPrepared, platformSeeking, duration())
        platformPrepared && !platformSeeking -> platformPlayer?.let {
            runCatching { it.currentPosition.toLong() }.getOrNull()
        } ?: VideoPlaybackPolicy.failurePosition(checkpoint, lastPosition, platformPrepared, platformSeeking, duration())
        else -> checkpoint.positionMs
    }, duration())
    private fun requestedPlayback() = nativePlayer?.playWhenReady ?: checkpoint.playWhenReady
    private fun suppressed() = nativePlayer?.let { it.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE }
        ?: nativeFocusSuppressed
    private fun isPlaying() = nativePlayer?.isPlaying ?: platformPlayer?.takeIf { platformPrepared }
        ?.let { runCatching { it.isPlaying }.getOrDefault(false) } ?: false
    private fun selectedVideo() = nativePlayer?.let { it.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO) || it.currentTracks.isEmpty }
        ?: (platformPlayer != null)
    private fun outputReady() = if (backend == Backend.MEDIA3_SURFACE) surface.holder.surface.isValid else texture.isAvailable &&
        (backend != Backend.ANDROID_NATIVE || platformSurface?.isValid == true)
    private fun seekBackend(target: Long) {
        nativePlayer?.seekTo(target)
        if (platformPrepared) {
            if (platformSeeking) platformPendingSeek = target else issuePlatformSeek(target)
        }
    }
    private fun issuePlatformSeek(target: Long) {
        val player = platformPlayer ?: return
        if (!platformPrepared) return
        platformSeeking = true; platformState = Player.STATE_READY
        platformSeekAt = SystemClock.elapsedRealtime()
        try {
            if (player.isPlaying) player.pause()
            player.seekTo(VideoPlaybackPolicy.displayPosition(target, duration()), MediaPlayer.SEEK_CLOSEST_SYNC)
        } catch (error: Exception) {
            // Keep the requested checkpoint until release; an Error-state getter cannot replace it.
            platformFailed = true; record("native_seek_error", error.javaClass.simpleName)
            recoverInternally("O Android não conseguiu buscar a posição deste vídeo.")
        }
    }
    private fun stopTicker() { handler.removeCallbacks(ticker); tickerScheduled = false }
    private fun resetProgressClock() {
        val now = SystemClock.elapsedRealtime()
        lastPosition = position()
        lastPositionAt = now; lastVideoFrameAt = now; lastVideoFrames = -1
        // Surface recreation after resume gets its own grace period, rather than the original clip age.
        surfaceMissingSince = if (outputReady()) -1L else now
        if (playbackState() == Player.STATE_BUFFERING) bufferingSince = now
    }
    private fun hideFailure() {
        failure.visibility = View.GONE
        surface.visibility = if (backend == Backend.MEDIA3_SURFACE) View.VISIBLE else View.GONE
        texture.visibility = if (backend == Backend.MEDIA3_SURFACE) View.GONE else View.VISIBLE
    }
    private fun showFailure(message: String) {
        stopTicker(); keepScreenOn = false
        surface.visibility = View.GONE; texture.visibility = View.GONE; buffering.visibility = View.GONE
        errorText.text = message; failure.visibility = View.VISIBLE; refreshControls()
    }
    private fun errorMessage(error: PlaybackException): String = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "O vídeo não está mais disponível. Volte à biblioteca e atualize."
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> "O Android não permitiu ler o vídeo. Volte à biblioteca e abra novamente."
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> "O arquivo está incompleto ou seu formato não foi reconhecido."
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "O decodificador não conseguiu reproduzir o vídeo. Experimente o modo compatível."
        else -> "Não foi possível reproduzir o vídeo. Tente novamente; a posição foi preservada."
    }
    private fun releaseNative() {
        stopTicker(); keepScreenOn = false; revision++
        captureCounters()
        val player = nativePlayer; nativePlayer = null
        listener?.let { player?.removeListener(it) }; listener = null
        analytics?.let { player?.removeAnalyticsListener(it) }; analytics = null
        if (player != null) {
            runCatching { player.clearVideoSurfaceView(surface); player.clearVideoTextureView(texture) }
            runCatching { player.release() }
        }
        val platform = platformPlayer; platformPlayer = null
        texture.surfaceTextureListener = null
        runCatching { platform?.setSurface(null) }; runCatching { platform?.release() }
        platformSurface?.release(); platformSurface = null
        platformPrepared = false; platformFailed = false; platformSeeking = false; platformPendingSeek = null
        platformState = Player.STATE_IDLE; platformBuffering = false
        if (noisyRegistered) { runCatching { context.unregisterReceiver(noisyReceiver) }; noisyRegistered = false }
        abandonNativeFocus()
        bufferingSince = -1L
    }
    private fun captureCounters() {
        val player = nativePlayer
        if (player == null) {
            if (platformPlayer != null) lastCounters = JSONObject().put("nativeFrames", platformFrames)
                .put("positionMs", position()).put("durationMs", duration()).put("playbackState", playbackState())
                .put("prepared", platformPrepared).put("seeking", platformSeeking).put("audioSuppressed", nativeFocusSuppressed)
            return
        }
        val video = player.videoDecoderCounters?.also { it.ensureUpdated() }
        val audioCounters = player.audioDecoderCounters?.also { it.ensureUpdated() }
        lastCounters = JSONObject().put("videoRendered", video?.renderedOutputBufferCount)
            .put("videoDropped", video?.droppedBufferCount).put("audioRendered", audioCounters?.renderedOutputBufferCount)
            .put("positionMs", player.currentPosition).put("bufferedMs", player.bufferedPosition)
            .put("playbackState", player.playbackState).put("suppressionReason", player.playbackSuppressionReason)
            .put("videoMime", player.videoFormat?.sampleMimeType).put("audioMime", player.audioFormat?.sampleMimeType)
    }
    private fun record(kind: String, detail: String = "") {
        if (events.size >= 64) events.removeFirst()
        events.addLast(JSONObject().put("elapsedMs", SystemClock.elapsedRealtime() - openedAt).put("event", kind).put("detail", detail))
    }
    private fun action(text: String, filled: Boolean, click: () -> Unit) = Button(context).apply {
        this.text = text; isAllCaps = false; textSize = 12f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        minimumWidth = 0; minimumHeight = dp(48); setPadding(dp(10), dp(8), dp(10), dp(8))
        setTextColor(if (filled) CameraPalette.background else CameraPalette.text)
        background = RippleDrawable(ColorStateList.valueOf(0x26ffffff),
            rounded(if (filled) CameraPalette.lime else CameraPalette.elevated, 12), rounded(Color.WHITE, 12))
        contentDescription = text; setOnClickListener { click() }
    }
    private fun timeLabel() = TextView(context).apply {
        textSize = 11f; setTextColor(CameraPalette.text); gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        minimumHeight = dp(48); maxWidth = dp(84); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
    }
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val available = MeasureSpec.getSize(heightSpec)
        maximumControlsHeight = if (available > 0)
            minOf(available, (available * .6f).toInt().coerceAtLeast(dp(48))) else Int.MAX_VALUE
        super.onMeasure(widthSpec, heightSpec)
    }
    private fun setText(view: TextView, value: String) {
        if (view.text.toString() != value) view.text = value
        if (view === currentTime || view === totalTime) view.contentDescription = value
    }
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
}
