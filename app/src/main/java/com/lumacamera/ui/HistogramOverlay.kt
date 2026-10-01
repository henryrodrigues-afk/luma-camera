package com.lumacamera.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import com.lumacamera.core.HistogramDisplayPolicy
import com.lumacamera.effects.MonitorHistogram
import java.util.Locale

/** Small cached HUD, updated by the monitor callback at most four times per second. */
class HistogramOverlay(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB8080C10.toInt() }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x802B3947.toInt(); strokeWidth = density; style = Paint.Style.STROKE
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x91C7F36B.toInt() }
    private val curve = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFC7F36B.toInt(); style = Paint.Style.STROKE; strokeWidth = density
    }
    private val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x6691A0AF; strokeWidth = density * .5f }
    private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x91F4F7FA.toInt(); strokeWidth = density }
    private val clipped = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF6B78.toInt(); strokeWidth = density * 2f }
    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF4F7FA.toInt()
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 9f, resources.displayMetrics)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val labels = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF91A0AF.toInt()
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 8f, resources.displayMetrics)
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val bars = Path()
    private val outline = Path()
    private var data = HistogramDisplayPolicy.snapshot(IntArray(0))
    private var clippingLabel = "Preparando sinal"
    private var plotLeft = 0f; private var plotRight = 0f
    private var plotTop = 0f; private var plotBottom = 0f
    private val titleMetrics = title.fontMetrics
    private val labelMetrics = labels.fontMetrics
    private var titleBaseline = 0f; private var axisBaseline = 0f; private var captionBaseline = 0f
    private var showAxes = true

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setWillNotDraw(false)
    }

    fun update(histogram: MonitorHistogram) = update(histogram.bins, histogram.shadowClip, histogram.highlightClip, histogram.mean)

    /** Call on the UI thread. Copies bins once per callback; no allocations occur in onDraw. */
    fun update(bins: IntArray, shadowClipFraction: Float = 0f, highlightClipFraction: Float = 0f, mean: Float = .5f) {
        val next = HistogramDisplayPolicy.snapshot(bins, shadowClipFraction, highlightClipFraction, mean)
        if (data.bins.contentEquals(next.bins) && data.shadowClip == next.shadowClip &&
            data.highlightClip == next.highlightClip && data.mean == next.mean) return
        data = next
        clippingLabel = if (next.hasData) "Sombra ${percentage(next.shadowClip)} · Luz ${percentage(next.highlightClip)}" else "Preparando sinal"
        rebuildGeometry(); invalidate()
    }

    fun clear() = update(IntArray(0))

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh); rebuildGeometry()
    }

    private fun rebuildGeometry() {
        val pad = 8f * density
        plotLeft = pad; plotRight = (width - pad).coerceAtLeast(pad)
        titleBaseline = 5f * density - titleMetrics.ascent
        captionBaseline = height - 4f * density - labelMetrics.descent
        axisBaseline = captionBaseline - (labelMetrics.descent - labelMetrics.ascent) - 2f * density
        plotTop = titleBaseline + titleMetrics.descent + 4f * density
        showAxes = axisBaseline + labelMetrics.ascent - 3f * density >= plotTop + 8f * density
        plotBottom = ((if (showAxes) axisBaseline else captionBaseline) + labelMetrics.ascent - 3f * density).coerceAtLeast(plotTop)
        bars.rewind(); outline.rewind()
        if (!data.hasData || plotRight <= plotLeft || plotBottom <= plotTop) return
        val step = (plotRight - plotLeft) / HistogramDisplayPolicy.BIN_COUNT
        bars.moveTo(plotLeft, plotBottom)
        for (index in data.bins.indices) {
            val x = plotLeft + (index + .5f) * step
            val y = plotBottom - data.heightAt(index) * (plotBottom - plotTop)
            bars.lineTo(x, y)
            if (index == 0) outline.moveTo(x, y) else outline.lineTo(x, y)
        }
        bars.lineTo(plotRight, plotBottom); bars.close()
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        val radius = 9f * density
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, panel)
        canvas.drawRoundRect(.5f * density, .5f * density, width - .5f * density,
            height - .5f * density, radius, radius, border)
        canvas.drawText("HISTOGRAMA", plotLeft, titleBaseline, title)
        canvas.drawLine(plotLeft, plotBottom, plotRight, plotBottom, axis)
        if (data.hasData) {
            canvas.drawPath(bars, fill); canvas.drawPath(outline, curve)
            val meanX = plotLeft + data.mean * (plotRight - plotLeft)
            canvas.drawLine(meanX, plotBottom - 4f * density, meanX, plotBottom, marker)
            if (data.shadowClip >= .005f) canvas.drawLine(plotLeft, plotTop, plotLeft, plotTop + 7f * density, clipped)
            if (data.highlightClip >= .005f) canvas.drawLine(plotRight, plotTop, plotRight, plotTop + 7f * density, clipped)
        }
        if (showAxes) {
            labels.textAlign = Paint.Align.LEFT
            canvas.drawText("0%", plotLeft, axisBaseline, labels)
            labels.textAlign = Paint.Align.RIGHT
            canvas.drawText("100%", plotRight, axisBaseline, labels)
        }
        labels.textAlign = Paint.Align.CENTER
        canvas.drawText(clippingLabel, width * .5f, captionBaseline, labels)
    }

    private fun percentage(value: Float): String = if (value < .0005f) "0%" else
        String.format(Locale.getDefault(), "%.1f%%", value * 100f)
}
