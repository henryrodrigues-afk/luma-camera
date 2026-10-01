package com.lumacamera.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import com.lumacamera.core.CompositionPolicy
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class GridOverlay(context: Context) : View(context) {
    var showGrid: Boolean = true
        set(value) { if (field != value) { field = value; invalidate() } }
    var gridMode: Int = 0
        set(value) {
            val mode = CompositionPolicy.normalizeGridMode(value)
            if (field != mode) { field = mode; rebuildGeometry(); invalidate() }
        }
    var frameGuide: Int = 0
        set(value) {
            val mode = CompositionPolicy.normalizeFrameGuide(value)
            if (field != mode) { field = mode; rebuildGeometry(); invalidate() }
        }
    var showSafeArea: Boolean = false
        set(value) { if (field != value) { field = value; invalidate() } }
    var showLevel: Boolean = false
        set(value) { if (field != value) { field = value; invalidate() } }
    /** Horizon angle already expressed in the upright display; NaN means unavailable. */
    var rollDegrees: Float = Float.NaN
        set(value) {
            val normalized = CompositionPolicy.normalizeRoll(value)
            val next = if (normalized.isFinite()) (normalized * 10f).roundToInt() / 10f else Float.NaN
            if (field.toBits() != next.toBits()) {
                field = next
                levelLabel = if (next.isFinite()) String.format(Locale.getDefault(), "%.1f°", abs(next)) else "Nível indisponível"
                if (showLevel) invalidate()
            }
        }
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x3DF4F7FA; strokeWidth = density * .75f; style = Paint.Style.STROKE
    }
    private val contrast = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x80080C10.toInt(); strokeWidth = density * 2f; style = Paint.Style.STROKE
    }
    private val guidePaint = Paint(paint).apply { color = 0xB3F4F7FA.toInt(); strokeWidth = density }
    private val safePaint = Paint(paint).apply {
        color = 0x80C7F36B.toInt(); pathEffect = DashPathEffect(floatArrayOf(5f * density, 4f * density), 0f)
    }
    private val shade = Paint().apply { color = 0x38080C10 }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xD9F4F7FA.toInt()
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10f, resources.displayMetrics)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setShadowLayer(density, 0f, density, 0xCC080C10.toInt())
    }
    private val levelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = density * 2f; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val gridPath = Path()
    private val guidePath = Path()
    private val safePath = Path()
    private var bounds = CompositionPolicy.Bounds(0f, 0f, 0f, 0f)
    private var safeBounds = bounds
    private var guideLabel = ""
    private var levelLabel = "Nível indisponível"
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh); rebuildGeometry()
    }

    private fun rebuildGeometry() {
        bounds = CompositionPolicy.guideBounds(width.toFloat(), height.toFloat(), frameGuide)
        safeBounds = CompositionPolicy.safeBounds(bounds)
        gridPath.rewind(); guidePath.rewind(); safePath.rewind()
        for (fraction in CompositionPolicy.gridFractions(gridMode)) {
            val x = bounds.left + bounds.width * fraction; val y = bounds.top + bounds.height * fraction
            gridPath.moveTo(x, bounds.top); gridPath.lineTo(x, bounds.bottom)
            gridPath.moveTo(bounds.left, y); gridPath.lineTo(bounds.right, y)
        }
        guidePath.addRect(bounds.left, bounds.top, bounds.right, bounds.bottom, Path.Direction.CW)
        safePath.addRect(safeBounds.left, safeBounds.top, safeBounds.right, safeBounds.bottom, Path.Direction.CW)
        guideLabel = when (frameGuide) { 1 -> "1:1"; 2 -> "9:16"; 3 -> "16:9"; 4 -> "2.39:1"; else -> "" }
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        if (frameGuide != 0) {
            canvas.drawRect(0f, 0f, width.toFloat(), bounds.top, shade)
            canvas.drawRect(0f, bounds.bottom, width.toFloat(), height.toFloat(), shade)
            canvas.drawRect(0f, bounds.top, bounds.left, bounds.bottom, shade)
            canvas.drawRect(bounds.right, bounds.top, width.toFloat(), bounds.bottom, shade)
            canvas.drawPath(guidePath, contrast); canvas.drawPath(guidePath, guidePaint)
            textPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(guideLabel, bounds.right - 8f * density, bounds.bottom - 8f * density, textPaint)
        }
        if (showGrid) { canvas.drawPath(gridPath, contrast); canvas.drawPath(gridPath, paint) }
        if (showSafeArea) {
            canvas.drawPath(safePath, safePaint)
            textPaint.textAlign = Paint.Align.LEFT
            canvas.drawText("90%", safeBounds.left + 5f * density, safeBounds.top + 13f * density, textPaint)
        }
        if (showLevel) {
            val cx = bounds.centerX; val cy = bounds.centerY
            val half = minOf(62f * density, bounds.width * .26f)
            levelPaint.color = if (CompositionPolicy.isLevel(rollDegrees)) 0xFFC7F36B.toInt() else 0xD9F4F7FA.toInt()
            canvas.drawLine(cx - half - 16f * density, cy, cx - half - 4f * density, cy, contrast)
            canvas.drawLine(cx + half + 4f * density, cy, cx + half + 16f * density, cy, contrast)
            canvas.drawLine(cx - half - 16f * density, cy, cx - half - 4f * density, cy, guidePaint)
            canvas.drawLine(cx + half + 4f * density, cy, cx + half + 16f * density, cy, guidePaint)
            if (rollDegrees.isFinite()) {
                val saved = canvas.save()
                canvas.rotate(rollDegrees, cx, cy)
                canvas.drawLine(cx - half, cy, cx + half, cy, contrast)
                canvas.drawLine(cx - half, cy, cx + half, cy, levelPaint)
                canvas.drawCircle(cx, cy, 4f * density, levelPaint)
                canvas.restoreToCount(saved)
            }
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(levelLabel, cx, cy + 26f * density, textPaint)
        }
    }
}
