package com.lumacamera.effects

import com.lumacamera.core.StabilizationTransform

/** Inverts the copy sampler crop before UI applies orientation/mirroring. Cropped-out targets stay hidden. */
object SubjectTrackDisplayPolicy {
    fun point(sourceX: Float, sourceY: Float, correction: StabilizationTransform): Pair<Float, Float>? {
        return correction.outputPoint(sourceX, sourceY)
    }
}
