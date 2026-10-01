package com.lumacamera.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import com.lumacamera.effects.MonitorScopes
import kotlin.math.sqrt

/** Compact display-only scope panels. Snapshot/geometry updates occur at most 4 Hz, never per frame. */
class ScopeOverlay(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCD080C10.toInt() }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x408A9CAD; strokeWidth = density * .5f }
    private val ink = Paint().apply { color = 0xFFC7F36B.toInt() }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF4F7FA.toInt(); textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 9f, resources.displayMetrics)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val caption = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFA6B3BF.toInt(); textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 8f, resources.displayMetrics)
    }
    private var waveform = false; private var parade = false; private var vector = false
    private var snapshot: MonitorScopes? = null
    private var waveformPeak = 1; private var paradePeak = 1; private var vectorPeak = 1
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO; setWillNotDraw(false) }

    fun configure(waveform: Boolean, rgbParade: Boolean, vectorscope: Boolean) {
        if (this.waveform == waveform && parade == rgbParade && vector == vectorscope) return
        this.waveform = waveform; parade = rgbParade; vector = vectorscope; invalidate()
    }
    fun update(value: MonitorScopes) {
        snapshot = value
        waveformPeak = value.waveform.maxOrNull()?.coerceAtLeast(1) ?: 1
        paradePeak = value.rgbParade.maxOrNull()?.coerceAtLeast(1) ?: 1
        vectorPeak = value.vectorscope.maxOrNull()?.coerceAtLeast(1) ?: 1
        invalidate()
    }
    fun clear() { if (snapshot == null) return; snapshot = null; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val count = (if (waveform) 1 else 0) + (if (parade) 1 else 0) + (if (vector) 1 else 0)
        if (count == 0 || width <= 0 || height <= 0) return
        val gap = 4f * density
        val panelWidth = (width - gap * (count - 1)) / count
        var index = 0
        if (waveform) { drawPanel(canvas, index * (panelWidth + gap), panelWidth, 0); index++ }
        if (parade) { drawPanel(canvas, index * (panelWidth + gap), panelWidth, 1); index++ }
        if (vector) drawPanel(canvas, index * (panelWidth + gap), panelWidth, 2)
    }

    private fun drawPanel(canvas: Canvas, left: Float, panelWidth: Float, kind: Int) {
        val save = canvas.save()
        canvas.clipRect(left, 0f, left + panelWidth, height.toFloat())
        val pad = 6f * density
        canvas.drawRoundRect(left, 0f, left + panelWidth, height.toFloat(), 8f * density, 8f * density, background)
        val title = when (kind) { 0 -> "WAVEFORM"; 1 -> "RGB PARADE"; else -> "VECTORSCOPE" }
        canvas.drawText(title, left + pad, 13f * density, label)
        val top = 21f * density; val bottom = height - 17f * density
        if (bottom <= top || panelWidth < pad * 3) { canvas.restoreToCount(save); return }
        val plotLeft = left + pad; val plotWidth = panelWidth - pad * 2
        for (line in 0..4) {
            val y = top + (bottom - top) * line / 4f
            canvas.drawLine(plotLeft, y, plotLeft + plotWidth, y, grid)
        }
        val data = snapshot
        if (data != null) when (kind) {
            0 -> densityMap(canvas, data.waveform, 0, data.columns, data.levels, plotLeft, top, plotWidth,
                bottom - top, 0xFFC7F36B.toInt(), waveformPeak)
            1 -> {
                val slice = data.columns * data.levels
                for (channel in 0..2) densityMap(canvas, data.rgbParade, channel * slice, data.columns, data.levels,
                    plotLeft + plotWidth * channel / 3f, top, plotWidth / 3f, bottom - top,
                    when (channel) { 0 -> 0xFFFF7078.toInt(); 1 -> 0xFF73DB97.toInt(); else -> 0xFF79A7FF.toInt() }, paradePeak)
            }
            else -> {
                val size = minOf(plotWidth, bottom - top)
                val x = plotLeft + (plotWidth - size) / 2f; val y = top + (bottom - top - size) / 2f
                grid.style = Paint.Style.STROKE
                canvas.drawCircle(x + size / 2f, y + size / 2f, size / 2f, grid)
                grid.style = Paint.Style.FILL
                canvas.drawLine(x + size / 2f, y, x + size / 2f, y + size, grid)
                canvas.drawLine(x, y + size / 2f, x + size, y + size / 2f, grid)
                densityMap(canvas, data.vectorscope, 0, data.levels, data.levels, x, y, size, size,
                    0xFFE2B8FF.toInt(), vectorPeak)
            }
        }
        canvas.drawText(if (data == null) "Preparando sinal" else if (kind == 2) "Cb × Cr · gravado" else "Gravado · 0–100%", plotLeft,
            height - 5f * density, caption)
        canvas.restoreToCount(save)
    }

    private fun densityMap(canvas: Canvas, values: IntArray, offset: Int, columns: Int, levels: Int,
        left: Float, top: Float, plotWidth: Float, plotHeight: Float, color: Int, peak: Int) {
        if (columns <= 0 || levels <= 0 || values.size < offset + columns * levels) return
        ink.color = color
        val cellWidth = plotWidth / columns; val cellHeight = plotHeight / levels
        for (level in 0 until levels) for (column in 0 until columns) {
            val count = values[offset + level * columns + column]
            if (count == 0) continue
            ink.alpha = (70f + 185f * sqrt(count.toFloat() / peak)).toInt().coerceIn(0, 255)
            val x = left + column * cellWidth; val y = top + (levels - 1 - level) * cellHeight
            canvas.drawRect(x, y, x + cellWidth.coerceAtLeast(density * .6f),
                y + cellHeight.coerceAtLeast(density * .6f), ink)
        }
        ink.alpha = 255
    }
}
