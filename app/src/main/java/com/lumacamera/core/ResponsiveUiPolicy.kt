package com.lumacamera.core

import kotlin.math.roundToInt

/** Decisions use the usable window, rather than the physical display or a fixed phone model. */
object ResponsiveUiPolicy {
    data class Layout(
        val cameraRail: Boolean,
        val railWidthDp: Int,
        val compactControls: Boolean,
        val stackSliderLabels: Boolean,
        val sideSheet: Boolean,
        val sheetWidthDp: Int,
        val sheetHeightDp: Int
    )

    fun layout(widthDp: Float, heightDp: Float, fontScale: Float = 1f): Layout {
        val width = dimension(widthDp, 360f); val height = dimension(heightDp, 720f)
        val font = if (fontScale.isFinite()) fontScale.coerceIn(.5f, 3f) else 1f
        val rail = width >= 560f && width > height
        val railWidth = if (font > 1.3f) 232 else 184
        val side = width >= 720f && width > height && font <= 1.3f
        val sheetWidth = if (side) minOf(480f, width * .65f) else width
        val sheetHeight = if (height < 600f || font > 1.3f) height else height * .90f
        return Layout(rail, railWidth, height < 600f || font > 1.3f,
            width < 380f || font > 1.2f, side, sheetWidth.roundToInt().coerceAtLeast(1),
            sheetHeight.roundToInt().coerceAtLeast(1))
    }

    /** Keep scrolling within the content after a category rebuild/reset. A different page starts at top. */
    fun restoredScroll(previous: Int, samePage: Boolean): Int = if (samePage) previous.coerceAtLeast(0) else 0

    private fun dimension(value: Float, fallback: Float) = if (value.isFinite() && value > 0f) value else fallback
}
