package com.lumacamera.camera

import android.content.ContentValues
import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.LruCache
import com.lumacamera.core.RecordingFiles
import com.lumacamera.core.RecordingValidation
import com.lumacamera.core.StoragePolicy
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import com.lumacamera.core.RecordingOptions
import com.lumacamera.core.RecordingCopy
import com.lumacamera.core.RecordingProbePolicy
import org.json.JSONObject

data class StoredVideo(val uri: Uri, val name: String, val createdAt: Long, val sizeBytes: Long,
                       val durationMs: Long, val privateFile: File? = null, val validated: Boolean = true)
data class VideoLibrary(val videos: List<StoredVideo>, val warning: String? = null)

/** Publishes only completed recordings; failed copies cannot leave a visible partial MP4. */
class VideoStore(private val context: Context) {
    private val relativePath = "${Environment.DIRECTORY_MOVIES}/LumaCamera/"
    private data class Metadata(val width: Int, val height: Int, val durationMs: Long)
    private data class PrivateProbe(val metadata: Metadata?, val validated: Boolean, val checkedAtMs: Long,
        val nativeComplete: Boolean)
    private data class CachedDuration(val durationMs: Long, val checkedAtMs: Long)

    /** Private sidecars are keyed by the immutable clip name and survive publishing the MP4. */
    fun writeRecordingMetadata(file: File, options: RecordingOptions, sessionId: String, part: Int,
        actualAudio: String, expectedDurationMs: Long? = null) {
        require(RecordingFiles.isRecordingName(file.name))
        val directory = File(context.filesDir, "recording-metadata")
        check(directory.isDirectory || directory.mkdirs()) { "Não foi possível guardar a ficha da tomada." }
        val value = JSONObject().apply {
            put("schemaVersion", 1); put("clipName", file.name); put("sessionId", sessionId)
            put("projectName", options.projectName); put("sceneName", options.sceneName)
            put("takeNumber", options.takeNumber); put("part", part)
            put("splitEnabled", options.splitEnabled); put("splitSizeMb", options.splitSizeMb)
            put("audioDeviceId", options.audioDeviceId ?: JSONObject.NULL)
            put("actualAudio", actualAudio)
            put("elapsedEstimateMs", expectedDurationMs ?: JSONObject.NULL)
            // This is a capture receipt, not the container's authoritative playback duration.
            put("captureMetadata", options.captureMetadata ?: JSONObject.NULL)
        }.toString(2)
        val destination = File(directory, "${file.name}.json")
        val temporary = File.createTempFile("receipt_", ".tmp", directory)
        try {
            temporary.writeText(value, Charsets.UTF_8)
            // Private files share a filesystem: Android rename replaces a receipt atomically.
            // Failure leaves the previous complete receipt intact, never a truncated fallback write.
            check(temporary.renameTo(destination)) { "Não foi possível confirmar a ficha da tomada." }
        } finally { temporary.delete() }
    }

    fun recordingMetadata(clipName: String): String? {
        if (!RecordingFiles.isRecordingName(clipName)) return null
        val directory = File(context.filesDir, "recording-metadata").canonicalFile
        val file = File(directory, "$clipName.json").canonicalFile
        if (file.parentFile != directory || !file.isFile || file.length() > 262_144L) return null
        return runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
    }

    /** A recovered original must obey the same known capture timeline as its first publication. */
    private fun recordedDurationEstimate(clipName: String): Long? = runCatching {
        recordingMetadata(clipName)?.let { JSONObject(it).optLong("elapsedEstimateMs", 0L).takeIf { ms -> ms > 0L } }
    }.getOrNull()

    fun publish(file: File, expectedDurationMs: Long? = null): Uri {
        val key = file.canonicalPath
        check(!activeRecordings.contains(key)) { "A gravação ainda está em andamento." }
        check(publishing.add(key)) { "Este original já está sendo publicado. Aguarde e atualize a biblioteca." }
        // Manual recovery reserves its copy too; engine-owned queued parts keep their existing owner.
        val ownsReservation = queuedPublications.putIfAbsent(key, file.length().coerceAtLeast(0L)) == null
        try { return publishInternal(file, expectedDurationMs) }
        finally {
            publishing.remove(key)
            if (ownsReservation) queuedPublications.remove(key)
        }
    }

    @Suppress("DEPRECATION")
    private fun publishInternal(file: File, expectedDurationMs: Long?): Uri {
        val expectedBytes = file.length()
        val publicationKey = file.canonicalPath
        val metadata = fileMetadata(file) ?: error("O MP4 ainda não tem imagem e metadados válidos. Original preservado.")
        val videoSample = firstVideoSample(file)
        check(RecordingValidation.durationIsPlausible(metadata.durationMs, videoSample, expectedDurationMs)) {
            "A linha do tempo do MP4 é inválida ou incompatível com o tempo gravado. Original preservado para diagnóstico."
        }
        android.util.Log.i("LumaRecording", "Finalized bytes=${file.length()} durationMs=${metadata.durationMs} " +
            "firstVideoUs=$videoSample expectedMs=$expectedDurationMs ${metadata.width}x${metadata.height}")
        val destinationFree = android.os.StatFs(Environment.getExternalStorageDirectory().path).availableBytes
        check(StoragePolicy.canPublish(destinationFree, expectedBytes)) {
            "Falta espaço para copiar o vídeo completo à galeria. Original preservado."
        }
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Não foi possível criar o vídeo na galeria.")
        var committed = false
        try {
            val copiedBytes = resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { input ->
                RecordingCopy.copy(input, out, expectedBytes) { copied ->
                    queuedPublications.computeIfPresent(publicationKey) { _, _ ->
                        StoragePolicy.publicationBytesRemaining(expectedBytes, copied)
                    }
                }
            } }
                ?: error("Não foi possível abrir o destino do vídeo.")
            check(copiedBytes == expectedBytes && file.length() == expectedBytes) {
                "A cópia não corresponde ao original completo. Original preservado."
            }
            // DURATION/SIZE/WIDTH/HEIGHT are read-only indexed columns. Publishing the pending
            // row requests the provider's scan; keep our verified duration while indexing completes.
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null) > 0) {
                "A galeria não confirmou a publicação."
            }
            committed = true
            // The gallery commit is final. Auxiliary bookkeeping can never remove that valid video.
            runCatching {
                context.getSharedPreferences("video-library", Context.MODE_PRIVATE).edit()
                    .putString("latest", uri.toString()).apply()
                durationCache.put("$uri|$expectedBytes", CachedDuration(metadata.durationMs, android.os.SystemClock.elapsedRealtime()))
            }.onFailure { android.util.Log.w("LumaRecording", "Published clip bookkeeping failed", it) }
            if (!file.delete()) android.util.Log.w("LumaRecording", "Published clip retains private original: ${file.name}")
            return uri
        } catch (error: Exception) {
            // Preserve the original failure if cleaning the pending MediaStore row also fails.
            if (!committed) try { resolver.delete(uri, null, null) } catch (_: Exception) { }
            throw error
        }
    }

    /** Only app-owned records in our album are queried; no access to the user's entire library. */
    fun list(): VideoLibrary {
        val videos = mutableListOf<StoredVideo>()
        var warning: String? = null
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val columns = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DURATION)
        try {
            context.contentResolver.query(collection, columns,
                "(${MediaStore.Video.Media.RELATIVE_PATH} = ? OR ${MediaStore.Video.Media.RELATIVE_PATH} = ?) " +
                    "AND ${MediaStore.Video.Media.OWNER_PACKAGE_NAME} = ? AND ${MediaStore.Video.Media.IS_PENDING} = 0",
                arrayOf(relativePath, relativePath.trimEnd('/'), context.packageName),
                "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(1) ?: continue
                    if (!RecordingFiles.isRecordingName(name)) continue
                    val uri = ContentUris.withAppendedId(collection, cursor.getLong(0))
                    val createdAt = cursor.getLong(2) * 1000
                    val bytes = cursor.getLong(3)
                    // MediaStore's asynchronous scanner can initially return NULL/zero even for a
                    // finalized MP4. Read actual container metadata on this background library worker.
                    val duration = cursor.getLong(4).takeIf { it > 0L }
                        ?: cachedDuration(uri, bytes)
                    videos += StoredVideo(uri, name, createdAt, bytes, duration)
                }
            } ?: run { warning = "O Android não respondeu à consulta da galeria. Tente atualizar." }
        } catch (_: Exception) {
            warning = "Não foi possível consultar os vídeos da galeria. Os originais locais continuam disponíveis."
        }
        val publishedNames = videos.map { it.name }.toSet()
        File(context.filesDir, "recordings").listFiles()?.forEach { file ->
            if (file.isFile && RecordingFiles.isRecordingName(file.name) && file.name !in publishedNames &&
                !activeRecordings.contains(file.canonicalPath) && !publishing.contains(file.canonicalPath)) {
                privateVideo(file)?.let(videos::add)
            }
        }
        return VideoLibrary(videos.sortedByDescending { it.createdAt }, warning)
    }

    fun recover(video: StoredVideo): Uri {
        val file = video.privateFile ?: error("Esse vídeo já está na galeria.")
        val allowed = RecordingFiles.resolve(File(context.filesDir, "recordings"), file.name)
        require(allowed == file.canonicalFile && privateVideo(allowed) != null) { "A gravação não está disponível." }
        return publish(allowed, recordedDurationEstimate(allowed.name))
    }

    fun isReadable(uri: Uri): Boolean = try {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
    } catch (_: Exception) { false }

    /** Even after a native stop error, never discard a finalized file that contains a video sample. */
    fun hasVideoSamples(file: File): Boolean = file.isFile && file.length() > 0L && firstVideoSample(file) >= 0L

    private fun privateVideo(file: File): StoredVideo? {
        if (!file.isFile || file.length() <= 0L || activeRecordings.contains(file.canonicalPath) ||
            queuedPublications.containsKey(file.canonicalPath)) return null
        val expected = recordedDurationEstimate(file.name)
        val key = "${file.canonicalPath}|${file.length()}|${file.lastModified()}|$expected"
        val now = android.os.SystemClock.elapsedRealtime()
        val cached = privateProbeCache.get(key)?.takeUnless {
            RecordingProbePolicy.shouldRetry(it.nativeComplete, it.checkedAtMs, now)
        }
        val probe = cached ?: run {
            val metadata = fileMetadata(file)
            val firstSample = if (metadata != null) firstVideoSample(file) else -1L
            val nativeComplete = metadata != null && metadata.durationMs > 0L && firstSample >= 0L
            // A known bad timeline stays rejected. Retry only an incomplete native probe.
            PrivateProbe(metadata, metadata != null &&
                RecordingValidation.durationIsPlausible(metadata.durationMs, firstSample, expected), now, nativeComplete)
                .also { privateProbeCache.put(key, it) }
        }
        return try {
            StoredVideo(RecordingFileProvider.uriFor(context, file), file.name, file.lastModified(), file.length(),
                if (probe.validated) probe.metadata!!.durationMs else 0L, file, probe.validated)
        } catch (_: Exception) { null }
    }

    private fun fileMetadata(file: File): Metadata? {
        if (!file.isFile || file.length() == 0L) return null
        val retriever = runCatching { MediaMetadataRetriever() }.getOrNull() ?: return null
        return try {
            retriever.setDataSource(file.absolutePath)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            if (width <= 0 || height <= 0) return null
            Metadata(width, height, retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L)
        } catch (_: Exception) { null }
        finally { try { retriever.release() } catch (_: Exception) { } }
    }

    private fun firstVideoSample(file: File): Long {
        val extractor = runCatching { MediaExtractor() }.getOrNull() ?: return -1L
        return try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: return -1L
            extractor.selectTrack(track)
            if (extractor.sampleSize > 0L) extractor.sampleTime else -1L
        } catch (_: Exception) { -1L }
        finally { runCatching { extractor.release() } }
    }

    private fun cachedDuration(uri: Uri, bytes: Long): Long {
        val key = "$uri|$bytes"
        val now = android.os.SystemClock.elapsedRealtime()
        durationCache.get(key)?.let {
            if (!RecordingProbePolicy.shouldRetry(it.durationMs > 0L, it.checkedAtMs, now)) return it.durationMs
        }
        val retriever = runCatching { MediaMetadataRetriever() }.getOrNull() ?: return 0L
        val duration = try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        } catch (_: Exception) { 0L }
        finally { runCatching { retriever.release() } }
        // Valid immutable clips stay cached. Failed native probes get a bounded retry on later
        // refreshes, so temporary decoder/storage pressure cannot poison this clip permanently.
        durationCache.put(key, CachedDuration(duration, now))
        return duration
    }

    companion object {
        private val activeRecordings = ConcurrentHashMap.newKeySet<String>()
        private val publishing = ConcurrentHashMap.newKeySet<String>()
        private val queuedPublications = ConcurrentHashMap<String, Long>()
        fun beginRecording(file: File) { activeRecordings.add(file.canonicalPath) }
        fun finishRecording(file: File) { activeRecordings.remove(file.canonicalPath) }
        /** Queue ownership survives Activity/engine recreation and reserves the still-needed gallery copy. */
        fun queuePublication(file: File) {
            queuedPublications[file.canonicalPath] = file.length().coerceAtLeast(0L)
            finishRecording(file)
        }
        fun finishPublication(file: File) { queuedPublications.remove(file.canonicalPath) }
        fun pendingPublicationBytes(): Long = queuedPublications.values.fold(0L) { total, bytes ->
            StoragePolicy.saturatedSum(total, bytes)
        }
        // Shared across the short-lived publisher and Activity-owned library, bounded and thread-safe.
        private val durationCache = LruCache<String, CachedDuration>(64)
        private val privateProbeCache = LruCache<String, PrivateProbe>(64)
    }
}
