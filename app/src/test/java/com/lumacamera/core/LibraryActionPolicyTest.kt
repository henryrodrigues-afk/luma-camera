package com.lumacamera.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryActionPolicyTest {
    @Test fun chooserCannotBeginBeforeResumeOrAfterPause() {
        val gate = ExternalActionPolicy()
        assertNull(gate.begin())
        gate.resume(); assertNotNull(gate.begin())
        gate.pause(); assertNull(gate.begin())
    }

    @Test fun repeatedShareTapsHaveOnlyOnePendingRead() {
        val gate = ExternalActionPolicy().apply { resume() }
        val ticket = gate.begin()!!
        assertNull(gate.begin())
        assertTrue(gate.consume(ticket))
        assertFalse(gate.consume(ticket))
        assertNotNull(gate.begin())
    }

    @Test fun resultFromBeforePauseCannotLaunchAfterResume() {
        val gate = ExternalActionPolicy().apply { resume() }
        val old = gate.begin()!!
        gate.pause(); gate.resume()
        val current = gate.begin()!!
        assertFalse(gate.consume(old))
        assertNull(gate.begin()) // The stale result must not cancel the newer request.
        assertTrue(gate.consume(current))
    }

    @Test fun navigationInvalidatesShareWithoutDisablingCurrentScreen() {
        val gate = ExternalActionPolicy().apply { resume() }
        val old = gate.begin()!!
        gate.invalidate()
        val current = gate.begin()!!
        assertFalse(gate.consume(old))
        assertTrue(gate.consume(current))
    }

    @Test fun invalidOriginalNeverAutoplaysEvenWithRestoredPlayingIntent() {
        for (restoring in listOf(false, true)) for (intent in listOf(false, true))
            assertFalse(LibraryActionPolicy.initialPlayback(false, restoring, intent))
    }

    @Test fun validatedClipHonorsPauseOnRestoreButNewSelectionPlays() {
        assertTrue(LibraryActionPolicy.initialPlayback(true, false, false))
        assertTrue(LibraryActionPolicy.initialPlayback(true, true, true))
        assertFalse(LibraryActionPolicy.initialPlayback(true, true, false))
    }

    @Test fun privateToGalleryPublicationRestoresTheSameClipByStableName() {
        val candidates = listOf(LibraryActionPolicy.ClipIdentity("content://media/23", "LUMA_A.mp4"),
            LibraryActionPolicy.ClipIdentity("content://media/24", "LUMA_B.mp4"))
        assertEquals(0, LibraryActionPolicy.restoredIndex("content://private/LUMA_A.mp4", "LUMA_A.mp4", candidates))
        assertNull(LibraryActionPolicy.restoredIndex("content://private/LUMA_C.mp4", "LUMA_C.mp4", candidates))
    }

    @Test fun exactUriWinsAndDuplicateNamesCannotSelectTheWrongVideo() {
        val candidates = listOf(LibraryActionPolicy.ClipIdentity("content://media/23", "LUMA_A.mp4"),
            LibraryActionPolicy.ClipIdentity("content://media/24", "LUMA_A.mp4"))
        assertEquals(1, LibraryActionPolicy.restoredIndex("content://media/24", "LUMA_A.mp4", candidates))
        assertNull(LibraryActionPolicy.restoredIndex("content://private/LUMA_A.mp4", "LUMA_A.mp4", candidates))
        assertNull(LibraryActionPolicy.restoredIndex(null, "", candidates))
    }
}
