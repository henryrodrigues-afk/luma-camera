package com.lumacamera.core

data class RecordingStorageSnapshot(val availableBytes: Long, val recordedBytes: Long,
    val fileLimitBytes: Long, val videoBitrate: Int?, val hasAudio: Boolean, val recording: Boolean,
    val pendingPublicationBytes: Long = 0L)

/** A recording temporarily occupies its private original and a complete MediaStore copy. */
object StoragePolicy {
    const val MIB = 1024L * 1024L
    const val MIN_START_BYTES = 160L * MIB
    const val FINALIZATION_RESERVE_BYTES = 96L * MIB
    const val MAX_FILE_BYTES = 4_000_000_000L

    fun canStart(availableBytes: Long): Boolean = availableBytes > MIN_START_BYTES

    fun maxRecordingFileBytes(availableBytes: Long): Long =
        ((availableBytes.coerceAtLeast(0L) - FINALIZATION_RESERVE_BYTES).coerceAtLeast(0L) / 2L)
            .coerceAtMost(MAX_FILE_BYTES)

    /** Further bytes each consume free space and enlarge the copy still needed at publication. */
    fun remainingRecordingBytes(availableBytes: Long, recordedBytes: Long,
        fileLimitBytes: Long = MAX_FILE_BYTES, pendingPublicationBytes: Long = 0L): Long {
        val recorded = recordedBytes.coerceAtLeast(0L)
        val reservedCopies = saturatedSum(recorded, pendingPublicationBytes)
        val freeAfterReserve = (availableBytes.coerceAtLeast(0L) - FINALIZATION_RESERVE_BYTES).coerceAtLeast(0L)
        val copyBudget = if (freeAfterReserve > reservedCopies) (freeAfterReserve - reservedCopies) / 2L else 0L
        // An earlier clip's gallery copy consumes disk, never this clip's file-size allowance.
        val fileBudget = if (fileLimitBytes > recorded) fileLimitBytes - recorded else 0L
        return minOf(copyBudget, fileBudget)
    }

    fun saturatedSum(first: Long, second: Long): Long {
        val a = first.coerceAtLeast(0L)
        val b = second.coerceAtLeast(0L)
        return if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b
    }

    fun publicationBytesRemaining(totalBytes: Long, copiedBytes: Long): Long =
        (totalBytes.coerceAtLeast(0L) - copiedBytes.coerceIn(0L, totalBytes.coerceAtLeast(0L))).coerceAtLeast(0L)

    /** A gallery copy must not consume the reserve needed to finalize a simultaneous recording. */
    fun canPublish(availableBytes: Long, fileBytes: Long): Boolean =
        fileBytes > 0L && availableBytes > fileBytes &&
            availableBytes - fileBytes > FINALIZATION_RESERVE_BYTES

    /** VBR, muxing overhead and driver behavior make this an estimate, never an allowed duration. */
    fun estimatedRemainingMs(remainingBytes: Long, videoBitrate: Int, audio: Boolean): Long? {
        if (videoBitrate <= 0) return null
        val bitrate = videoBitrate.toLong() + if (audio) 128_000L else 0L
        return (remainingBytes.coerceAtLeast(0L).toDouble() * 8_000.0 / bitrate)
            .coerceAtMost(Long.MAX_VALUE.toDouble()).toLong()
    }

    /** A parser/native finalization error does not prove that a nonempty original is worthless. */
    fun canDiscardIncomplete(fileBytes: Long): Boolean = fileBytes <= 0L
}
