package com.lumacamera.effects

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class MotionAnalysisGateTest {
    @Test fun oneJobOwnsScratchPixelsAndFramesAreDroppedWhileBusy() {
        val gate = MotionAnalysisGate()
        val first = gate.tryBegin(1_000)!!
        assertNull(gate.tryBegin(1_100))
        gate.complete(first)
        assertNotNull(gate.tryBegin(1_200))
    }
    @Test fun settingsChangeRejectsOldResultWithoutReleasingItsBuffersEarly() {
        val gate = MotionAnalysisGate()
        val first = gate.tryBegin(1_000)!!
        gate.invalidate()
        assertFalse(gate.shouldAccept(first, 1_050))
        assertNull(gate.tryBegin(1_060))
        gate.complete(first)
        val next = gate.tryBegin(1_100)!!
        assertTrue(gate.shouldAccept(next, 1_150))
    }
    @Test fun duplicateOldCompletionCannotUnlockNewJob() {
        val gate = MotionAnalysisGate()
        val first = gate.tryBegin(1_000)!!
        gate.complete(first)
        val next = gate.tryBegin(1_000)!!
        gate.complete(first)
        assertNull(gate.tryBegin(1_001))
        gate.complete(next)
        assertNotNull(gate.tryBegin(1_002))
    }
    @Test fun delayedFutureAndInvalidTimestampsCannotApplyCorrection() {
        val gate = MotionAnalysisGate()
        assertNull(gate.tryBegin(-1))
        val ticket = gate.tryBegin(1_000)!!
        assertTrue(gate.shouldAccept(ticket, 1_300))
        assertFalse(gate.shouldAccept(ticket, 1_301))
        assertFalse(gate.shouldAccept(ticket, 999))
    }
    @Test fun closeRejectsPendingAndFutureWorkPermanently() {
        val gate = MotionAnalysisGate()
        val ticket = gate.tryBegin(1_000)!!
        gate.close()
        assertFalse(gate.isCurrent(ticket)); assertFalse(gate.shouldAccept(ticket, 1_001))
        gate.complete(ticket)
        assertNull(gate.tryBegin(1_100))
    }
    @Test fun concurrentRequestsHaveExactlyOneOwner() {
        val gate = MotionAnalysisGate()
        val start = CountDownLatch(1)
        val finish = CountDownLatch(12)
        val accepted = ConcurrentLinkedQueue<MotionAnalysisGate.Ticket>()
        val executor = Executors.newFixedThreadPool(12)
        try {
            repeat(12) { executor.execute {
                try { start.await(); gate.tryBegin(1_000)?.let { accepted.add(it) } }
                finally { finish.countDown() }
            } }
            start.countDown()
            assertTrue(finish.await(5, TimeUnit.SECONDS)); assertEquals(1, accepted.size)
            gate.complete(accepted.single()); assertNotNull(gate.tryBegin(1_100))
        } finally { executor.shutdownNow() }
    }
}
