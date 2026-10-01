package com.lumacamera

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.database.ContentObserver
import android.media.ThumbnailUtils
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.TextUtils
import android.text.format.DateFormat
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.lumacamera.camera.StoredVideo
import com.lumacamera.camera.VideoStore
import com.lumacamera.core.PlaybackCheckpoint
import com.lumacamera.core.MediaTime
import com.lumacamera.core.ResponsiveUiPolicy
import com.lumacamera.core.ExternalActionPolicy
import com.lumacamera.core.LibraryActionPolicy
import com.lumacamera.ui.CameraWindowInsets
import com.lumacamera.ui.CameraIcon
import com.lumacamera.ui.CameraIconButton
import com.lumacamera.ui.CameraPalette
import com.lumacamera.ui.VideoPlayerView
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import org.json.JSONObject

/** The library and player stay inside Luma; an external gallery/player is not required. */
class LibraryActivity : Activity() {
    private val background = Executors.newSingleThreadExecutor()
    // Thumbnail decode never queues ahead of opening, sharing or recovering a recording.
    private val thumbnails = Executors.newSingleThreadExecutor()
    private val thumbnailCache = object : LruCache<String, Bitmap>(6 * 1_024 * 1_024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private data class ThumbnailRow(val video: StoredVideo, val card: View, val image: ImageView, val key: String)
    private val thumbnailRows = mutableListOf<ThumbnailRow>()
    private val visibleThumbnails = mutableSetOf<ThumbnailRow>()
    private val pendingThumbnails = mutableSetOf<String>()
    private val failedThumbnails = mutableSetOf<String>()
    private var renderedVideos: List<StoredVideo>? = null
    private var libraryScroll: ScrollView? = null
    private var loadingLibrary = false
    private var reloadRequested = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var observingLibrary = false
    private val refreshFromGallery = Runnable { if (resumed && selected == null) loadLibrary() }
    private val galleryObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) {
            if (!resumed || selected != null) return
            mainHandler.removeCallbacks(refreshFromGallery)
            mainHandler.postDelayed(refreshFromGallery, 250L)
        }
    }
    @Volatile private var thumbnailCancellation: CancellationSignal? = null
    private lateinit var store: VideoStore
    private lateinit var page: LinearLayout
    private lateinit var list: LinearLayout
    private lateinit var subtitle: TextView
    private lateinit var refresh: View
    @Volatile private var generation = 0
    private var requestedUri: Uri? = null
    private var requestedName: String? = null
    private var selected: StoredVideo? = null
    private var player: VideoPlayerView? = null
    private var started = false
    private var resumed = false
    private var position = 0L
    private var resumePlayback = true
    private var restoring = false
    private var saving = false
    private var playbackMuted = false
    private var playbackCompatible = false
    private var diagnosticExport: String? = null
    private var recordingReceipts = emptyMap<String, JSONObject>()
    private var projectFilter: String? = null
    private var projectPicker: Button? = null
    private val externalActions = ExternalActionPolicy()
    private val ink = CameraPalette.background
    private val cardColor = CameraPalette.surface
    private val subtle = CameraPalette.muted
    private val accent = CameraPalette.lime
    private val logName = Regex("_LOGv(\\d+)_(\\d+)pct", RegexOption.IGNORE_CASE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = ink
        window.navigationBarColor = ink
        volumeControlStream = AudioManager.STREAM_MUSIC
        store = VideoStore(applicationContext)
        requestedUri = if (savedInstanceState != null) savedInstanceState.getString("video")?.let(Uri::parse) else intent.data
        requestedName = savedInstanceState?.getString("videoName")
            ?: requestedUri?.takeIf { it.authority == "$packageName.recordings" }?.lastPathSegment
        position = savedInstanceState?.getLong("positionMs", 0L) ?: 0L
        resumePlayback = savedInstanceState?.getBoolean("playing", true) ?: true
        restoring = savedInstanceState != null
        playbackMuted = savedInstanceState?.getBoolean("muted", false) ?: false
        playbackCompatible = savedInstanceState?.getBoolean("compatible", false) ?: false
        diagnosticExport = savedInstanceState?.getString("diagnosticExport")
        showLibrary()
    }

    override fun onStart() {
        super.onStart()
        started = true
        player?.startHost()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        externalActions.resume()
        if (!observingLibrary) observingLibrary = runCatching {
            contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, galleryObserver)
            true
        }.getOrDefault(false)
        if (selected == null) loadLibrary()
        else player?.resumeHost()
    }

    override fun onPause() {
        resumed = false
        externalActions.pause()
        mainHandler.removeCallbacks(refreshFromGallery)
        if (observingLibrary) { contentResolver.unregisterContentObserver(galleryObserver); observingLibrary = false }
        player?.pauseHost()
        super.onPause()
    }

    override fun onStop() {
        started = false
        player?.stopHost()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        (selected?.uri ?: requestedUri)?.let { outState.putString("video", it.toString()) }
        (selected?.name ?: requestedName)?.let { outState.putString("videoName", it) }
        val checkpoint = player?.snapshot() ?: PlaybackCheckpoint(position, resumePlayback)
        outState.putLong("positionMs", checkpoint.positionMs)
        outState.putBoolean("playing", checkpoint.playWhenReady)
        outState.putBoolean("muted", player?.muted ?: playbackMuted)
        outState.putBoolean("compatible", player?.compatibilityMode ?: playbackCompatible)
        diagnosticExport?.let { outState.putString("diagnosticExport", it) }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        generation++
        mainHandler.removeCallbacks(refreshFromGallery)
        if (observingLibrary) { contentResolver.unregisterContentObserver(galleryObserver); observingLibrary = false }
        thumbnailCancellation?.cancel()
        player?.release(); player = null
        // Accepted copies/diagnostic writes finish even after rotation; stale UI callbacks stay guarded.
        background.shutdown()
        thumbnails.shutdownNow()
        thumbnailRows.clear(); visibleThumbnails.clear()
        thumbnailCache.evictAll()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (selected != null) { showLibrary(); loadLibrary() }
        else super.onBackPressed()
    }

    private fun showLibrary() {
        externalActions.invalidate()
        generation++
        thumbnailCancellation?.cancel()
        pendingThumbnails.clear(); failedThumbnails.clear(); thumbnailRows.clear(); visibleThumbnails.clear()
        renderedVideos = null; loadingLibrary = false; reloadRequested = false
        player?.let { playbackMuted = it.muted; playbackCompatible = it.compatibilityMode }
        player?.release(); player = null; selected = null
        val compact = layoutPolicy().compactControls
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ink)
            setPadding(dp(if (compact) 10 else 18), dp(if (compact) 4 else 12), dp(if (compact) 10 else 18), 0)
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(icon(CameraIcon.BACK, "Voltar à câmera") { finish() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(4), 0)
            if (!compact) addView(label("LUMA  /  BIBLIOTECA", 10f, accent).apply {
                letterSpacing = .12f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
            addView(label("Suas cenas", if (compact) 20f else 28f).apply {
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
        }
        header.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        refresh = icon(CameraIcon.RESET, "Atualizar vídeos") { loadLibrary() }
        header.addView(refresh, LinearLayout.LayoutParams(dp(48), dp(48)))
        page.addView(header)
        subtitle = label("Carregando seus vídeos…", 12f, subtle).apply { setPadding(dp(4), dp(20), dp(4), dp(18)) }
        projectPicker = action("Todos os projetos", "Filtrar gravações por projeto", false) {
            val projects = recordingReceipts.values.map { it.optString("projectName") }.filter { it.isNotBlank() }.distinct().sorted()
            AlertDialog.Builder(this).setTitle("Projeto").setItems((listOf("Todos os projetos") + projects).toTypedArray()) { _, index ->
                projectFilter = projects.getOrNull(index - 1); loadLibrary()
            }.setNegativeButton("Cancelar", null).show()
        }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(20)) }
        list.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> refreshVisibleThumbnails() }
        val scrollContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(subtitle)
            addView(projectPicker, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
            addView(list, LinearLayout.LayoutParams(-1, -2))
        }
        libraryScroll = ScrollView(this).apply {
            isFillViewport = true; clipToPadding = false; isVerticalScrollBarEnabled = false
            addView(scrollContent)
            setOnScrollChangeListener { _: View, _: Int, _: Int, _: Int, _: Int -> refreshVisibleThumbnails() }
        }
        page.addView(libraryScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(page)
        CameraWindowInsets.bind(window, page)
    }

    private fun loadLibrary() {
        if (selected != null || isFinishing || isDestroyed) return
        if (loadingLibrary) { reloadRequested = true; return }
        // A refresh invalidates pending URI reads just like navigation; release the chooser ticket too.
        externalActions.invalidate()
        loadingLibrary = true
        val token = ++generation
        thumbnailCancellation?.cancel()
        pendingThumbnails.clear(); failedThumbnails.clear()
        refresh.isEnabled = false
        subtitle.text = "Carregando sua biblioteca…"
        background.execute {
            if (isDestroyed || generation != token) return@execute
            val result = try { store.list() } catch (_: Exception) { null }
            val receipts = result?.videos?.mapNotNull { video ->
                runCatching { store.recordingMetadata(video.name)?.let { video.name to JSONObject(it) } }.getOrNull()
            }?.toMap() ?: emptyMap()
            runOnUiThread {
                if (isDestroyed || isFinishing || generation != token || selected != null) return@runOnUiThread
                loadingLibrary = false
                val loadAgain = reloadRequested; reloadRequested = false
                refresh.isEnabled = true
                if (result == null) {
                    subtitle.text = "Não foi possível atualizar. Toque em atualizar para tentar novamente."
                    if (renderedVideos == null) renderUnavailable()
                    else refreshVisibleThumbnails()
                    if (loadAgain && resumed) mainHandler.post(refreshFromGallery)
                    return@runOnUiThread
                }
                recordingReceipts = receipts
                val shownVideos = if (projectFilter == null) result.videos else result.videos.filter {
                    receipts[it.name]?.optString("projectName") == projectFilter
                }
                projectPicker?.text = projectFilter ?: "Todos os projetos"
                val saved = result.videos.count { it.privateFile == null }
                val local = result.videos.size - saved
                subtitle.text = result.warning ?: when {
                    local > 0 -> "${result.videos.size} ${if (result.videos.size == 1) "cena" else "cenas"}  ·  $local aguardando salvar na galeria"
                    saved == 1 -> "1 cena  ·  Salva na galeria"
                    else -> "$saved cenas  ·  Suas gravações mais recentes"
                }
                // A lifecycle return with an unchanged album preserves its rows and scroll position.
                if (renderedVideos != shownVideos) {
                    thumbnailRows.clear(); visibleThumbnails.clear(); list.removeAllViews()
                    renderedVideos = shownVideos
                    if (shownVideos.isEmpty()) renderEmpty()
                    else shownVideos.forEach { addVideo(it) }
                }
                list.post { refreshVisibleThumbnails() }
                if (requestedUri != null || requestedName != null) {
                    val index = LibraryActionPolicy.restoredIndex(requestedUri?.toString(), requestedName,
                        result.videos.map { LibraryActionPolicy.ClipIdentity(it.uri.toString(), it.name) })
                    val found = index?.let { result.videos[it] }
                    if (found != null) showPlayer(found, restoring)
                    else subtitle.text = "O vídeo solicitado ainda não está disponível. Se estiver sendo salvo, ele aparecerá ao terminar; você também pode atualizar."
                    // Keep the identity/checkpoint while a publishing worker in the prior Activity finishes.
                }
                if (selected != null) restoring = false
                else if (loadAgain && resumed) mainHandler.post(refreshFromGallery)
            }
        }
    }

    private fun renderEmpty() {
        list.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = rounded(cardColor, 24f)
            setPadding(dp(24), dp(48), dp(24), dp(36))
            addView(CameraIconButton(this@LibraryActivity, CameraIcon.VIDEO, "Biblioteca de vídeos").apply {
                accented = true; isClickable = false; isFocusable = false
            }, LinearLayout.LayoutParams(dp(72), dp(72)).apply { bottomMargin = dp(20) })
            addView(label("Sua próxima cena\ncomeça aqui.", 25f).apply {
                gravity = Gravity.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            addView(label("Grave, reveja e compartilhe. Seus vídeos ficam reunidos neste espaço.", 14f, subtle).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(24))
            })
            addView(action("Voltar a gravar", "Voltar à câmera e gravar", true) { finish() }, LinearLayout.LayoutParams(-1, -2))
        }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun renderUnavailable() {
        list.removeAllViews()
        list.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            background = rounded(cardColor, 24f)
            setPadding(dp(24), dp(36), dp(24), dp(28))
            addView(label("Vamos buscar suas cenas", 21f).apply { gravity = Gravity.CENTER })
            addView(label("A biblioteca está indisponível neste momento. Você pode tentar carregar de novo.", 14f, subtle).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(24))
            })
            addView(action("Tentar novamente", "Atualizar biblioteca", true) { loadLibrary() }, LinearLayout.LayoutParams(-1, -2))
        }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun addVideo(video: StoredVideo) {
        val stackInfo = layoutPolicy().stackSliderLabels
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(cardColor, 22f).apply { setStroke(dp(1), CameraPalette.border) }
            setPadding(dp(10), dp(10), dp(10), dp(12))
        }
        val thumbnail = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(CameraPalette.elevated, 15f)
            clipToOutline = true
            contentDescription = "Prévia do vídeo ${video.name}"
        }
        val thumbFrame = FrameLayout(this).apply {
            background = rounded(CameraPalette.elevated, 15f); clipToOutline = true
            addView(thumbnail, FrameLayout.LayoutParams(-1, -1))
            addView(View(this@LibraryActivity).apply {
                background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(0x08000000, 0x88000000.toInt()))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, FrameLayout.LayoutParams(-1, -1))
            addView(CameraIconButton(this@LibraryActivity, CameraIcon.PLAY, "Reproduzir vídeo").apply {
                background = rounded(0xcc080c10.toInt(), 28f); tintColor = accent
                isClickable = false; isFocusable = false
            }, FrameLayout.LayoutParams(dp(52), dp(52), Gravity.CENTER))
            val badges = LinearLayout(this@LibraryActivity).apply {
                orientation = if (stackInfo) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, dp(12), dp(10))
            }
            val log = logName.find(video.name)
            badges.addView(pill(if (!video.validated) "NÃO VALIDADO" else
                log?.let { "LOG v${it.groupValues[1]} · ${it.groupValues[2]}%" } ?: "VÍDEO", 0xb3080c10.toInt(), Color.WHITE))
            if (!stackInfo) badges.addView(View(this@LibraryActivity), LinearLayout.LayoutParams(0, 1, 1f))
            badges.addView(pill(formatDuration(video.durationMs), 0xb3080c10.toInt(), Color.WHITE),
                LinearLayout.LayoutParams(-2, -2).apply { if (stackInfo) topMargin = dp(4) })
            addView(badges, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            setOnClickListener { showPlayer(video) }
            isFocusable = true
            contentDescription = "Reproduzir ${video.name}"
        }
        card.addView(thumbFrame, LinearLayout.LayoutParams(-1, dp(156)))
        val summary = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(14), dp(2), 0)
        }
        summary.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(DateFormat.format("dd MMM • HH:mm", Date(video.createdAt)).toString(), 16f).apply {
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            })
            addView(label(video.name, 11f, subtle).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE
                setPadding(0, dp(4), dp(8), 0)
            })
            recordingReceipts[video.name]?.let { receipt ->
                val project = receipt.optString("projectName")
                val scene = receipt.optString("sceneName")
                val identification = listOf(project, scene).filter { it.isNotBlank() }.joinToString(" / ")
                addView(label("${identification.take(90)}${if (identification.isBlank()) "" else " · "}T${receipt.optInt("takeNumber", 1)} · P${receipt.optInt("part", 1)}", 11f, accent).apply {
                    maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(4), dp(8), 0)
                })
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        summary.addView(icon(CameraIcon.SHARE, "Compartilhar ${video.name}") { share(video) },
            LinearLayout.LayoutParams(dp(48), dp(48)))
        card.addView(summary)
        val details = LinearLayout(this).apply {
            orientation = if (stackInfo) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(10), dp(6), dp(12))
        }
        details.addView(label(formatSize(video.sizeBytes), 12f, subtle),
            if (stackInfo) LinearLayout.LayoutParams(-1, -2) else LinearLayout.LayoutParams(0, -2, 1f))
        details.addView(pill(if (video.privateFile == null) "NA GALERIA" else "ORIGINAL NO APP", CameraPalette.elevated,
            if (video.privateFile == null) subtle else accent),
            LinearLayout.LayoutParams(-2, -2).apply { if (stackInfo) topMargin = dp(6) })
        card.addView(details)
        if (!video.validated) card.addView(label("Original preservado · a imagem e os tempos ainda não foram confirmados. Você pode inspecionar ou compartilhar o arquivo.",
            12f, subtle).apply { setPadding(dp(6), 0, dp(6), dp(12)) })
        val stackActions = stackInfo
        val actions = LinearLayout(this).apply { orientation = if (stackActions) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL }
        actions.addView(action(if (video.validated) "Reproduzir" else "Inspecionar", "Abrir ${video.name}", true) { showPlayer(video) },
            if (stackActions) LinearLayout.LayoutParams(-1, -2) else LinearLayout.LayoutParams(0, -2, 1f))
        if (video.privateFile != null) actions.addView(action("Salvar na galeria", "Publicar original preservado na galeria", false) { recover(video) },
            (if (stackActions) LinearLayout.LayoutParams(-1, -2) else LinearLayout.LayoutParams(0, -2, 1f)).apply {
                if (stackActions) topMargin = dp(8) else marginStart = dp(8)
            })
        card.addView(actions)
        list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
        thumbnailRows += ThumbnailRow(video, card, thumbnail, "${video.uri}|${video.sizeBytes}|${video.createdAt}")
    }

    /** Release offscreen ImageView references; only a small nearby window retains full bitmap previews. */
    private fun refreshVisibleThumbnails() {
        val scroll = libraryScroll ?: return
        if (selected != null || isDestroyed || isFinishing || scroll.height <= 0 || loadingLibrary) return
        if (thumbnailRows.firstOrNull()?.card?.height == 0) return // Wait for the row positions from layout.
        // Rows are relative to their list, below the scrollable subtitle/filter.
        val listOffset = list.top
        val start = scroll.scrollY - listOffset - dp(180)
        val end = scroll.scrollY - listOffset + scroll.height + dp(180)
        var available = (2 - pendingThumbnails.size).coerceAtLeast(0)
        val visible = visibleThumbnails.iterator()
        while (visible.hasNext()) {
            val row = visible.next()
            if (row.card.bottom < start || row.card.top > end) {
                row.image.setImageDrawable(null); visible.remove()
            }
        }
        // Rows have ordered bounds: avoid scanning the entire album for every scroll event.
        var low = 0
        var high = thumbnailRows.size
        while (low < high) {
            val middle = (low + high) / 2
            if (thumbnailRows[middle].card.bottom < start) low = middle + 1 else high = middle
        }
        for (index in low until thumbnailRows.size) {
            val row = thumbnailRows[index]
            if (row.card.top > end) break
            if (row.image.drawable != null) continue
            val cached = thumbnailCache.get(row.key)
            if (cached != null) { row.image.setImageBitmap(cached); visibleThumbnails += row }
            else if (available > 0 && row.key !in pendingThumbnails && row.key !in failedThumbnails) {
                available--; pendingThumbnails += row.key
                loadThumbnail(row, generation)
            }
        }
    }

    private fun loadThumbnail(row: ThumbnailRow, token: Int) {
        thumbnails.execute {
            if (generation != token || isDestroyed) return@execute
            val signal = CancellationSignal()
            thumbnailCancellation = signal
            val bitmap = try {
                if (generation != token) signal.cancel()
                val size = Size(480, 270)
                val original = row.video.privateFile
                if (original != null) ThumbnailUtils.createVideoThumbnail(original, size, signal)
                else contentResolver.loadThumbnail(row.video.uri, size, signal)
            } catch (_: Exception) { null }
            finally { if (thumbnailCancellation === signal) thumbnailCancellation = null }
            runOnUiThread {
                if (isDestroyed || generation != token || selected != null) {
                    bitmap?.recycle()
                    return@runOnUiThread
                }
                pendingThumbnails.remove(row.key)
                if (bitmap != null) thumbnailCache.put(row.key, bitmap)
                else failedThumbnails += row.key
                // Re-check visibility: a finished decode never installs into a row already scrolled away.
                refreshVisibleThumbnails()
            }
        }
    }

    private fun showPlayer(video: StoredVideo, restorePosition: Boolean = false) {
        requestedUri = null; requestedName = null; restoring = false
        externalActions.invalidate()
        val token = ++generation
        thumbnailCancellation?.cancel()
        pendingThumbnails.clear(); thumbnailRows.clear(); visibleThumbnails.clear(); libraryScroll = null; loadingLibrary = false; reloadRequested = false
        player?.let { playbackMuted = it.muted; playbackCompatible = it.compatibilityMode; it.release() }
        selected = video
        if (!restorePosition) position = 0L
        resumePlayback = LibraryActionPolicy.initialPlayback(video.validated, restorePosition, resumePlayback)
        val compact = layoutPolicy().compactControls
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(ink)
            setPadding(dp(if (compact) 10 else 18), dp(if (compact) 4 else 10), dp(if (compact) 10 else 18), dp(if (compact) 4 else 12))
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(icon(CameraIcon.BACK, "Voltar aos vídeos") { showLibrary(); loadLibrary() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(4), 0)
            if (!compact) addView(label("LUMA  /  REPRODUÇÃO", 10f, accent).apply { letterSpacing = .12f })
            addView(label("Rever a cena", if (compact) 20f else 25f).apply {
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(icon(CameraIcon.SHARE, "Compartilhar vídeo") { share(video) }, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(buttonMenu(video), LinearLayout.LayoutParams(dp(48), dp(48)))
        page.addView(header)
        val videoView = VideoPlayerView(this).apply {
            muted = playbackMuted
            if (playbackCompatible) useCompatibilityMode(true)
        }
        player = videoView
        page.addView(videoView, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            topMargin = dp(if (compact) 4 else 12); bottomMargin = dp(if (compact) 0 else 12)
        })
        val status = label("Abrindo vídeo…", 12f, subtle).apply {
            visibility = View.GONE // The player has its own visible status, including audio interruptions.
        }
        if (!compact) {
            val info = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), 0, dp(4), 0) }
            info.addView(label(DateFormat.format("dd MMM yyyy • HH:mm", Date(video.createdAt)).toString(), 17f), LinearLayout.LayoutParams(0, -2, 1f))
            info.addView(pill(formatSize(video.sizeBytes), cardColor, subtle))
            page.addView(info)
            page.addView(label(video.name, 11f, subtle).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE; setPadding(dp(4), dp(4), dp(4), 0)
            })
        }
        page.addView(status)
        if (!video.validated) page.addView(label("Original não validado · a reprodução e a recuperação não são garantidas. O arquivo permanece disponível para compartilhar.",
            12f, subtle).apply { setPadding(dp(4), dp(6), dp(4), dp(6)) })
        // Landscape reserves its height for the video and persistent playback controls.
        // Sharing/recovery/library actions remain in the toolbar and menu instead of a second footer.
        if (!compact && video.privateFile != null) page.addView(action("Salvar na galeria", "Publicar original preservado na galeria", false) { recover(video) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        setContentView(page)
        CameraWindowInsets.bind(window, page)
        videoView.onStatus = { message ->
            if (!isDestroyed && generation == token && player === videoView) status.text = message
        }
        videoView.onOpenExternal = {
            if (!isDestroyed && generation == token && player === videoView) openExternal(video)
        }
        videoView.bind(video.uri, PlaybackCheckpoint(position, resumePlayback))
        if (started) videoView.startHost()
        if (resumed) videoView.resumeHost()
    }

    private fun buttonMenu(video: StoredVideo) = Button(this).apply {
        text = "⋮"; textSize = 26f; isAllCaps = false; minimumWidth = 0
        setPadding(0, 0, 0, 0); setTextColor(CameraPalette.text)
        background = RippleDrawable(ColorStateList.valueOf(0x26ffffff), rounded(cardColor, 24f), rounded(Color.WHITE, 24f))
        contentDescription = "Mais opções do vídeo"
        setOnClickListener {
            val labels = mutableListOf("Informações do vídeo", "${if (player?.compatibilityMode == true) "Desativar modo compatível" else "Modo compatível · pode ser mais lento"}", "Salvar diagnóstico de reprodução")
            if (video.privateFile != null) labels += "Salvar na galeria"
            labels += "Abrir em outro player"
            if (recordingReceipts.containsKey(video.name)) labels += "Exportar ficha da tomada"
            AlertDialog.Builder(this@LibraryActivity).setTitle("Vídeo")
                .setItems(labels.toTypedArray()) { _, choice -> when {
                    choice == 0 -> AlertDialog.Builder(this@LibraryActivity).setTitle("Informações")
                        .setMessage("${video.name}\n\n${formatDuration(player?.durationMs() ?: video.durationMs)} · ${formatSize(video.sizeBytes)}\n${if (!video.validated) "Original não validado, preservado no app" else if (video.privateFile == null) "Salvo na galeria" else "Original preservado no app"}")
                        .setPositiveButton("Fechar", null).show()
                    choice == 1 -> {
                        val enabled = player?.compatibilityMode != true
                        player?.useCompatibilityMode(enabled)
                    }
                    choice == 2 -> exportPlaybackDiagnostic()
                    video.privateFile != null && choice == 3 -> recover(video)
                    labels[choice] == "Exportar ficha da tomada" -> exportRecordingReceipt(video)
                    else -> openExternal(video)
                } }.show()
        }
    }

    private fun exportPlaybackDiagnostic() {
        externalActions.invalidate()
        val activePlayer = player ?: return
        diagnosticExport = activePlayer.diagnostics()
        val save = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "Luma-reproducao-${DateFormat.format("yyyyMMdd-HHmm", Date())}.json")
        }
        try { startActivityForResult(save, REQUEST_DIAGNOSTIC) }
        catch (_: Exception) { diagnosticExport = null; Toast.makeText(this, "Nenhum app disponível para escolher o destino.", Toast.LENGTH_LONG).show() }
    }
    private fun exportRecordingReceipt(video: StoredVideo) {
        val receipt = recordingReceipts[video.name] ?: return
        externalActions.invalidate()
        diagnosticExport = receipt.toString(2)
        try { startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "${video.name.substringBeforeLast('.')}-ficha.json")
        }, REQUEST_DIAGNOSTIC) }
        catch (_: Exception) { diagnosticExport = null; Toast.makeText(this, "Nenhum seletor de destino disponível.", Toast.LENGTH_LONG).show() }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_DIAGNOSTIC) return
        val body = diagnosticExport; diagnosticExport = null
        val destination = data?.data
        if (resultCode != RESULT_OK || body == null || destination == null) return
        background.execute {
            val result = runCatching {
                contentResolver.openOutputStream(destination, "wt")?.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    ?: error("Destino indisponível")
            }
            runOnUiThread {
                if (!isDestroyed && !isFinishing) Toast.makeText(this,
                    if (result.isSuccess) "Arquivo JSON salvo." else "Não foi possível salvar o arquivo JSON.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openExternal(video: StoredVideo) {
        if (!canShareSource(video)) return
        externalActions.invalidate()
        val open = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(video.uri, "video/mp4")
            clipData = ClipData.newUri(contentResolver, video.name, video.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { startActivity(Intent.createChooser(open, "Abrir vídeo")) }
        catch (_: Exception) { Toast.makeText(this, "Nenhum outro player disponível. Use Tentar novamente ou volte à biblioteca.", Toast.LENGTH_LONG).show() }
    }
    private fun share(video: StoredVideo) {
        if (!canShareSource(video)) return
        val ticket = externalActions.begin() ?: return
        val token = generation
        background.execute {
            if (isDestroyed || generation != token) return@execute
            val readable = store.isReadable(video.uri)
            runOnUiThread {
                if (isDestroyed || isFinishing || generation != token || !externalActions.consume(ticket)) return@runOnUiThread
                if (!readable) {
                    Toast.makeText(this, "O vídeo não está mais disponível. Atualize a biblioteca.", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "video/mp4"
                    putExtra(Intent.EXTRA_STREAM, video.uri)
                    clipData = ClipData.newUri(contentResolver, video.name, video.uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try { startActivity(Intent.createChooser(send, "Compartilhar vídeo").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                catch (_: Exception) { Toast.makeText(this, "Nenhum aplicativo disponível para compartilhar vídeos.", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun recover(video: StoredVideo) {
        if (saving) return
        // Publishing replaces the private URI with a gallery URI; cancel a share still reading it.
        externalActions.invalidate()
        saving = true
        val token = generation
        if (selected == null) subtitle.text = "Salvando vídeo na galeria…"
        Toast.makeText(this, "Salvando vídeo na galeria…", Toast.LENGTH_SHORT).show()
        background.execute {
            val result = runCatching { store.recover(video) }
            runOnUiThread {
                saving = false
                if (!isDestroyed && !isFinishing) {
                    result.fold(onSuccess = {
                        Toast.makeText(this, "Vídeo salvo em Movies / LumaCamera.", Toast.LENGTH_LONG).show()
                        if (generation == token) {
                            val checkpoint = player?.snapshot()
                            if (checkpoint != null) {
                                position = checkpoint.positionMs
                                resumePlayback = checkpoint.playWhenReady
                            }
                            restoring = selected?.uri == video.uri
                            requestedUri = if (restoring) it else null
                            requestedName = if (restoring) video.name else null
                            showLibrary(); loadLibrary()
                        } else if (selected == null) loadLibrary()
                    }, onFailure = {
                        Toast.makeText(this, if (video.validated)
                            "Não foi possível salvar. O original continua no app; verifique espaço e tente novamente."
                        else "Não foi possível validar e salvar este original. Ele continua no app para inspeção e compartilhamento.", Toast.LENGTH_LONG).show()
                        if (selected == null) loadLibrary()
                    })
                }
            }
        }
    }

    private fun canShareSource(video: StoredVideo): Boolean {
        if (!saving || video.privateFile == null) return true
        Toast.makeText(this, "Aguarde o original terminar de salvar na galeria antes de compartilhá-lo.", Toast.LENGTH_LONG).show()
        return false
    }

    private fun action(text: String, description: String, filled: Boolean, click: () -> Unit) = Button(this).apply {
        this.text = text; textSize = 13f; isAllCaps = false; contentDescription = description
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        minimumWidth = 0; minimumHeight = dp(48); setPadding(dp(10), dp(10), dp(10), dp(10))
        maxLines = 3
        setTextColor(if (filled) ink else CameraPalette.text)
        val base = rounded(if (filled) accent else CameraPalette.elevated, 15f)
        background = RippleDrawable(ColorStateList.valueOf(if (filled) 0x22080c10 else 0x26ffffff), base, rounded(Color.WHITE, 15f))
        setOnClickListener { click() }
    }
    private fun label(text: String, size: Float, color: Int = CameraPalette.text) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color)
    }
    private fun pill(text: String, fill: Int, color: Int) = label(text, 10f, color).apply {
        background = rounded(fill, 8f); setPadding(dp(9), dp(5), dp(9), dp(5))
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END; contentDescription = text
    }
    private fun icon(icon: CameraIcon, description: String, click: () -> Unit) = CameraIconButton(this, icon, description).apply {
        setOnClickListener { click() }
    }
    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
    private fun dp(value: Float) = (value * resources.displayMetrics.density + .5f).toInt()
    private fun layoutPolicy(): ResponsiveUiPolicy.Layout {
        val size = CameraWindowInsets.availableSize(window)
        val density = resources.displayMetrics.density
        return ResponsiveUiPolicy.layout(size.width / density, size.height / density, resources.configuration.fontScale)
    }
    private fun formatDuration(ms: Long?): String = MediaTime.format(ms)
    private fun formatSize(bytes: Long) = String.format(Locale.getDefault(), "%.1f MB", bytes / 1_048_576.0)

    companion object {
        private const val REQUEST_DIAGNOSTIC = 71
        fun open(context: Context, preferredUri: Uri? = null) {
            context.startActivity(Intent(context, LibraryActivity::class.java).apply {
                data = preferredUri
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }
}

