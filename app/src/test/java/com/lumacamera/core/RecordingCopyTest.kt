package com.lumacamera.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import org.junit.Assert.*
import org.junit.Test

class RecordingCopyTest {
    @Test fun immutableVideoIsCopiedExactlyAndReservationTracksOnlySuccessfulBytes() {
        val source = ByteArray(700_003) { (it % 251).toByte() }
        val output = ByteArrayOutputStream()
        val progress = mutableListOf<Long>()
        assertEquals(source.size.toLong(), RecordingCopy.copy(ByteArrayInputStream(source), output,
            source.size.toLong()) { progress += it })
        assertArrayEquals(source, output.toByteArray())
        assertTrue(progress.zipWithNext().all { (a, b) -> b > a })
        assertEquals(listOf(262_144L, 524_288L, 700_003L), progress)
        assertEquals(0L, StoragePolicy.publicationBytesRemaining(source.size.toLong(), progress.last()))
    }

    @Test fun providerFailureCannotReleaseReservationForUnwrittenBytes() {
        var writes = 0
        val output = object : OutputStream() {
            override fun write(value: Int) = error("Bulk writes expected")
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                if (++writes == 2) throw IOException("Synthetic full destination")
            }
        }
        var acknowledged = 0L
        try {
            RecordingCopy.copy(ByteArrayInputStream(ByteArray(600_000)), output, 600_000L) { acknowledged = it }
            fail("A failed copy cannot publish")
        } catch (_: IOException) { }
        assertEquals(262_144L, acknowledged)
        assertEquals(337_856L, StoragePolicy.publicationBytesRemaining(600_000L, acknowledged))
    }

    @Test fun shortenedOriginalCannotSilentlyProduceACommittedPartialVideo() {
        try {
            RecordingCopy.copy(ByteArrayInputStream(ByteArray(100)), ByteArrayOutputStream(), 200L)
            fail("Truncation must fail")
        } catch (_: IllegalStateException) { }
    }

    @Test fun growingOriginalFailsBeforeExceedingTheReservedCopyBudget() {
        val output = ByteArrayOutputStream()
        try {
            RecordingCopy.copy(ByteArrayInputStream(ByteArray(200)), output, 100L)
            fail("Changed original must fail")
        } catch (_: IllegalStateException) { }
        assertEquals(0, output.size())
    }
}
