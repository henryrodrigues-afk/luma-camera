package com.lumacamera.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RecordingFilesTest {
    @Test fun acceptsTheRecorderNames() {
        assertTrue(RecordingFiles.isRecordingName("LUMA_20261001_101530_120.mp4"))
        assertTrue(RecordingFiles.isRecordingName("LUMA_video.MP4"))
        assertFalse(RecordingFiles.isRecordingName("other.mp4"))
    }

    @Test fun rejectsTraversalAndForeignFiles() {
        val directory = File(System.getProperty("java.io.tmpdir"), "luma-path-policy")
        listOf("../LUMA_escape.mp4", "LUMA_../other.mp4", "LUMA_..\\other.mp4", "LUMA_video.txt", "LUMA_\u0000.mp4").forEach {
            try { RecordingFiles.resolve(directory, it); fail("Accepted $it") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun permitsOnlyARecordingInsideTheExactDirectory() {
        val directory = File(System.getProperty("java.io.tmpdir"), "luma-path-policy")
        val file = RecordingFiles.resolve(directory, "LUMA_video.mp4")
        assertEquals(directory.canonicalFile, file.parentFile)
    }
}
