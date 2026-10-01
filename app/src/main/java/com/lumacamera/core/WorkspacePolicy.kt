package com.lumacamera.core

import java.text.Normalizer

/** Limits user labels and lookup cost. Names are labels, never filesystem paths. */
object WorkspacePolicy {
    const val MAX_PRESETS = 24

    /** Replacing a named preset is allowed at capacity; a new one never evicts saved work. */
    fun canSavePreset(existingNames: List<String>, name: String): Boolean =
        existingNames.size < MAX_PRESETS || label(name, "Meu preset") in existingNames

    fun label(value: String, fallback: String = ""): String = value.filter { !it.isISOControl() }.trim().take(56).ifEmpty { fallback }
    fun searchKey(value: String): String = Normalizer.normalize(value.lowercase(java.util.Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
    fun matches(query: String, title: String, keywords: String): Boolean = searchKey(query).trim().split(Regex("\\s+"))
        .filter { it.isNotEmpty() }.all { searchKey("$title $keywords").contains(it) }
    fun nextTake(value: Int): Int = if (value in 1 until 999999) value + 1 else 999999
    fun splitMb(value: Int): Int = value.coerceIn(64, 3072)
}
