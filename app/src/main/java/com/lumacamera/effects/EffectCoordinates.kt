package com.lumacamera.effects

import com.lumacamera.core.FrameGeometry

/** Uses the same inverse geometry as the GPU, converting the UI's top-left origin to GLES. */
object EffectCoordinates {
    fun sourceCenter(x: Float, y: Float, rotation: Int, mirrored: Boolean): Pair<Float, Float> =
        FrameGeometry.sourceGlAt(x, 1f - y, rotation, mirrored)
}
