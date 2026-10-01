package com.lumacamera.ui

import android.content.Context
import android.content.SharedPreferences
import com.lumacamera.camera.CaptureSettings
import com.lumacamera.core.SubjectFocusPolicy

data class AppPreferences(
    val microphoneEnabled: Boolean = true,
    val grid: Boolean = true,
    val gridMode: Int = 0,
    val frameGuide: Int = 0,
    val safeArea: Boolean = false,
    val level: Boolean = false,
    val keepScreenOn: Boolean = true,
    val volumeShutter: Boolean = true,
    val defaultCameraId: String? = null,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val videoFps: Int = 0
)

/** Saves each switch/value independently. Device-specific limits still belong to CameraCatalog. */
class CameraPreferences(context: Context) {
    private val store = context.getSharedPreferences("luma-camera", Context.MODE_PRIVATE)

    fun loadCaptureSettings(): CaptureSettings {
        val fallback = CaptureSettings()
        return normalizeCaptureSettings(CaptureSettings(
            isoEnabled = store.bool("isoEnabled", fallback.isoEnabled),
            shutterEnabled = store.bool("shutterEnabled", fallback.shutterEnabled),
            focusEnabled = store.bool("focusEnabled", fallback.focusEnabled),
            evEnabled = store.bool("evEnabled", fallback.evEnabled),
            whiteBalanceEnabled = store.bool("whiteBalanceEnabled", fallback.whiteBalanceEnabled),
            aeLockEnabled = store.bool("aeLockEnabled", fallback.aeLockEnabled),
            awbLockEnabled = store.bool("awbLockEnabled", fallback.awbLockEnabled),
            videoBitrateScale = store.float("videoBitrateScale", fallback.videoBitrateScale),
            audioMeterEnabled = store.bool("audioMeterEnabled", fallback.audioMeterEnabled),
            histogramEnabled = store.bool("histogramEnabled", fallback.histogramEnabled),
            waveformEnabled = store.bool("waveformEnabled", fallback.waveformEnabled),
            rgbParadeEnabled = store.bool("rgbParadeEnabled", fallback.rgbParadeEnabled),
            vectorscopeEnabled = store.bool("vectorscopeEnabled", fallback.vectorscopeEnabled),
            previewLutEnabled = store.bool("previewLutEnabled", fallback.previewLutEnabled),
            previewLutStrength = store.float("previewLutStrength", fallback.previewLutStrength),
            zoomRatio = store.float("zoomRatio", fallback.zoomRatio),
            focusLockEnabled = store.bool("focusLockEnabled", fallback.focusLockEnabled),
            subjectTrackingEnabled = store.bool("subjectTrackingEnabled", fallback.subjectTrackingEnabled),
            zebraEnabled = store.bool("zebraEnabled", fallback.zebraEnabled),
            zebraThreshold = store.float("zebraThreshold", fallback.zebraThreshold),
            falseColorEnabled = store.bool("falseColorEnabled", fallback.falseColorEnabled),
            peakingEnabled = store.bool("peakingEnabled", fallback.peakingEnabled),
            peakingStrength = store.float("peakingStrength", fallback.peakingStrength),
            monitorOverlayStrength = store.float("monitorOverlayStrength", fallback.monitorOverlayStrength),
            logEnabled = store.bool("logEnabled", fallback.logEnabled),
            logProfileVersion = store.int("logProfileVersion", fallback.logProfileVersion),
            logStrength = store.float("logStrength", fallback.logStrength),
            logPreviewAssist = store.bool("logPreviewAssist", fallback.logPreviewAssist),
            portraitEnabled = store.bool("portraitEnabled", fallback.portraitEnabled),
            cinematicEnabled = store.bool("cinematicEnabled", fallback.cinematicEnabled),
            subjectMode = store.int("subjectMode", fallback.subjectMode),
            objectFocusX = store.float("objectFocusX", fallback.objectFocusX),
            objectFocusY = store.float("objectFocusY", fallback.objectFocusY),
            objectTapRevision = store.long("objectTapRevision", fallback.objectTapRevision),
            cinematicAutoFocus = store.bool("cinematicAutoFocus", fallback.cinematicAutoFocus),
            cinematicTapFocus = store.bool("cinematicTapFocus", fallback.cinematicTapFocus),
            cinematicTapRevision = store.long("cinematicTapRevision", fallback.cinematicTapRevision),
            focusBackground = store.bool("focusBackground", fallback.focusBackground),
            cinematicFocusX = store.float("cinematicFocusX", fallback.cinematicFocusX),
            cinematicFocusY = store.float("cinematicFocusY", fallback.cinematicFocusY),
            cinematicTransitionSeconds = store.float("cinematicTransitionSeconds", fallback.cinematicTransitionSeconds),
            portraitQuality = store.int("portraitQuality", fallback.portraitQuality),
            portraitStrength = store.float("portraitStrength", fallback.portraitStrength),
            portraitStability = store.float("portraitStability", fallback.portraitStability),
            portraitEdgeSoftness = store.float("portraitEdgeSoftness", fallback.portraitEdgeSoftness),
            stabilizationEnabled = store.bool("stabilizationEnabled", fallback.stabilizationEnabled),
            stabilizationStrength = store.float("stabilizationStrength", fallback.stabilizationStrength),
            stabilizationResponse = store.float("stabilizationResponse", fallback.stabilizationResponse),
            stabilizationCrop = store.float("stabilizationCrop", fallback.stabilizationCrop),
            stabilizationQuality = store.int("stabilizationQuality", fallback.stabilizationQuality),
            stabilizationUseSubjectMask = store.bool("stabilizationUseSubjectMask", fallback.stabilizationUseSubjectMask),
            stabilizationMode = store.int("stabilizationMode", fallback.stabilizationMode),
            stabilizationHorizonCorrection = store.bool("stabilizationHorizonCorrection", fallback.stabilizationHorizonCorrection),
            flatEnabled = store.bool("flatEnabled", fallback.flatEnabled),
            gainEnabled = store.bool("gainEnabled", fallback.gainEnabled),
            temperatureEnabled = store.bool("temperatureEnabled", fallback.temperatureEnabled),
            blurEnabled = store.bool("blurEnabled", fallback.blurEnabled),
            contrastEnabled = store.bool("contrastEnabled", fallback.contrastEnabled),
            saturationEnabled = store.bool("saturationEnabled", fallback.saturationEnabled),
            sharpnessEnabled = store.bool("sharpnessEnabled", fallback.sharpnessEnabled),
            trailEnabled = store.bool("trailEnabled", fallback.trailEnabled),
            iso = store.int("iso", fallback.iso),
            exposureNs = store.long("exposureNs", fallback.exposureNs),
            focusDiopters = store.float("focusDiopters", fallback.focusDiopters),
            ev = store.int("ev", fallback.ev),
            whiteBalance = store.int("whiteBalance", fallback.whiteBalance),
            gainStops = store.float("gainStops", fallback.gainStops),
            temperature = store.float("temperature", fallback.temperature),
            blurAmount = store.float("blurAmount", fallback.blurAmount),
            blurCenterX = store.float("blurCenterX", fallback.blurCenterX),
            blurCenterY = store.float("blurCenterY", fallback.blurCenterY),
            blurRadius = store.float("blurRadius", fallback.blurRadius),
            contrast = store.float("contrast", fallback.contrast),
            saturation = store.float("saturation", fallback.saturation),
            sharpness = store.float("sharpness", fallback.sharpness),
            trailAmount = store.float("trailAmount", fallback.trailAmount)
        ))
    }

    fun saveCaptureSettings(settings: CaptureSettings) {
        val value = normalizeCaptureSettings(settings)
        store.edit().apply {
            putBoolean("isoEnabled", value.isoEnabled)
            putBoolean("shutterEnabled", value.shutterEnabled)
            putBoolean("focusEnabled", value.focusEnabled)
            putBoolean("evEnabled", value.evEnabled)
            putBoolean("whiteBalanceEnabled", value.whiteBalanceEnabled)
            putBoolean("aeLockEnabled", value.aeLockEnabled)
            putBoolean("awbLockEnabled", value.awbLockEnabled)
            putFloat("videoBitrateScale", value.videoBitrateScale)
            putBoolean("audioMeterEnabled", value.audioMeterEnabled)
            putBoolean("histogramEnabled", value.histogramEnabled)
            putBoolean("waveformEnabled", value.waveformEnabled)
            putBoolean("rgbParadeEnabled", value.rgbParadeEnabled)
            putBoolean("vectorscopeEnabled", value.vectorscopeEnabled)
            putBoolean("previewLutEnabled", value.previewLutEnabled)
            putFloat("previewLutStrength", value.previewLutStrength)
            putFloat("zoomRatio", value.zoomRatio)
            putBoolean("focusLockEnabled", value.focusLockEnabled)
            putBoolean("subjectTrackingEnabled", value.subjectTrackingEnabled)
            putBoolean("zebraEnabled", value.zebraEnabled)
            putFloat("zebraThreshold", value.zebraThreshold)
            putBoolean("falseColorEnabled", value.falseColorEnabled)
            putBoolean("peakingEnabled", value.peakingEnabled)
            putFloat("peakingStrength", value.peakingStrength)
            putFloat("monitorOverlayStrength", value.monitorOverlayStrength)
            putBoolean("logEnabled", value.logEnabled)
            putInt("logProfileVersion", value.logProfileVersion)
            putFloat("logStrength", value.logStrength)
            putBoolean("logPreviewAssist", value.logPreviewAssist)
            putBoolean("portraitEnabled", value.portraitEnabled)
            putBoolean("cinematicEnabled", value.cinematicEnabled)
            putInt("subjectMode", value.subjectMode)
            putFloat("objectFocusX", value.objectFocusX)
            putFloat("objectFocusY", value.objectFocusY)
            putLong("objectTapRevision", value.objectTapRevision)
            putBoolean("cinematicAutoFocus", value.cinematicAutoFocus)
            putBoolean("cinematicTapFocus", value.cinematicTapFocus)
            putLong("cinematicTapRevision", value.cinematicTapRevision)
            putBoolean("focusBackground", value.focusBackground)
            putFloat("cinematicFocusX", value.cinematicFocusX)
            putFloat("cinematicFocusY", value.cinematicFocusY)
            putFloat("cinematicTransitionSeconds", value.cinematicTransitionSeconds)
            putInt("portraitQuality", value.portraitQuality)
            putFloat("portraitStrength", value.portraitStrength)
            putFloat("portraitStability", value.portraitStability)
            putFloat("portraitEdgeSoftness", value.portraitEdgeSoftness)
            putBoolean("stabilizationEnabled", value.stabilizationEnabled)
            putFloat("stabilizationStrength", value.stabilizationStrength)
            putFloat("stabilizationResponse", value.stabilizationResponse)
            putFloat("stabilizationCrop", value.stabilizationCrop)
            putInt("stabilizationQuality", value.stabilizationQuality)
            putBoolean("stabilizationUseSubjectMask", value.stabilizationUseSubjectMask)
            putInt("stabilizationMode", value.stabilizationMode)
            putBoolean("stabilizationHorizonCorrection", value.stabilizationHorizonCorrection)
            putBoolean("flatEnabled", value.flatEnabled)
            putBoolean("gainEnabled", value.gainEnabled)
            putBoolean("temperatureEnabled", value.temperatureEnabled)
            putBoolean("blurEnabled", value.blurEnabled)
            putBoolean("contrastEnabled", value.contrastEnabled)
            putBoolean("saturationEnabled", value.saturationEnabled)
            putBoolean("sharpnessEnabled", value.sharpnessEnabled)
            putBoolean("trailEnabled", value.trailEnabled)
            putInt("iso", value.iso)
            putLong("exposureNs", value.exposureNs)
            putFloat("focusDiopters", value.focusDiopters)
            putInt("ev", value.ev)
            putInt("whiteBalance", value.whiteBalance)
            putFloat("gainStops", value.gainStops)
            putFloat("temperature", value.temperature)
            putFloat("blurAmount", value.blurAmount)
            putFloat("blurCenterX", value.blurCenterX)
            putFloat("blurCenterY", value.blurCenterY)
            putFloat("blurRadius", value.blurRadius)
            putFloat("contrast", value.contrast)
            putFloat("saturation", value.saturation)
            putFloat("sharpness", value.sharpness)
            putFloat("trailAmount", value.trailAmount)
        }.apply()
    }

    fun captureSnapshot(settings: CaptureSettings): org.json.JSONObject {
        saveCaptureSettings(settings)
        val defaults = CaptureSettings()
        return org.json.JSONObject().apply {
        put("isoEnabled", store.bool("isoEnabled", defaults.isoEnabled))
        put("shutterEnabled", store.bool("shutterEnabled", defaults.shutterEnabled))
        put("focusEnabled", store.bool("focusEnabled", defaults.focusEnabled))
        put("evEnabled", store.bool("evEnabled", defaults.evEnabled))
        put("whiteBalanceEnabled", store.bool("whiteBalanceEnabled", defaults.whiteBalanceEnabled))
        put("aeLockEnabled", store.bool("aeLockEnabled", defaults.aeLockEnabled))
        put("awbLockEnabled", store.bool("awbLockEnabled", defaults.awbLockEnabled))
        put("videoBitrateScale", store.float("videoBitrateScale", defaults.videoBitrateScale))
        put("audioMeterEnabled", store.bool("audioMeterEnabled", defaults.audioMeterEnabled))
        put("histogramEnabled", store.bool("histogramEnabled", defaults.histogramEnabled))
        put("waveformEnabled", store.bool("waveformEnabled", defaults.waveformEnabled))
        put("rgbParadeEnabled", store.bool("rgbParadeEnabled", defaults.rgbParadeEnabled))
        put("vectorscopeEnabled", store.bool("vectorscopeEnabled", defaults.vectorscopeEnabled))
        put("previewLutEnabled", store.bool("previewLutEnabled", defaults.previewLutEnabled))
        put("previewLutStrength", store.float("previewLutStrength", defaults.previewLutStrength))
        put("zoomRatio", store.float("zoomRatio", defaults.zoomRatio))
        put("focusLockEnabled", store.bool("focusLockEnabled", defaults.focusLockEnabled))
        put("subjectTrackingEnabled", store.bool("subjectTrackingEnabled", defaults.subjectTrackingEnabled))
        put("zebraEnabled", store.bool("zebraEnabled", defaults.zebraEnabled))
        put("zebraThreshold", store.float("zebraThreshold", defaults.zebraThreshold))
        put("falseColorEnabled", store.bool("falseColorEnabled", defaults.falseColorEnabled))
        put("peakingEnabled", store.bool("peakingEnabled", defaults.peakingEnabled))
        put("peakingStrength", store.float("peakingStrength", defaults.peakingStrength))
        put("monitorOverlayStrength", store.float("monitorOverlayStrength", defaults.monitorOverlayStrength))
        put("logEnabled", store.bool("logEnabled", defaults.logEnabled))
        put("logProfileVersion", store.int("logProfileVersion", defaults.logProfileVersion))
        put("logStrength", store.float("logStrength", defaults.logStrength))
        put("logPreviewAssist", store.bool("logPreviewAssist", defaults.logPreviewAssist))
        put("portraitEnabled", store.bool("portraitEnabled", defaults.portraitEnabled))
        put("cinematicEnabled", store.bool("cinematicEnabled", defaults.cinematicEnabled))
        put("subjectMode", store.int("subjectMode", defaults.subjectMode))
        put("objectFocusX", store.float("objectFocusX", defaults.objectFocusX))
        put("objectFocusY", store.float("objectFocusY", defaults.objectFocusY))
        put("objectTapRevision", store.long("objectTapRevision", defaults.objectTapRevision))
        put("cinematicAutoFocus", store.bool("cinematicAutoFocus", defaults.cinematicAutoFocus))
        put("cinematicTapFocus", store.bool("cinematicTapFocus", defaults.cinematicTapFocus))
        put("cinematicTapRevision", store.long("cinematicTapRevision", defaults.cinematicTapRevision))
        put("focusBackground", store.bool("focusBackground", defaults.focusBackground))
        put("cinematicFocusX", store.float("cinematicFocusX", defaults.cinematicFocusX))
        put("cinematicFocusY", store.float("cinematicFocusY", defaults.cinematicFocusY))
        put("cinematicTransitionSeconds", store.float("cinematicTransitionSeconds", defaults.cinematicTransitionSeconds))
        put("portraitQuality", store.int("portraitQuality", defaults.portraitQuality))
        put("portraitStrength", store.float("portraitStrength", defaults.portraitStrength))
        put("portraitStability", store.float("portraitStability", defaults.portraitStability))
        put("portraitEdgeSoftness", store.float("portraitEdgeSoftness", defaults.portraitEdgeSoftness))
        put("stabilizationEnabled", store.bool("stabilizationEnabled", defaults.stabilizationEnabled))
        put("stabilizationStrength", store.float("stabilizationStrength", defaults.stabilizationStrength))
        put("stabilizationResponse", store.float("stabilizationResponse", defaults.stabilizationResponse))
        put("stabilizationCrop", store.float("stabilizationCrop", defaults.stabilizationCrop))
        put("stabilizationQuality", store.int("stabilizationQuality", defaults.stabilizationQuality))
        put("stabilizationUseSubjectMask", store.bool("stabilizationUseSubjectMask", defaults.stabilizationUseSubjectMask))
        put("stabilizationMode", store.int("stabilizationMode", defaults.stabilizationMode))
        put("stabilizationHorizonCorrection", store.bool("stabilizationHorizonCorrection", defaults.stabilizationHorizonCorrection))
        put("flatEnabled", store.bool("flatEnabled", defaults.flatEnabled))
        put("gainEnabled", store.bool("gainEnabled", defaults.gainEnabled))
        put("temperatureEnabled", store.bool("temperatureEnabled", defaults.temperatureEnabled))
        put("blurEnabled", store.bool("blurEnabled", defaults.blurEnabled))
        put("contrastEnabled", store.bool("contrastEnabled", defaults.contrastEnabled))
        put("saturationEnabled", store.bool("saturationEnabled", defaults.saturationEnabled))
        put("sharpnessEnabled", store.bool("sharpnessEnabled", defaults.sharpnessEnabled))
        put("trailEnabled", store.bool("trailEnabled", defaults.trailEnabled))
        put("iso", store.int("iso", defaults.iso))
        put("exposureNs", store.long("exposureNs", defaults.exposureNs))
        put("focusDiopters", store.float("focusDiopters", defaults.focusDiopters))
        put("ev", store.int("ev", defaults.ev))
        put("whiteBalance", store.int("whiteBalance", defaults.whiteBalance))
        put("gainStops", store.float("gainStops", defaults.gainStops))
        put("temperature", store.float("temperature", defaults.temperature))
        put("blurAmount", store.float("blurAmount", defaults.blurAmount))
        put("blurCenterX", store.float("blurCenterX", defaults.blurCenterX))
        put("blurCenterY", store.float("blurCenterY", defaults.blurCenterY))
        put("blurRadius", store.float("blurRadius", defaults.blurRadius))
        put("contrast", store.float("contrast", defaults.contrast))
        put("saturation", store.float("saturation", defaults.saturation))
        put("sharpness", store.float("sharpness", defaults.sharpness))
        put("trailAmount", store.float("trailAmount", defaults.trailAmount))
        }
    }

    /** Only known typed capture keys are restored; unrelated app preferences are preserved. */
    fun restoreCaptureSnapshot(snapshot: org.json.JSONObject): CaptureSettings {
        val editor = store.edit()
        editor.apply {
            if (snapshot.has("isoEnabled")) putBoolean("isoEnabled", snapshot.getBoolean("isoEnabled"))
            if (snapshot.has("shutterEnabled")) putBoolean("shutterEnabled", snapshot.getBoolean("shutterEnabled"))
            if (snapshot.has("focusEnabled")) putBoolean("focusEnabled", snapshot.getBoolean("focusEnabled"))
            if (snapshot.has("evEnabled")) putBoolean("evEnabled", snapshot.getBoolean("evEnabled"))
            if (snapshot.has("whiteBalanceEnabled")) putBoolean("whiteBalanceEnabled", snapshot.getBoolean("whiteBalanceEnabled"))
            if (snapshot.has("aeLockEnabled")) putBoolean("aeLockEnabled", snapshot.getBoolean("aeLockEnabled"))
            if (snapshot.has("awbLockEnabled")) putBoolean("awbLockEnabled", snapshot.getBoolean("awbLockEnabled"))
            if (snapshot.has("videoBitrateScale")) putFloat("videoBitrateScale", snapshot.getDouble("videoBitrateScale").toFloat())
            if (snapshot.has("audioMeterEnabled")) putBoolean("audioMeterEnabled", snapshot.getBoolean("audioMeterEnabled"))
            if (snapshot.has("histogramEnabled")) putBoolean("histogramEnabled", snapshot.getBoolean("histogramEnabled"))
            if (snapshot.has("waveformEnabled")) putBoolean("waveformEnabled", snapshot.getBoolean("waveformEnabled"))
            if (snapshot.has("rgbParadeEnabled")) putBoolean("rgbParadeEnabled", snapshot.getBoolean("rgbParadeEnabled"))
            if (snapshot.has("vectorscopeEnabled")) putBoolean("vectorscopeEnabled", snapshot.getBoolean("vectorscopeEnabled"))
            if (snapshot.has("previewLutEnabled")) putBoolean("previewLutEnabled", snapshot.getBoolean("previewLutEnabled"))
            if (snapshot.has("previewLutStrength")) putFloat("previewLutStrength", snapshot.getDouble("previewLutStrength").toFloat())
            if (snapshot.has("zoomRatio")) putFloat("zoomRatio", snapshot.getDouble("zoomRatio").toFloat())
            if (snapshot.has("focusLockEnabled")) putBoolean("focusLockEnabled", snapshot.getBoolean("focusLockEnabled"))
            if (snapshot.has("subjectTrackingEnabled")) putBoolean("subjectTrackingEnabled", snapshot.getBoolean("subjectTrackingEnabled"))
            if (snapshot.has("zebraEnabled")) putBoolean("zebraEnabled", snapshot.getBoolean("zebraEnabled"))
            if (snapshot.has("zebraThreshold")) putFloat("zebraThreshold", snapshot.getDouble("zebraThreshold").toFloat())
            if (snapshot.has("falseColorEnabled")) putBoolean("falseColorEnabled", snapshot.getBoolean("falseColorEnabled"))
            if (snapshot.has("peakingEnabled")) putBoolean("peakingEnabled", snapshot.getBoolean("peakingEnabled"))
            if (snapshot.has("peakingStrength")) putFloat("peakingStrength", snapshot.getDouble("peakingStrength").toFloat())
            if (snapshot.has("monitorOverlayStrength")) putFloat("monitorOverlayStrength", snapshot.getDouble("monitorOverlayStrength").toFloat())
            if (snapshot.has("logEnabled")) putBoolean("logEnabled", snapshot.getBoolean("logEnabled"))
            if (snapshot.has("logProfileVersion")) putInt("logProfileVersion", snapshot.getInt("logProfileVersion"))
            if (snapshot.has("logStrength")) putFloat("logStrength", snapshot.getDouble("logStrength").toFloat())
            if (snapshot.has("logPreviewAssist")) putBoolean("logPreviewAssist", snapshot.getBoolean("logPreviewAssist"))
            if (snapshot.has("portraitEnabled")) putBoolean("portraitEnabled", snapshot.getBoolean("portraitEnabled"))
            if (snapshot.has("cinematicEnabled")) putBoolean("cinematicEnabled", snapshot.getBoolean("cinematicEnabled"))
            if (snapshot.has("subjectMode")) putInt("subjectMode", snapshot.getInt("subjectMode"))
            if (snapshot.has("objectFocusX")) putFloat("objectFocusX", snapshot.getDouble("objectFocusX").toFloat())
            if (snapshot.has("objectFocusY")) putFloat("objectFocusY", snapshot.getDouble("objectFocusY").toFloat())
            if (snapshot.has("objectTapRevision")) putLong("objectTapRevision", snapshot.getLong("objectTapRevision"))
            if (snapshot.has("cinematicAutoFocus")) putBoolean("cinematicAutoFocus", snapshot.getBoolean("cinematicAutoFocus"))
            if (snapshot.has("cinematicTapFocus")) putBoolean("cinematicTapFocus", snapshot.getBoolean("cinematicTapFocus"))
            if (snapshot.has("cinematicTapRevision")) putLong("cinematicTapRevision", snapshot.getLong("cinematicTapRevision"))
            if (snapshot.has("focusBackground")) putBoolean("focusBackground", snapshot.getBoolean("focusBackground"))
            if (snapshot.has("cinematicFocusX")) putFloat("cinematicFocusX", snapshot.getDouble("cinematicFocusX").toFloat())
            if (snapshot.has("cinematicFocusY")) putFloat("cinematicFocusY", snapshot.getDouble("cinematicFocusY").toFloat())
            if (snapshot.has("cinematicTransitionSeconds")) putFloat("cinematicTransitionSeconds", snapshot.getDouble("cinematicTransitionSeconds").toFloat())
            if (snapshot.has("portraitQuality")) putInt("portraitQuality", snapshot.getInt("portraitQuality"))
            if (snapshot.has("portraitStrength")) putFloat("portraitStrength", snapshot.getDouble("portraitStrength").toFloat())
            if (snapshot.has("portraitStability")) putFloat("portraitStability", snapshot.getDouble("portraitStability").toFloat())
            if (snapshot.has("portraitEdgeSoftness")) putFloat("portraitEdgeSoftness", snapshot.getDouble("portraitEdgeSoftness").toFloat())
            if (snapshot.has("stabilizationEnabled")) putBoolean("stabilizationEnabled", snapshot.getBoolean("stabilizationEnabled"))
            if (snapshot.has("stabilizationStrength")) putFloat("stabilizationStrength", snapshot.getDouble("stabilizationStrength").toFloat())
            if (snapshot.has("stabilizationResponse")) putFloat("stabilizationResponse", snapshot.getDouble("stabilizationResponse").toFloat())
            if (snapshot.has("stabilizationCrop")) putFloat("stabilizationCrop", snapshot.getDouble("stabilizationCrop").toFloat())
            if (snapshot.has("stabilizationQuality")) putInt("stabilizationQuality", snapshot.getInt("stabilizationQuality"))
            if (snapshot.has("stabilizationUseSubjectMask")) putBoolean("stabilizationUseSubjectMask", snapshot.getBoolean("stabilizationUseSubjectMask"))
            if (snapshot.has("stabilizationMode")) putInt("stabilizationMode", snapshot.getInt("stabilizationMode"))
            if (snapshot.has("stabilizationHorizonCorrection")) putBoolean("stabilizationHorizonCorrection", snapshot.getBoolean("stabilizationHorizonCorrection"))
            if (snapshot.has("flatEnabled")) putBoolean("flatEnabled", snapshot.getBoolean("flatEnabled"))
            if (snapshot.has("gainEnabled")) putBoolean("gainEnabled", snapshot.getBoolean("gainEnabled"))
            if (snapshot.has("temperatureEnabled")) putBoolean("temperatureEnabled", snapshot.getBoolean("temperatureEnabled"))
            if (snapshot.has("blurEnabled")) putBoolean("blurEnabled", snapshot.getBoolean("blurEnabled"))
            if (snapshot.has("contrastEnabled")) putBoolean("contrastEnabled", snapshot.getBoolean("contrastEnabled"))
            if (snapshot.has("saturationEnabled")) putBoolean("saturationEnabled", snapshot.getBoolean("saturationEnabled"))
            if (snapshot.has("sharpnessEnabled")) putBoolean("sharpnessEnabled", snapshot.getBoolean("sharpnessEnabled"))
            if (snapshot.has("trailEnabled")) putBoolean("trailEnabled", snapshot.getBoolean("trailEnabled"))
            if (snapshot.has("iso")) putInt("iso", snapshot.getInt("iso"))
            if (snapshot.has("exposureNs")) putLong("exposureNs", snapshot.getLong("exposureNs"))
            if (snapshot.has("focusDiopters")) putFloat("focusDiopters", snapshot.getDouble("focusDiopters").toFloat())
            if (snapshot.has("ev")) putInt("ev", snapshot.getInt("ev"))
            if (snapshot.has("whiteBalance")) putInt("whiteBalance", snapshot.getInt("whiteBalance"))
            if (snapshot.has("gainStops")) putFloat("gainStops", snapshot.getDouble("gainStops").toFloat())
            if (snapshot.has("temperature")) putFloat("temperature", snapshot.getDouble("temperature").toFloat())
            if (snapshot.has("blurAmount")) putFloat("blurAmount", snapshot.getDouble("blurAmount").toFloat())
            if (snapshot.has("blurCenterX")) putFloat("blurCenterX", snapshot.getDouble("blurCenterX").toFloat())
            if (snapshot.has("blurCenterY")) putFloat("blurCenterY", snapshot.getDouble("blurCenterY").toFloat())
            if (snapshot.has("blurRadius")) putFloat("blurRadius", snapshot.getDouble("blurRadius").toFloat())
            if (snapshot.has("contrast")) putFloat("contrast", snapshot.getDouble("contrast").toFloat())
            if (snapshot.has("saturation")) putFloat("saturation", snapshot.getDouble("saturation").toFloat())
            if (snapshot.has("sharpness")) putFloat("sharpness", snapshot.getDouble("sharpness").toFloat())
            if (snapshot.has("trailAmount")) putFloat("trailAmount", snapshot.getDouble("trailAmount").toFloat())
        }.apply()
        return loadCaptureSettings().copy(objectPointSelected = false, cinematicTapFocus = false,
            focusLockEnabled = false)
    }

    fun loadAppPreferences(): AppPreferences = normalizeAppPreferences(AppPreferences(
        microphoneEnabled = store.bool("microphoneEnabled", true),
        grid = store.bool("grid", true),
        gridMode = store.int("gridMode", 0),
        frameGuide = store.int("frameGuide", 0),
        safeArea = store.bool("safeArea", false),
        level = store.bool("level", false),
        keepScreenOn = store.bool("keepScreenOn", true),
        volumeShutter = store.bool("volumeShutter", true),
        defaultCameraId = runCatching { store.getString("defaultCameraId", null) }.getOrNull(),
        videoWidth = store.int("videoWidth", 0),
        videoHeight = store.int("videoHeight", 0),
        videoFps = store.int("videoFps", 0)
    ))

    fun saveAppPreferences(preferences: AppPreferences) {
        val value = normalizeAppPreferences(preferences)
        store.edit().apply {
            putBoolean("microphoneEnabled", value.microphoneEnabled)
            putBoolean("grid", value.grid)
            putInt("gridMode", value.gridMode)
            putInt("frameGuide", value.frameGuide)
            putBoolean("safeArea", value.safeArea)
            putBoolean("level", value.level)
            putBoolean("keepScreenOn", value.keepScreenOn)
            putBoolean("volumeShutter", value.volumeShutter)
            putString("defaultCameraId", value.defaultCameraId)
            putInt("videoWidth", value.videoWidth)
            putInt("videoHeight", value.videoHeight)
            putInt("videoFps", value.videoFps)
        }.apply()
    }

    private fun SharedPreferences.bool(key: String, fallback: Boolean) = runCatching { getBoolean(key, fallback) }.getOrDefault(fallback)
    private fun SharedPreferences.int(key: String, fallback: Int) = runCatching { getInt(key, fallback) }.getOrDefault(fallback)
    private fun SharedPreferences.long(key: String, fallback: Long) = runCatching { getLong(key, fallback) }.getOrDefault(fallback)
    private fun SharedPreferences.float(key: String, fallback: Float) = runCatching { getFloat(key, fallback) }.getOrDefault(fallback)
}

/** Storage cannot inject NaN into shaders; the selected lens still supplies its sensor limits. */
internal fun normalizeCaptureSettings(value: CaptureSettings): CaptureSettings {
    val defaults = CaptureSettings()
    fun bounded(number: Float, low: Float, high: Float, fallback: Float): Float =
        if (number.isFinite()) number.coerceIn(low, high) else fallback
    return value.copy(
        iso = value.iso.coerceIn(1, 1_000_000),
        exposureNs = value.exposureNs.coerceIn(1L, 60_000_000_000L),
        focusDiopters = bounded(value.focusDiopters, 0f, 100f, defaults.focusDiopters),
        ev = value.ev.coerceIn(-100, 100),
        whiteBalance = value.whiteBalance.takeIf { it in 1..8 } ?: defaults.whiteBalance,
        videoBitrateScale = bounded(value.videoBitrateScale, .5f, 2f, defaults.videoBitrateScale),
        previewLutStrength = bounded(value.previewLutStrength, 0f, 1f, 1f),
        zoomRatio = bounded(value.zoomRatio, 1f, 20f, 1f),
        zebraThreshold = bounded(value.zebraThreshold, .5f, 1f, defaults.zebraThreshold),
        peakingStrength = bounded(value.peakingStrength, 0f, 1f, defaults.peakingStrength),
        monitorOverlayStrength = bounded(value.monitorOverlayStrength, 0f, 1f, defaults.monitorOverlayStrength),
        logStrength = bounded(value.logStrength, 0f, 1f, defaults.logStrength),
        logProfileVersion = value.logProfileVersion.takeIf { it == 1 || it == 2 } ?: defaults.logProfileVersion,
        subjectMode = SubjectFocusPolicy.normalizeMode(value.subjectMode),
        objectPointSelected = value.objectPointSelected && value.objectFocusX.isFinite() &&
            value.objectFocusY.isFinite() && value.objectFocusX in 0f..1f && value.objectFocusY in 0f..1f,
        objectFocusX = bounded(value.objectFocusX, 0f, 1f, defaults.objectFocusX),
        objectFocusY = bounded(value.objectFocusY, 0f, 1f, defaults.objectFocusY),
        cinematicFocusX = bounded(value.cinematicFocusX, 0f, 1f, defaults.cinematicFocusX),
        cinematicFocusY = bounded(value.cinematicFocusY, 0f, 1f, defaults.cinematicFocusY),
        cinematicTransitionSeconds = bounded(value.cinematicTransitionSeconds, .2f, 3f, defaults.cinematicTransitionSeconds),
        portraitStrength = bounded(value.portraitStrength, 0f, 1f, defaults.portraitStrength),
        portraitStability = bounded(value.portraitStability, 0f, 1f, defaults.portraitStability),
        portraitEdgeSoftness = bounded(value.portraitEdgeSoftness, 0f, 1f, defaults.portraitEdgeSoftness),
        stabilizationStrength = bounded(value.stabilizationStrength, 0f, 1f, defaults.stabilizationStrength),
        stabilizationResponse = bounded(value.stabilizationResponse, 0f, 1f, defaults.stabilizationResponse),
        stabilizationCrop = bounded(value.stabilizationCrop, .04f, .25f, defaults.stabilizationCrop),
        stabilizationQuality = value.stabilizationQuality.coerceIn(0, 1),
        stabilizationMode = value.stabilizationMode.takeIf { it in 0..2 } ?: defaults.stabilizationMode,
        portraitQuality = value.portraitQuality.coerceIn(0, 1),
        gainStops = bounded(value.gainStops, -3f, 3f, defaults.gainStops),
        temperature = bounded(value.temperature, -1f, 1f, defaults.temperature),
        blurAmount = bounded(value.blurAmount, 0f, 1f, defaults.blurAmount),
        blurCenterX = bounded(value.blurCenterX, 0f, 1f, defaults.blurCenterX),
        blurCenterY = bounded(value.blurCenterY, 0f, 1f, defaults.blurCenterY),
        blurRadius = bounded(value.blurRadius, .1f, .7f, defaults.blurRadius),
        contrast = bounded(value.contrast, 0f, 2f, defaults.contrast),
        saturation = bounded(value.saturation, 0f, 2f, defaults.saturation),
        sharpness = bounded(value.sharpness, 0f, 1f, defaults.sharpness),
        trailAmount = bounded(value.trailAmount, 0f, .9f, defaults.trailAmount)
    )
}

internal fun normalizeAppPreferences(value: AppPreferences): AppPreferences {
    val modeValid = value.videoWidth in 1..32768 && value.videoHeight in 1..32768 && value.videoFps in 1..240
    return value.copy(
        gridMode = value.gridMode.coerceIn(0, 2),
        frameGuide = value.frameGuide.coerceIn(0, 4),
        defaultCameraId = value.defaultCameraId?.trim()?.takeIf { it.isNotEmpty() && it.length <= 128 },
        videoWidth = if (modeValid) value.videoWidth else 0,
        videoHeight = if (modeValid) value.videoHeight else 0,
        videoFps = if (modeValid) value.videoFps else 0
    )
}
