package com.lumacamera.effects

import org.junit.Assert.*
import org.junit.Test

class PortraitPixelsTest {
    @Test fun ownedRgbaRowsAndChannelsBecomeUprightArgbWithoutMirroring() {
        val rgba = byteArrayOf(10, 20, 30, 0, 40, 50, 60, 0, 70, 80, 90, 0, 100, 110, 120, 0)
        val result = PortraitPixels.topLeftArgb(rgba, 2, 2)
        assertArrayEquals(intArrayOf(0xff46505a.toInt(), 0xff646e78.toInt(), 0xff0a141e.toInt(), 0xff28323c.toInt()), result)
        rgba.fill(0)
        assertEquals(0xff46505a.toInt(), result[0])
    }

    @Test(expected = IllegalArgumentException::class)
    fun truncatedCameraPixelsAreRejectedBeforeAnyBitmapAccess() {
        PortraitPixels.topLeftArgb(ByteArray(15), 2, 2)
    }
}
