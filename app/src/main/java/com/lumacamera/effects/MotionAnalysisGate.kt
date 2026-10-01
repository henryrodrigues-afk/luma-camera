package com.lumacamera.effects

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** One analysis owns the scratch pixels at a time. Invalidating never releases another job's buffers. */
class MotionAnalysisGate {
    data class Ticket(val generation: Long, val capturedAtMs: Long)
    private val generation = AtomicLong()
    private val inFlight = AtomicReference<Ticket?>()
    @Volatile private var closed = false

    fun tryBegin(capturedAtMs: Long): Ticket? {
        if (closed || capturedAtMs < 0L) return null
        val ticket = Ticket(generation.get(), capturedAtMs)
        if (!inFlight.compareAndSet(null, ticket)) return null
        if (!isCurrent(ticket)) { complete(ticket); return null }
        return ticket
    }
    fun isCurrent(ticket: Ticket): Boolean = !closed && ticket.generation == generation.get()
    fun shouldAccept(ticket: Ticket, nowMs: Long): Boolean =
        isCurrent(ticket) && nowMs >= ticket.capturedAtMs && nowMs - ticket.capturedAtMs <= MAX_RESULT_AGE_MS
    fun complete(ticket: Ticket) { inFlight.compareAndSet(ticket, null) }
    fun invalidate() { generation.incrementAndGet() }
    fun close() { closed = true; invalidate() }

    companion object { const val MAX_RESULT_AGE_MS = 300L }
}
