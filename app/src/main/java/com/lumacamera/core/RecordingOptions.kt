package com.lumacamera.core

import java.text.Normalizer

/** Snapshotted before REC: changing a menu never silently changes the current take. */
data class RecordingOptions(
    val audioDeviceId: Int? = null,
    val splitEnabled: Boolean = false,
    val splitSizeMb: Int = 1024,
    val projectName: String = "",
    val sceneName: String = "",
    val takeNumber: Int = 1,
    val captureMetadata: String? = null
) {
    fun sanitized() = copy(
        audioDeviceId = audioDeviceId?.takeIf { it > 0 },
        splitSizeMb = splitSizeMb.coerceIn(64, 3072),
        projectName = RecordingSegmentPolicy.cleanLabel(projectName),
        sceneName = RecordingSegmentPolicy.cleanLabel(sceneName),
        takeNumber = takeNumber.coerceIn(1, 999_999),
        captureMetadata = captureMetadata?.take(32_768)
    )
}

data class AudioInputOption(val id: Int, val label: String, val type: Int, val external: Boolean)
data class AudioRouteStatus(val preferredDeviceId: Int? = null, val actualDeviceId: Int? = null,
    val label: String = "Automático", val confirmed: Boolean = false,
    val message: String = "A entrada real será confirmada durante REC.")
data class RecordingSegmentState(val index: Int = 0, val splitEnabled: Boolean = false,
    val queuedNext: Boolean = false, val completedParts: Int = 0)

object AudioRoutePolicy {
    fun status(preferredId: Int?, actualId: Int?, actualLabel: String?, recording: Boolean,
        hasAudio: Boolean): AudioRouteStatus {
        if (!recording) return AudioRouteStatus(preferredId)
        if (!hasAudio) return AudioRouteStatus(preferredId, label = "Sem áudio",
            message = "Esta tomada está sendo gravada sem áudio.")
        val label = actualLabel?.let(RecordingSegmentPolicy::cleanLabel)?.takeIf { it.isNotBlank() }
        if (actualId == null || actualId <= 0 || label == null)
            return AudioRouteStatus(preferredId, label = "Aguardando rota",
                message = "O Android ainda não confirmou a entrada de áudio.")
        return AudioRouteStatus(preferredId, actualId, label, true,
            if (preferredId != null && actualId != preferredId) "Entrada solicitada não está em uso: REC usa $label."
            else "REC usa $label · rota confirmada pelo Android.")
    }
}

object RecordingSegmentPolicy {
    /** Labels stay out of filesystem paths, but are still bounded for metadata and UI. */
    fun cleanLabel(value: String): String = value.filter { !it.isISOControl() }.trim().take(80)

    /** Metadata keeps full labels; filenames get a small ASCII identifier with no path syntax. */
    fun filenameIdentity(project: String, scene: String, take: Int): String {
        fun slug(value: String): String = Normalizer.normalize(cleanLabel(value), Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').take(24).trimEnd('-')
        return listOf(slug(project), slug(scene), "T" + take.coerceIn(1, 999_999).toString().padStart(6, '0'))
            .filter { it.isNotBlank() }.joinToString("_")
    }

    fun segmentLimitBytes(options: RecordingOptions, availableBytes: Long): Long {
        val safeBudget = StoragePolicy.maxRecordingFileBytes(availableBytes)
        return if (options.splitEnabled) minOf(options.sanitized().splitSizeMb * StoragePolicy.MIB, safeBudget)
            else safeBudget
    }

    /** Account for all retained originals: even with splitting, publication requires a full copy. */
    fun remainingSessionBytes(availableBytes: Long, retainedBytes: Long,
        pendingPublicationBytes: Long = 0L): Long =
        StoragePolicy.remainingRecordingBytes(availableBytes, retainedBytes, Long.MAX_VALUE, pendingPublicationBytes)

    fun canQueueNext(availableBytes: Long, retainedBytes: Long, segmentLimit: Long,
        pendingPublicationBytes: Long = 0L): Boolean =
        segmentLimit >= 16L * StoragePolicy.MIB &&
            remainingSessionBytes(availableBytes, retainedBytes, pendingPublicationBytes) >= segmentLimit

    fun partSuffix(index: Int): String = "_P" + index.coerceAtLeast(1).toString().padStart(3, '0')

    /** A queued file may already be native-active even if its NEXT_OUTPUT callback follows STOP. */
    fun finalPartDurationEstimate(elapsedMs: Long, unconfirmedNextFile: Boolean): Long? =
        elapsedMs.takeIf { it > 0L && !unconfirmedNextFile }
}
