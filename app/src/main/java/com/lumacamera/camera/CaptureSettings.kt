package com.lumacamera.camera

import android.hardware.camera2.CaptureRequest
import com.lumacamera.core.AutomaticBlurPolicy
import com.lumacamera.core.ExposureSafetyPolicy
import com.lumacamera.core.SubjectFocusPolicy

data class CaptureSettings(
    val isoEnabled: Boolean = false,
    val shutterEnabled: Boolean = false,
    val focusEnabled: Boolean = false,
    val evEnabled: Boolean = false,
    val whiteBalanceEnabled: Boolean = false,
    val aeLockEnabled: Boolean = false,
    val awbLockEnabled: Boolean = false,
    val videoBitrateScale: Float = 1f,
    val audioMeterEnabled: Boolean = false,
    val histogramEnabled: Boolean = false,
    val waveformEnabled: Boolean = false,
    val rgbParadeEnabled: Boolean = false,
    val vectorscopeEnabled: Boolean = false,
    val previewLutEnabled: Boolean = false,
    val previewLutStrength: Float = 1f,
    val zoomRatio: Float = 1f,
    val focusLockEnabled: Boolean = false,
    val subjectTrackingEnabled: Boolean = false,
    val zebraEnabled: Boolean = false,
    val zebraThreshold: Float = .95f,
    val falseColorEnabled: Boolean = false,
    val peakingEnabled: Boolean = false,
    val peakingStrength: Float = .5f,
    val monitorOverlayStrength: Float = .6f,
    val flatEnabled: Boolean = false,
    val logEnabled: Boolean = false,
    val logProfileVersion: Int = 2,
    val logStrength: Float = 1f,
    val logPreviewAssist: Boolean = false,
    val portraitEnabled: Boolean = false,
    val cinematicEnabled: Boolean = false,
    val subjectMode: Int = 0,
    // A point belongs to the current camera scene; never restore a selected object from disk.
    val objectPointSelected: Boolean = false,
    val objectFocusX: Float = .5f,
    val objectFocusY: Float = .5f,
    val objectTapRevision: Long = 0L,
    val cinematicAutoFocus: Boolean = true,
    val cinematicTapFocus: Boolean = false,
    val cinematicTapRevision: Long = 0L,
    val focusBackground: Boolean = false,
    val cinematicFocusX: Float = .5f,
    val cinematicFocusY: Float = .5f,
    val cinematicTransitionSeconds: Float = 1f,
    val portraitQuality: Int = 0,
    val portraitStrength: Float = .22f,
    val portraitStability: Float = .42f,
    val portraitEdgeSoftness: Float = .20f,
    val stabilizationEnabled: Boolean = false,
    val stabilizationStrength: Float = .7f,
    val stabilizationResponse: Float = .55f,
    val stabilizationCrop: Float = .12f,
    val stabilizationQuality: Int = 0,
    val stabilizationUseSubjectMask: Boolean = true,
    val stabilizationMode: Int = 0,
    val stabilizationHorizonCorrection: Boolean = true,
    val gainEnabled: Boolean = false,
    val temperatureEnabled: Boolean = false,
    val blurEnabled: Boolean = false,
    val contrastEnabled: Boolean = false,
    val saturationEnabled: Boolean = false,
    val sharpnessEnabled: Boolean = false,
    val trailEnabled: Boolean = false,
    val iso: Int = 200,
    val exposureNs: Long = 16_666_666L,
    val focusDiopters: Float = 0f,
    val ev: Int = 0,
    val whiteBalance: Int = CaptureRequest.CONTROL_AWB_MODE_AUTO,
    val gainStops: Float = 0f,
    val temperature: Float = 0f,
    val blurAmount: Float = .35f,
    val blurCenterX: Float = .5f,
    val blurCenterY: Float = .5f,
    val blurRadius: Float = .25f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val sharpness: Float = .3f,
    val trailAmount: Float = .25f
) {
    /** One-time setup. Later blur, tracking and lens controls remain independently editable. */
    fun preparedForAutomaticBlur(subjectMode: Int, style: Int): CaptureSettings {
        val mode = SubjectFocusPolicy.normalizeMode(subjectMode)
        val tuning = AutomaticBlurPolicy.tuning(mode, style)
        val sameSubject = mode == this.subjectMode
        val selectedObject = mode == SubjectFocusPolicy.OBJECTS && sameSubject &&
            SubjectFocusPolicy.selectionReady(mode, objectPointSelected, objectFocusX, objectFocusY)
        return copy(
            portraitEnabled = true, subjectTrackingEnabled = true, subjectMode = mode,
            portraitStrength = tuning.strength, portraitStability = tuning.stability,
            portraitEdgeSoftness = tuning.edgeSoftness, portraitQuality = tuning.quality,
            cinematicTransitionSeconds = tuning.transitionSeconds,
            cinematicAutoFocus = true, cinematicTapFocus = false, focusBackground = false,
            // An existing object remains selected when only the style changes. Never invent a new center target.
            objectPointSelected = selectedObject,
            objectFocusX = if (selectedObject) objectFocusX else .5f,
            objectFocusY = if (selectedObject) objectFocusY else .5f,
            objectTapRevision = if (sameSubject) objectTapRevision else
                if (objectTapRevision == Long.MAX_VALUE) 0L else objectTapRevision + 1L,
            // Oval blur has a separate radius and would obscure the segmentation result.
            blurEnabled = false,
        )
    }

    /** Restore camera AE without changing the recording profile, subject, WB, stabilization or focus. */
    fun preparedForSafeAutomaticExposure(evMinimum: Int, evMaximum: Int, evStep: Float): CaptureSettings {
        val steps = ExposureSafetyPolicy.compensationSteps(evMinimum, evMaximum, evStep)
        return copy(isoEnabled = false, shutterEnabled = false, aeLockEnabled = false,
            evEnabled = steps < 0, ev = steps, gainEnabled = false, gainStops = 0f)
    }

    /** Explicit one-time setup: later edits remain independent and are never ignored. */
    fun preparedForEditing() = copy(
        logEnabled = true, logProfileVersion = 2, logStrength = 1f, logPreviewAssist = false,
        flatEnabled = false, gainEnabled = false, temperatureEnabled = false,
        contrastEnabled = false, saturationEnabled = false, sharpnessEnabled = false, trailEnabled = false
    )

    /** Keep independent software effects even if a driver rejects a hardware edit. */
    fun withHardwareFrom(other: CaptureSettings) = copy(
        isoEnabled = other.isoEnabled, shutterEnabled = other.shutterEnabled,
        focusEnabled = other.focusEnabled, evEnabled = other.evEnabled,
        focusLockEnabled = other.focusLockEnabled, zoomRatio = other.zoomRatio,
        subjectTrackingEnabled = other.subjectTrackingEnabled,
        whiteBalanceEnabled = other.whiteBalanceEnabled,
        aeLockEnabled = other.aeLockEnabled, awbLockEnabled = other.awbLockEnabled,
        iso = other.iso, exposureNs = other.exposureNs, focusDiopters = other.focusDiopters,
        ev = other.ev, whiteBalance = other.whiteBalance
    )
}
