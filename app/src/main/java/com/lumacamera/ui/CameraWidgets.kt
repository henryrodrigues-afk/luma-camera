package com.lumacamera.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Shared camera colors. Icons are cached vector paths, with no bitmap or blur layers. */
object CameraPalette {
    val background = Color.rgb(8, 12, 16)
    val surface = Color.rgb(20, 27, 35)
    val elevated = Color.rgb(29, 40, 52)
    val border = Color.rgb(43, 57, 71)
    val text = Color.rgb(244, 247, 250)
    val lime = Color.rgb(199, 243, 107)
    val muted = Color.rgb(145, 160, 175)
    val red = Color.rgb(255, 77, 95)
}

enum class CameraIcon {
    SETTINGS, GALLERY, FLIP, BACK, CLOSE, GRID, PLAY, INFO, RESET, MIC, MIC_OFF,
    COLOR, MOTION, EXPOSURE, FOCUS, VIDEO, APP, SHARE, DELETE, CHEVRON
}

/** A 48 dp touch target with a clear vector icon and native, cancelable ripple feedback. */
class CameraIconButton(context: Context, icon: CameraIcon, description: String) : View(context) {
    var icon: CameraIcon = icon
        set(value) { if (field != value) { field = value; buildPaths(); invalidate() } }
    var tintColor: Int = CameraPalette.text
        set(value) { if (field != value) { field = value; invalidate() } }
    var accented: Boolean = false
        set(value) { if (field != value) { field = value; updateBackground(); invalidate() } }
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val outline = Path()
    private val filled = Path()

    init {
        minimumWidth = dp(48); minimumHeight = dp(48)
        isClickable = true; isFocusable = true
        contentDescription = description
        buildPaths()
        updateBackground()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    private fun updateBackground() {
        val base = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (accented) Color.rgb(32, 44, 30) else CameraPalette.surface)
            setStroke(dp(1), if (accented) 0x55C7F36B else CameraPalette.border)
        }
        val mask = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
        background = RippleDrawable(ColorStateList.valueOf(if (accented) 0x35C7F36B else 0x28FFFFFF), base, mask)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(dp(48), widthMeasureSpec), resolveSize(dp(48), heightMeasureSpec))
    }

    private fun buildPaths() {
        outline.rewind()
        filled.rewind()
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) {
            outline.moveTo(x1, y1); outline.lineTo(x2, y2)
        }
        fun path(vararg coordinates: Float) {
            outline.moveTo(coordinates[0], coordinates[1])
            for (i in 2 until coordinates.size step 2) outline.lineTo(coordinates[i], coordinates[i + 1])
        }
        fun circle(x: Float, y: Float, radius: Float) = outline.addCircle(x, y, radius, Path.Direction.CW)
        fun rounded(left: Float, top: Float, right: Float, bottom: Float, radius: Float) =
            outline.addRoundRect(left, top, right, bottom, radius, radius, Path.Direction.CW)
        when (icon) {
            CameraIcon.SETTINGS -> {
                for (i in 0 until 32) {
                    val angle = -Math.PI / 2 + Math.PI * i / 16
                    val radius = if (i % 4 == 1 || i % 4 == 2) 11.5 else 9.2
                    val x = (cos(angle) * radius).toFloat(); val y = (sin(angle) * radius).toFloat()
                    if (i == 0) outline.moveTo(x, y) else outline.lineTo(x, y)
                }
                outline.close(); circle(0f, 0f, 3.5f)
            }
            CameraIcon.GALLERY -> {
                rounded(-11f, -9f, 11f, 10f, 2.5f)
                circle(5f, -3.5f, 1.7f)
                path(-10f, 6f, -4f, -.5f, 2f, 5f, 6f, 1f, 10f, 5f)
                path(-8f, -12f, 8f, -12f)
            }
            CameraIcon.FLIP -> {
                outline.addArc(RectF(-10f, -10f, 10f, 10f), 35f, 145f)
                outline.addArc(RectF(-10f, -10f, 10f, 10f), 215f, 145f)
                path(-6f, 4f, -10f, 0f, -14f, 4f)
                path(6f, -4f, 10f, 0f, 14f, -4f)
                circle(0f, 0f, 3f)
            }
            CameraIcon.RESET -> {
                outline.addArc(RectF(-10f, -10f, 10f, 10f), -55f, 285f)
                path(-11f, -8f, -6f, -8f, -6f, -13f)
            }
            CameraIcon.BACK -> { line(11f, 0f, -10f, 0f); path(-3f, -7f, -10f, 0f, -3f, 7f) }
            CameraIcon.CLOSE -> { line(-8f, -8f, 8f, 8f); line(8f, -8f, -8f, 8f) }
            CameraIcon.GRID -> {
                rounded(-10f, -10f, 10f, 10f, 2f)
                line(-3.3f, -10f, -3.3f, 10f); line(3.3f, -10f, 3.3f, 10f)
                line(-10f, -3.3f, 10f, -3.3f); line(-10f, 3.3f, 10f, 3.3f)
            }
            CameraIcon.PLAY -> {
                filled.moveTo(-5f, -9f); filled.lineTo(10f, 0f); filled.lineTo(-5f, 9f); filled.close()
            }
            CameraIcon.INFO -> {
                circle(0f, 0f, 10.5f); line(0f, -1f, 0f, 6f)
                filled.addCircle(0f, -6f, 1.3f, Path.Direction.CW)
            }
            CameraIcon.MIC, CameraIcon.MIC_OFF -> {
                rounded(-4f, -11f, 4f, 3f, 4f)
                outline.addArc(RectF(-8f, -5f, 8f, 8f), 0f, 180f)
                line(0f, 8f, 0f, 12f); line(-4f, 12f, 4f, 12f)
                if (icon == CameraIcon.MIC_OFF) line(-12f, -12f, 12f, 12f)
            }
            CameraIcon.COLOR -> {
                circle(0f, 0f, 11f)
                outline.moveTo(0f, -11f); outline.cubicTo(-11f, -3f, 11f, 3f, 0f, 11f)
                filled.addCircle(-4f, -4f, 1.5f, Path.Direction.CW)
                filled.addCircle(4f, 4f, 1.5f, Path.Direction.CW)
            }
            CameraIcon.MOTION -> {
                line(-12f, -6f, -3f, -6f); line(-12f, 0f, -6f, 0f); line(-12f, 6f, -3f, 6f)
                rounded(0f, -9f, 12f, 9f, 3f)
                line(4f, -5f, 8f, -5f); line(4f, 5f, 8f, 5f)
            }
            CameraIcon.EXPOSURE -> {
                circle(0f, 0f, 5f)
                for (i in 0 until 8) {
                    val angle = Math.PI * i / 4
                    line((cos(angle) * 8).toFloat(), (sin(angle) * 8).toFloat(),
                        (cos(angle) * 11).toFloat(), (sin(angle) * 11).toFloat())
                }
            }
            CameraIcon.FOCUS -> {
                path(-6f, -11f, -11f, -11f, -11f, -6f); path(6f, -11f, 11f, -11f, 11f, -6f)
                path(-6f, 11f, -11f, 11f, -11f, 6f); path(6f, 11f, 11f, 11f, 11f, 6f)
                circle(0f, 0f, 3.5f)
            }
            CameraIcon.VIDEO -> {
                rounded(-12f, -9f, 5f, 9f, 2.5f)
                path(5f, -4f, 12f, -8f, 12f, 8f, 5f, 4f)
                outline.close()
            }
            CameraIcon.APP -> {
                rounded(-11f, -11f, -3f, -3f, 2f); rounded(3f, -11f, 11f, -3f, 2f)
                rounded(-11f, 3f, -3f, 11f, 2f); rounded(3f, 3f, 11f, 11f, 2f)
            }
            CameraIcon.SHARE -> {
                line(-5f, -1.5f, 5f, -7f); line(-5f, 1.5f, 5f, 7f)
                circle(-8f, 0f, 3f); circle(8f, -9f, 3f); circle(8f, 9f, 3f)
            }
            CameraIcon.DELETE -> {
                line(-10f, -7f, 10f, -7f); path(-4f, -7f, -4f, -11f, 4f, -11f, 4f, -7f)
                path(-8f, -7f, -6f, 11f, 6f, 11f, 8f, -7f)
                line(-3f, -2f, -2f, 6f); line(3f, -2f, 2f, 6f)
            }
            CameraIcon.CHEVRON -> path(-3f, -7f, 4f, 0f, -3f, 7f)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val save = canvas.save()
        canvas.translate(width / 2f, height / 2f)
        val nominalSize = if (isClickable) 48f else 28f
        val scale = min(resources.displayMetrics.density, min(width, height) / nominalSize)
        canvas.scale(scale, scale)
        ink.color = if (accented) CameraPalette.lime else tintColor
        ink.alpha = if (isEnabled) 255 else 80
        ink.style = Paint.Style.STROKE; ink.strokeWidth = 2f
        canvas.drawPath(outline, ink)
        ink.style = Paint.Style.FILL
        canvas.drawPath(filled, ink)
        canvas.restoreToCount(save)
    }

    override fun onDetachedFromWindow() {
        background?.jumpToCurrentState()
        super.onDetachedFromWindow()
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.Button"
        info.isSelected = accented
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}

/** White shutter ring; the red stop square makes the recording state clear even without color. */
class RecordShutterView(context: Context) : View(context) {
    var recording: Boolean = false
        set(value) { if (field != value) { field = value; updateDescription(); invalidate() } }
    var busy: Boolean = false
        set(value) { if (field != value) { field = value; updateDescription(); invalidate() } }
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val busyArc = RectF()

    init {
        minimumWidth = dp(88); minimumHeight = dp(88)
        isClickable = true; isFocusable = true
        updateDescription()
        val mask = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
        // Foreground keeps the press ripple visible over the filled shutter, and cancels with the gesture.
        foreground = RippleDrawable(ColorStateList.valueOf(0x30FFFFFF), null, mask)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    private fun updateDescription() {
        contentDescription = when {
            busy && recording -> "Finalizando gravação"
            busy -> "Preparando câmera"
            recording -> "Parar gravação"
            else -> "Gravar vídeo"
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(dp(88), widthMeasureSpec), resolveSize(dp(88), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f; val cy = height / 2f
        val radius = min(width, height) * .40f
        val alpha = if ((isEnabled || recording) && !busy) 255 else 110
        ink.style = Paint.Style.STROKE
        ink.strokeWidth = resources.displayMetrics.density * 3f
        ink.color = CameraPalette.text; ink.alpha = alpha
        canvas.drawCircle(cx, cy, radius, ink)
        ink.style = Paint.Style.FILL
        if (recording) {
            ink.color = CameraPalette.red; ink.alpha = alpha
            val half = radius * .43f
            canvas.drawRoundRect(cx - half, cy - half, cx + half, cy + half,
                radius * .12f, radius * .12f, ink)
        } else {
            ink.color = CameraPalette.text; ink.alpha = alpha
            canvas.drawCircle(cx, cy, radius * .79f, ink)
            ink.color = if (busy) CameraPalette.background else CameraPalette.red
            ink.alpha = alpha
            if (busy) {
                val arcRadius = radius * .29f
                busyArc.set(cx - arcRadius, cy - arcRadius, cx + arcRadius, cy + arcRadius)
                ink.style = Paint.Style.STROKE
                ink.strokeWidth = resources.displayMetrics.density * 2.5f
                canvas.drawArc(busyArc, -90f, 265f, false, ink)
            } else canvas.drawCircle(cx, cy, radius * .26f, ink)
        }
    }

    override fun onDetachedFromWindow() {
        foreground?.jumpToCurrentState()
        super.onDetachedFromWindow()
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.Button"
        info.isSelected = recording
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
