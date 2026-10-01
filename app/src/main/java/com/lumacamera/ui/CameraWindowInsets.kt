package com.lumacamera.ui

import android.app.Dialog
import android.os.Build
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import com.lumacamera.core.ResponsiveUiPolicy
import kotlin.math.max
import kotlin.math.roundToInt
import java.util.WeakHashMap

/** Shared native-window handling for camera and library; preserves the root's own gutters. */
object CameraWindowInsets {
    private data class Padding(val left: Int, val top: Int, val right: Int, val bottom: Int)
    private val originalPadding = WeakHashMap<View, Padding>()
    @Suppress("DEPRECATION")
    fun bind(window: Window, root: View) {
        val base = originalPadding.getOrPut(root) { Padding(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom) }
        val left = base.left; val top = base.top
        val right = base.right; val bottom = base.bottom
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        else window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        root.setOnApplyWindowInsetsListener { view, insets ->
            val insetLeft: Int; val insetTop: Int; val insetRight: Int; val insetBottom: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                insetLeft = bars.left; insetTop = bars.top; insetRight = bars.right; insetBottom = bars.bottom
            } else {
                val cutout = insets.displayCutout
                insetLeft = max(insets.systemWindowInsetLeft, cutout?.safeInsetLeft ?: 0)
                insetTop = max(insets.systemWindowInsetTop, cutout?.safeInsetTop ?: 0)
                insetRight = max(insets.systemWindowInsetRight, cutout?.safeInsetRight ?: 0)
                insetBottom = max(insets.systemWindowInsetBottom, cutout?.safeInsetBottom ?: 0)
            }
            val desiredLeft = left + insetLeft; val desiredTop = top + insetTop
            val desiredRight = right + insetRight; val desiredBottom = bottom + insetBottom
            if (view.paddingLeft != desiredLeft || view.paddingTop != desiredTop ||
                view.paddingRight != desiredRight || view.paddingBottom != desiredBottom)
                view.setPadding(desiredLeft, desiredTop, desiredRight, desiredBottom)
            if (Build.VERSION.SDK_INT >= 30) WindowInsets.CONSUMED
            else insets.consumeSystemWindowInsets().consumeDisplayCutout()
        }
        root.requestApplyInsets()
    }

    fun availableSize(window: Window): Size {
        val content = window.decorView.findViewById<ViewGroup>(android.R.id.content)
        val root = content?.getChildAt(0)
        val metrics = window.context.resources.displayMetrics
        val configuration = window.context.resources.configuration
        val width = root?.let { it.width - it.paddingLeft - it.paddingRight } ?: 0
        val height = root?.let { it.height - it.paddingTop - it.paddingBottom } ?: 0
        return Size(if (width > 0) width else (configuration.screenWidthDp * metrics.density).roundToInt().coerceAtLeast(1),
            if (height > 0) height else (configuration.screenHeightDp * metrics.density).roundToInt().coerceAtLeast(1))
    }

    fun sizeDialog(dialog: Dialog, ownerWindow: Window) {
        val size = availableSize(ownerWindow)
        val resources = ownerWindow.context.resources
        val density = resources.displayMetrics.density
        val policy = ResponsiveUiPolicy.layout(size.width / density, size.height / density, resources.configuration.fontScale)
        dialog.window?.apply {
            setGravity(if (policy.sideSheet) Gravity.END or Gravity.CENTER_VERTICAL else Gravity.BOTTOM)
            setLayout((policy.sheetWidthDp * density).roundToInt().coerceAtMost(size.width),
                (policy.sheetHeightDp * density).roundToInt().coerceAtMost(size.height))
        }
    }
}
