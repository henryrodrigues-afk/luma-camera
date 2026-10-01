package com.lumacamera.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.View

/** Tap target feedback belongs to the preview; it never claims a successful lens lock. */
class FocusReticle(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = CameraPalette.lime; style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2f
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val contrast = Paint(paint).apply { color = 0x99080C10.toInt(); strokeWidth = resources.displayMetrics.density * 4f }
    private val corners = Path()
    private var x = .5f
    private var y = .5f
    private var visibleUntil = 0L
    private val hide = Runnable { visibleUntil = 0L; invalidate() }

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun showAt(normalizedX: Float, normalizedY: Float) {
        if (!normalizedX.isFinite() || !normalizedY.isFinite()) return
        x = normalizedX.coerceIn(0f, 1f); y = normalizedY.coerceIn(0f, 1f)
        visibleUntil = SystemClock.elapsedRealtime() + 1800
        removeCallbacks(hide)
        invalidate()
        postDelayed(hide, 1800L)
    }
    fun clear() { removeCallbacks(hide); visibleUntil = 0L; invalidate() }

    override fun onDraw(canvas: Canvas) {
        if (SystemClock.elapsedRealtime() >= visibleUntil) return
        val centerX = x * width; val centerY = y * height
        val density = resources.displayMetrics.density
        val radius = density * 23f
        val arm = density * 9f
        corners.rewind()
        corners.moveTo(centerX - radius, centerY - radius + arm)
        corners.lineTo(centerX - radius, centerY - radius); corners.lineTo(centerX - radius + arm, centerY - radius)
        corners.moveTo(centerX + radius - arm, centerY - radius)
        corners.lineTo(centerX + radius, centerY - radius); corners.lineTo(centerX + radius, centerY - radius + arm)
        corners.moveTo(centerX - radius, centerY + radius - arm)
        corners.lineTo(centerX - radius, centerY + radius); corners.lineTo(centerX - radius + arm, centerY + radius)
        corners.moveTo(centerX + radius - arm, centerY + radius)
        corners.lineTo(centerX + radius, centerY + radius); corners.lineTo(centerX + radius, centerY + radius - arm)
        canvas.drawPath(corners, contrast)
        canvas.drawPath(corners, paint)
        canvas.drawCircle(centerX, centerY, density * 1.5f, paint)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(hide)
        visibleUntil = 0L
        super.onDetachedFromWindow()
    }
}
