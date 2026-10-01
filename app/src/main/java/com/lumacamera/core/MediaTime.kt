package com.lumacamera.core

import java.util.Locale

/** Unknown container metadata must not masquerade as a valid zero-length recording. */
object MediaTime {
    fun format(durationMs: Long?, allowZero: Boolean = false): String {
        if (durationMs == null || durationMs < 0L || (durationMs == 0L && !allowZero)) return "—:—"
        val seconds = durationMs / 1000L
        return if (seconds >= 3600L) String.format(Locale.ROOT, "%d:%02d:%02d",
            seconds / 3600L, seconds / 60L % 60L, seconds % 60L)
        else String.format(Locale.ROOT, "%d:%02d", seconds / 60L, seconds % 60L)
    }
}
