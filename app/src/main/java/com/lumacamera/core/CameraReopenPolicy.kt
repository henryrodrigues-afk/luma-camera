package com.lumacamera.core

/** A resume must not close a working session; explicit lens/mode changes still reopen it. */
object CameraReopenPolicy {
    data class Identity(val cameraId: String, val width: Int, val height: Int, val fps: Int, val rotation: Int)

    fun shouldOpen(previous: Identity?, requested: Identity, sessionActive: Boolean,
        sameSurface: Boolean, force: Boolean = false): Boolean =
        force || !sessionActive || !sameSurface || previous != requested
}
