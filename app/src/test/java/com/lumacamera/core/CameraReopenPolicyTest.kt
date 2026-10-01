package com.lumacamera.core

import org.junit.Assert.*
import org.junit.Test

class CameraReopenPolicyTest {
    private val session = CameraReopenPolicy.Identity("0", 1920, 1080, 30, 0)

    @Test fun resumeKeepsTheWorkingPreviewAndRecordingSession() {
        assertFalse(CameraReopenPolicy.shouldOpen(session, session, sessionActive = true, sameSurface = true))
    }

    @Test fun aClosedOrPausedSessionReopensEvenWithTheSameMode() {
        assertTrue(CameraReopenPolicy.shouldOpen(session, session, sessionActive = false, sameSurface = true))
        assertTrue(CameraReopenPolicy.shouldOpen(null, session, sessionActive = true, sameSurface = true))
    }

    @Test fun aReplacementSurfaceCannotReuseTheOldCameraTarget() {
        assertTrue(CameraReopenPolicy.shouldOpen(session, session, sessionActive = true, sameSurface = false))
    }

    @Test fun lensResolutionFpsAndOrientationChangesReopen() {
        for (changed in listOf(session.copy(cameraId = "1"), session.copy(width = 1280, height = 720),
            session.copy(fps = 60), session.copy(rotation = 90))) {
            assertTrue(CameraReopenPolicy.shouldOpen(session, changed, sessionActive = true, sameSurface = true))
        }
    }

    @Test fun explicitRecoveryReopensTheSameSession() {
        assertTrue(CameraReopenPolicy.shouldOpen(session, session, sessionActive = true, sameSurface = true, force = true))
    }
}
