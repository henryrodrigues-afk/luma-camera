package com.lumacamera.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.TypedValue
import android.view.View
import kotlin.math.log10

/** Relative microphone peak, refreshed by recorder callbacks. No AudioRecord or animation loop. */
class AudioMeterView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private val labelSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 9f, resources.displayMetrics)
    private val labelMetrics = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = labelSize }.fontMetrics
    private var visibleLevel = 0f
    private var peak = 0f
    private var label = "MIC · PICO"

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        contentDescription = "Pico relativo do microfone durante a gravação"
    }

    fun update(value: Float) {
        val valid = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
        // This is a visual scale, not a calibrated dBFS measurement of the encoded AAC track.
        val level = if (valid < .0001f) 0f else ((log10(valid) * 20f + 60f) / 60f).coerceIn(0f, 1f)
        val next = if (level >= visibleLevel) level else visibleLevel * .55f + level * .45f
        val clipped = valid >= .98f
        val nextLabel = if (clipped) "MIC · PICO ALTO" else "MIC · PICO"
        if (kotlin.math.abs(next - visibleLevel) < .003f && peak == valid && label == nextLabel) return
        visibleLevel = next; peak = valid; label = nextLabel
        invalidate()
    }

    fun clear() {
        if (visibleLevel == 0f && peak == 0f && label == "MIC · PICO") return
        visibleLevel = 0f; peak = 0f; label = "MIC · PICO"; invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val padding = 8f * density
        paint.color = 0xC0080C10.toInt()
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 8f * density, 8f * density, paint)
        paint.color = if (peak >= .98f) CameraPalette.red else CameraPalette.text
        paint.textSize = labelSize
        val baseline = 5f * density - labelMetrics.ascent
        canvas.drawText(label, padding, baseline, paint)
        val count = 16
        val gap = 2f * density
        val barWidth = ((width - padding * 2 - gap * (count - 1)) / count).coerceAtLeast(1f)
        val top = baseline + labelMetrics.descent + 4f * density
        val bottom = height - 6f * density
        if (bottom <= top) return
        for (index in 0 until count) {
            paint.color = if ((index + .5f) / count > visibleLevel) CameraPalette.elevated
                else if (index >= 14) CameraPalette.red else if (index >= 11) 0xFFFFCD6B.toInt() else CameraPalette.lime
            val left = padding + index * (barWidth + gap)
            canvas.drawRoundRect(left, top, left + barWidth, bottom, density, density, paint)
        }
    }
}
