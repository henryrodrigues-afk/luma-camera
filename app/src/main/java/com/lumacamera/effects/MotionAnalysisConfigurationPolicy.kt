package com.lumacamera.effects

import com.lumacamera.camera.CaptureSettings

/** Separates a new image-analysis reference from changes that also replace the sampler crop. */
object MotionAnalysisConfigurationPolicy {
    data class Change(val resetReference: Boolean, val resetCorrection: Boolean)

    fun change(previous: CaptureSettings, next: CaptureSettings): Change {
        val geometry = next.stabilizationEnabled != previous.stabilizationEnabled ||
            next.stabilizationCrop != previous.stabilizationCrop ||
            next.stabilizationQuality != previous.stabilizationQuality
        val reference = geometry || next.zoomRatio != previous.zoomRatio ||
            next.stabilizationMode != previous.stabilizationMode ||
            next.stabilizationHorizonCorrection != previous.stabilizationHorizonCorrection ||
            next.stabilizationUseSubjectMask != previous.stabilizationUseSubjectMask ||
            next.subjectMode != previous.subjectMode || next.objectTapRevision != previous.objectTapRevision ||
            aiEnabled(next) != aiEnabled(previous)
        return Change(reference, geometry)
    }

    private fun aiEnabled(value: CaptureSettings) =
        value.portraitEnabled || value.cinematicEnabled || value.subjectTrackingEnabled
}
