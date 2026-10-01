package com.lumacamera.camera

import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics as C
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodecList
import android.media.MediaRecorder
import android.os.Build
import android.util.Range
import android.util.Size
import com.lumacamera.core.CapturePolicy
import com.lumacamera.core.EncoderLimits
import com.lumacamera.core.VideoMode
import org.json.JSONArray
import org.json.JSONObject

data class CameraInfo(
    val id: String, val label: String, val characteristics: C,
    val modes: List<VideoMode>, val previewSizes: List<Size>,
    val encoderName: String?, val manualSensor: Boolean, val manualFocus: Boolean,
    val flatCurve: Boolean, val isoRange: Range<Int>?, val exposureRange: Range<Long>?,
    val minFocus: Float, val evRange: Range<Int>, val evStep: Float,
    val awbModes: List<Int>, val afModes: List<Int>, val fpsRanges: List<Range<Int>>
) {
    val maxDigitalZoom: Float get() = if (characteristics.availableCaptureRequestKeys
        .contains(CaptureRequest.SCALER_CROP_REGION))
        (characteristics[C.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM] ?: 1f).takeIf { it.isFinite() }?.coerceIn(1f, 100f) ?: 1f
        else 1f
    val tapFocusAvailable: Boolean get() = afModes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) &&
        characteristics.availableCaptureRequestKeys.containsAll(listOf(CaptureRequest.CONTROL_AF_MODE,
            CaptureRequest.CONTROL_AF_TRIGGER))
    val aeLockAvailable: Boolean = characteristics[C.CONTROL_AE_LOCK_AVAILABLE] == true &&
        characteristics.availableCaptureRequestKeys.contains(CaptureRequest.CONTROL_AE_LOCK)
    val awbLockAvailable: Boolean = characteristics[C.CONTROL_AWB_LOCK_AVAILABLE] == true &&
        characteristics.availableCaptureRequestKeys.contains(CaptureRequest.CONTROL_AWB_LOCK)
    val front: Boolean get() = characteristics[C.LENS_FACING] == C.LENS_FACING_FRONT
    val orientation: Int get() = characteristics[C.SENSOR_ORIENTATION] ?: 0
    fun defaultMode(): VideoMode? = modes.firstOrNull { it.width == 1920 && it.height == 1080 && it.fps == 30 }
        ?: modes.firstOrNull { it.width == 1280 && it.height == 720 && it.fps == 30 }
        ?: modes.firstOrNull()

    fun previewFor(mode: VideoMode): Size = previewSizes.filter {
        kotlin.math.abs(it.width.toDouble() / it.height - mode.width.toDouble() / mode.height) < 0.025
    }.let { matched ->
        matched.filter { it.width <= 1920 && it.height <= 1080 }.maxByOrNull { it.width * it.height }
            ?: matched.minByOrNull { it.width * it.height }
            ?: previewSizes.minByOrNull { it.width * it.height }
            ?: Size(mode.width, mode.height)
    }
}

class CameraCatalog(private val manager: CameraManager) {
    val scanErrors = mutableListOf<String>()
    fun scan(): List<CameraInfo> {
        scanErrors.clear()
        return manager.cameraIdList.mapNotNull { id -> try {
        val c = manager.getCameraCharacteristics(id)
        val caps = c[C.REQUEST_AVAILABLE_CAPABILITIES]?.toList().orEmpty()
        val requestKeys = c.availableCaptureRequestKeys.toSet()
        val map = c[C.SCALER_STREAM_CONFIGURATION_MAP]
        val fps = c[C.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES]?.toList().orEmpty()
        val codec = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && it.supportedTypes.any { type -> type.equals("video/avc", true) } }
            .sortedBy { if (it.isHardwareAccelerated) 0 else 1 }
            .firstOrNull { candidate -> runCatching { candidate.getCapabilitiesForType("video/avc").colorFormats.contains(
                android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) }.getOrDefault(false) }
        val videoCaps = codec?.getCapabilitiesForType("video/avc")?.videoCapabilities
        val sizes = map?.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
        val limits = videoCaps?.let {
            EncoderLimits(it.supportedWidths.lower, it.supportedWidths.upper,
                it.supportedHeights.lower, it.supportedHeights.upper,
                it.widthAlignment, it.heightAlignment, it.bitrateRange.upper, it.bitrateRange.lower)
        }
        val modes = if (limits == null) emptyList() else CapturePolicy.compatibleModes(
            sizes.map { it.width to it.height }, fps.map { it.lower..it.upper }, limits
        ).filter { mode ->
            val size = Size(mode.width, mode.height)
            val minDuration = map?.getOutputMinFrameDuration(SurfaceTexture::class.java, size) ?: 0L
            mode.width <= 3840 && mode.height <= 2160 &&
                (minDuration == 0L || minDuration <= CapturePolicy.frameDurationNs(mode.fps) * 1.01) &&
                videoCaps?.areSizeAndRateSupported(mode.width, mode.height, mode.fps.toDouble()) == true
        }
        val iso = c[C.SENSOR_INFO_SENSITIVITY_RANGE]
        val exposure = c[C.SENSOR_INFO_EXPOSURE_TIME_RANGE]
        val af = c[C.CONTROL_AF_AVAILABLE_MODES]?.toList().orEmpty()
        val minFocus = c[C.LENS_INFO_MINIMUM_FOCUS_DISTANCE] ?: 0f
        val toneModes = c[C.TONEMAP_AVAILABLE_TONE_MAP_MODES]?.toList().orEmpty()
        val side = when (c[C.LENS_FACING]) { C.LENS_FACING_FRONT -> "Frontal"; C.LENS_FACING_BACK -> "Traseira"; else -> "Externa" }
        CameraInfo(id, "$side · $id", c, modes,
            map?.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty(), codec?.name,
            caps.contains(C.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) && iso != null && exposure != null &&
                requestKeys.containsAll(listOf(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.SENSOR_SENSITIVITY,
                    CaptureRequest.SENSOR_EXPOSURE_TIME)),
            minFocus > 0 && af.contains(C.CONTROL_AF_MODE_OFF) && requestKeys.contains(CaptureRequest.LENS_FOCUS_DISTANCE),
            caps.contains(C.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING) &&
                toneModes.contains(C.TONEMAP_MODE_CONTRAST_CURVE) && (c[C.TONEMAP_MAX_CURVE_POINTS] ?: 0) >= 8,
            iso, exposure, minFocus, c[C.CONTROL_AE_COMPENSATION_RANGE] ?: Range(0, 0),
            c[C.CONTROL_AE_COMPENSATION_STEP]?.toFloat() ?: 0f,
            c[C.CONTROL_AWB_AVAILABLE_MODES]?.toList().orEmpty(), af, fps)
        } catch (error: Exception) {
            scanErrors.add("Câmera $id: ${error.message}")
            null
        } }
    }

    fun report(cameras: List<CameraInfo>): String = JSONObject().apply {
        put("schemaVersion", 6)
        put("processing", "GLES2 local: cor, nitidez, Flat, LumaLog SDR, desfoque oval, pessoas por ML Kit, objetos por toque/MagicTouch v1 e rastro. Efeitos na prévia e no MP4; assistência Log somente na prévia.")
        put("portraitModel", "ML Kit segmentation-selfie 16.0.0-beta6 bundled; 256/512 px; até 5/8 análises/s; desempenho não medido neste aparelho")
        put("videoGeometry", "Proporção FIT; orientação física na GPU quando encoder permite, ou somente metadata MP4 nas dimensões originais. Espelho frontal somente na prévia. Encoder reavaliado nas dimensões finais.")
        put("editingProfile", "LumaLog v2: curva SDR + compressão reversível de crominância; LUT por versão e força. Media3 player 1.4.1 local.")
        put("manufacturer", Build.MANUFACTURER); put("model", Build.MODEL)
        put("androidApi", Build.VERSION.SDK_INT)
        put("scanErrors", JSONArray(scanErrors))
        put("warning", "Capacidades anunciadas; combinações e desempenho ainda exigem teste de gravação. Sem identificador pessoal.")
        put("cameras", JSONArray().apply { cameras.forEach { camera ->
            put(JSONObject().apply {
                put("id", camera.id); put("label", camera.label)
                put("hardwareLevel", camera.characteristics[C.INFO_SUPPORTED_HARDWARE_LEVEL])
                put("capabilities", JSONArray(camera.characteristics[C.REQUEST_AVAILABLE_CAPABILITIES]?.toList().orEmpty()))
                put("manualSensor", camera.manualSensor); put("manualFocus", camera.manualFocus)
                put("maxDigitalZoom", camera.maxDigitalZoom); put("tapFocusAvailable", camera.tapFocusAvailable)
                put("aeLockAvailable", camera.aeLockAvailable); put("awbLockAvailable", camera.awbLockAvailable)
                put("flatCurve", camera.flatCurve); put("isoRange", camera.isoRange?.toString())
                put("exposureNs", camera.exposureRange?.toString()); put("encoderCandidate", camera.encoderName)
                put("note", "MediaRecorder escolhe seu encoder; este cruzamento é uma triagem, não garantia de sessão.")
                put("bitrateQuality", "Escala 0,5–2x do alvo; faixa AVC renegociada nas dimensões finais antes de prepare. Bitrate configurado não mede o VBR do arquivo.")
                put("fpsRanges", JSONArray(camera.fpsRanges.map { it.toString() }))
                put("awbModes", JSONArray(camera.awbModes))
                put("afModes", JSONArray(camera.afModes))
                put("maxAfRegions", camera.characteristics[C.CONTROL_MAX_REGIONS_AF] ?: 0)
                put("maxAeRegions", camera.characteristics[C.CONTROL_MAX_REGIONS_AE] ?: 0)
                put("oisModes", JSONArray(camera.characteristics[C.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION]?.toList().orEmpty()))
                put("digitalStabilizationModes", JSONArray(camera.characteristics[C.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES]?.toList().orEmpty()))
                val jpeg = camera.characteristics[C.SCALER_STREAM_CONFIGURATION_MAP]?.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width.toLong() * it.height }
                put("maxJpegSize", jpeg?.toString())
                put("videoCandidates", JSONArray().apply { camera.modes.forEach { mode ->
                    put(JSONObject().put("width", mode.width).put("height", mode.height)
                        .put("fpsTarget", mode.fps).put("bitrate", mode.bitrate))
                } })
            })
        } })
    }.toString(2)
}
