package com.lumacamera.core

/** Main-thread ticket for an external chooser; background results cannot revive a paused screen. */
class ExternalActionPolicy {
    private var active = false
    private var revision = 0L
    private var pending: Long? = null

    fun resume() { active = true; invalidate() }
    fun pause() { active = false; invalidate() }
    fun invalidate() { revision++; pending = null }

    fun begin(): Long? {
        if (!active || pending != null) return null
        revision++
        pending = revision
        return revision
    }

    /** Consuming an old result must never discard a newer request. */
    fun consume(ticket: Long): Boolean {
        if (!active || pending != ticket) return false
        pending = null
        return true
    }
}

object LibraryActionPolicy {
    data class ClipIdentity(val uri: String, val name: String)

    /** Publishing changes the URI, not the recording's name. Never guess between duplicate names. */
    fun restoredIndex(preferredUri: String?, stableName: String?, candidates: List<ClipIdentity>): Int? {
        if (preferredUri != null) candidates.indexOfFirst { it.uri == preferredUri }.takeIf { it >= 0 }?.let { return it }
        if (stableName.isNullOrBlank()) return null
        var found: Int? = null
        for (index in candidates.indices) if (candidates[index].name == stableName) {
            if (found != null) return null
            found = index
        }
        return found
    }

    /** An unvalidated original is accessible for inspection/sharing, but never starts by itself. */
    fun initialPlayback(validated: Boolean, restoring: Boolean, savedIntent: Boolean): Boolean =
        validated && (!restoring || savedIntent)
}
