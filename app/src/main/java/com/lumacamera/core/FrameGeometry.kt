package com.lumacamera.core

import kotlin.math.roundToInt
import kotlin.math.abs

/** One geometry contract for GLES display/encoder copies, UI sizing, and touch coordinates. */
object FrameGeometry {
    data class Dimensions(val width: Int, val height: Int)
    /** GLES viewport coordinates have their origin at the bottom-left. */
    data class Viewport(val x: Int, val y: Int, val width: Int, val height: Int)

    /** SENSOR_ORIENTATION and this result describe a clockwise rotation of unmirrored pixels. */
    fun relativeRotation(sensorOrientation: Int, displayRotation: Int, front: Boolean): Int =
        normalizeRotation(sensorOrientation + if (front) displayRotation else -displayRotation)

    fun orientedSize(sourceWidth: Int, sourceHeight: Int, rotation: Int): Dimensions {
        require(sourceWidth > 0 && sourceHeight > 0)
        return if (normalizeRotation(rotation) % 180 == 0) Dimensions(sourceWidth, sourceHeight)
        else Dimensions(sourceHeight, sourceWidth)
    }

    /** Fit the entire oriented image, preserving aspect ratio and adding bars when necessary. */
    fun fitSize(sourceWidth: Int, sourceHeight: Int, rotation: Int,
        containerWidth: Int, containerHeight: Int): Dimensions {
        require(containerWidth > 0 && containerHeight > 0)
        val oriented = orientedSize(sourceWidth, sourceHeight, rotation)
        val scale = minOf(containerWidth.toDouble() / oriented.width, containerHeight.toDouble() / oriented.height)
        return Dimensions((oriented.width * scale).roundToInt().coerceIn(1, containerWidth),
            (oriented.height * scale).roundToInt().coerceIn(1, containerHeight))
    }

    fun fitViewport(sourceWidth: Int, sourceHeight: Int, rotation: Int,
        surfaceWidth: Int, surfaceHeight: Int): Viewport {
        val fitted = fitSize(sourceWidth, sourceHeight, rotation, surfaceWidth, surfaceHeight)
        return Viewport((surfaceWidth - fitted.width) / 2, (surfaceHeight - fitted.height) / 2,
            fitted.width, fitted.height)
    }

    /** Inverse output-to-source mapping. Mirroring is applied in upright display space only. */
    fun sourceGlAt(outputX: Float, outputY: Float, rotation: Int, mirrored: Boolean): Pair<Float, Float> {
        val x = if (mirrored) 1f - outputX else outputX
        return when (normalizeRotation(rotation)) {
            90 -> (1f - outputY) to x
            180 -> (1f - x) to (1f - outputY)
            270 -> outputY to (1f - x)
            else -> x to outputY
        }
    }

    /** Forward companion used only to draw a tracked subject on the fitted, mirrored preview. */
    fun outputGlAt(sourceX: Float, sourceY: Float, rotation: Int, mirrored: Boolean): Pair<Float, Float>? {
        if (!sourceX.isFinite() || !sourceY.isFinite() || sourceX !in 0f..1f || sourceY !in 0f..1f) return null
        val unmirrored = when (normalizeRotation(rotation)) {
            90 -> sourceY to (1f - sourceX)
            180 -> (1f - sourceX) to (1f - sourceY)
            270 -> (1f - sourceY) to sourceX
            else -> sourceX to sourceY
        }
        return (if (mirrored) 1f - unmirrored.first else unmirrored.first) to unmirrored.second
    }

    /** Column-major mat3 suitable for glUniformMatrix3fv; never combines the SurfaceTexture transform. */
    fun uvTransform(rotation: Int, mirrored: Boolean): FloatArray {
        val origin = sourceGlAt(0f, 0f, rotation, mirrored)
        val horizontal = sourceGlAt(1f, 0f, rotation, mirrored)
        val vertical = sourceGlAt(0f, 1f, rotation, mirrored)
        return floatArrayOf(horizontal.first - origin.first, horizontal.second - origin.second, 0f,
            vertical.first - origin.first, vertical.second - origin.second, 0f,
            origin.first, origin.second, 1f)
    }

    /**
     * Camera2 can embed sensor rotation and front mirroring in SurfaceTexture's matrix.
     * Strip the observed orthogonal orientation before the separate preview/encoder rotation,
     * while retaining the sampled crop rectangle and the native top-left-to-GLES Y flip.
     * A producer with no camera rotation (plain Y flip) remains unchanged.
     * Returns false for a non-orthogonal transform; the original matrix is retained safely.
     */
    fun canonicalCameraTransform(surfaceTransform: FloatArray, output: FloatArray): Boolean {
        require(surfaceTransform.size >= 16 && output.size >= 16)
        val a = surfaceTransform[0]; val b = surfaceTransform[4]
        val c = surfaceTransform[1]; val d = surfaceTransform[5]
        val tx = surfaceTransform[12]; val ty = surfaceTransform[13]
        val epsilon = .0001f
        val diagonal = abs(b) < epsilon && abs(c) < epsilon && abs(a) > epsilon && abs(d) > epsilon
        val swappedAxes = abs(a) < epsilon && abs(d) < epsilon && abs(b) > epsilon && abs(c) > epsilon
        if ((!diagonal && !swappedAxes) || !a.isFinite() || !b.isFinite() || !c.isFinite() ||
            !d.isFinite() || !tx.isFinite() || !ty.isFinite() ||
            abs(surfaceTransform[3]) > epsilon || abs(surfaceTransform[7]) > epsilon ||
            abs(surfaceTransform[15] - 1f) > epsilon) {
            surfaceTransform.copyInto(output, endIndex = 16)
            return false
        }
        // Bounds of the four transformed unit-square corners are the valid native-buffer crop.
        val minX = tx + minOf(0f, a) + minOf(0f, b)
        val maxX = tx + maxOf(0f, a) + maxOf(0f, b)
        val minY = ty + minOf(0f, c) + minOf(0f, d)
        val maxY = ty + maxOf(0f, c) + maxOf(0f, d)
        output.fill(0f, 0, 16)
        output[0] = maxX - minX
        output[5] = minY - maxY
        output[10] = 1f
        output[12] = minX; output[13] = maxY; output[15] = 1f
        return true
    }

    /** UI pixel point to source GLES coordinates. A touch on a letterbox bar has no camera target. */
    fun sourcePoint(viewX: Float, viewY: Float, sourceWidth: Int, sourceHeight: Int,
        rotation: Int, mirrored: Boolean, viewWidth: Int, viewHeight: Int): Pair<Float, Float>? {
        if (!viewX.isFinite() || !viewY.isFinite() || viewWidth <= 0 || viewHeight <= 0) return null
        val viewport = fitViewport(sourceWidth, sourceHeight, rotation, viewWidth, viewHeight)
        val top = viewHeight - viewport.y - viewport.height
        if (viewX < viewport.x || viewY < top || viewX > viewport.x + viewport.width ||
            viewY > top + viewport.height) return null
        val u = (viewX - viewport.x) / viewport.width
        val v = 1f - (viewY - top) / viewport.height
        return sourceGlAt(u, v, rotation, mirrored)
    }

    private fun normalizeRotation(rotation: Int): Int = (((rotation % 360) + 360) % 360).also {
        require(it % 90 == 0) { "Rotation must be a multiple of 90 degrees" }
    }
}
