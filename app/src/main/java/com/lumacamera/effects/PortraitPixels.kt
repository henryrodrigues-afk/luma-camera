package com.lumacamera.effects

/** Small owned inference pixels; no Android or camera buffer retained after preparation. */
object PortraitPixels {
    /** glReadPixels is bottom-left RGBA; the model receives top-left opaque ARGB. */
    fun topLeftArgb(rgba: ByteArray, width: Int, height: Int): IntArray {
        require(width > 0 && height > 0 && width.toLong() * height * 4 == rgba.size.toLong())
        return IntArray(width * height) { index ->
            val offset = ((height - 1 - index / width) * width + index % width) * 4
            (255 shl 24) or ((rgba[offset].toInt() and 255) shl 16) or
                ((rgba[offset + 1].toInt() and 255) shl 8) or (rgba[offset + 2].toInt() and 255)
        }
    }
}
