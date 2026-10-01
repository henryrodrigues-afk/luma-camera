package com.lumacamera.core

import org.junit.Assert.*
import org.junit.Test

class ResponsiveUiPolicyTest {
    @Test fun portraitKeepsPreviewAndUsableSheetInsideTheWindow() {
        val layout = ResponsiveUiPolicy.layout(360f, 720f)
        assertFalse(layout.cameraRail); assertFalse(layout.sideSheet)
        assertEquals(360, layout.sheetWidthDp); assertEquals(648, layout.sheetHeightDp)
    }

    @Test fun landscapeRailLeavesAUsefulPreviewAndKeepsLargeFontMenusFullWidth() {
        val normal = ResponsiveUiPolicy.layout(760f, 320f)
        assertTrue(normal.cameraRail); assertTrue(normal.sideSheet)
        assertTrue(760 - normal.railWidthDp >= 320); assertEquals(320, normal.sheetHeightDp)
        val large = ResponsiveUiPolicy.layout(760f, 320f, 2f)
        assertTrue(large.cameraRail); assertFalse(large.sideSheet); assertTrue(large.stackSliderLabels)
        assertEquals(760, large.sheetWidthDp)
    }

    @Test fun narrowWindowsDoNotLoseThePreviewToAFixedSidePanel() {
        val narrow = ResponsiveUiPolicy.layout(480f, 280f)
        assertFalse(narrow.cameraRail); assertTrue(narrow.compactControls)
        assertEquals(480, narrow.sheetWidthDp); assertEquals(280, narrow.sheetHeightDp)
    }

    @Test fun invalidValuesAreFiniteAndEveryValidSheetFitsItsWindow() {
        val invalid = ResponsiveUiPolicy.layout(Float.NaN, Float.POSITIVE_INFINITY, Float.NaN)
        assertTrue(invalid.sheetWidthDp > 0 && invalid.sheetHeightDp > 0)
        for (width in listOf(240f, 320f, 360f, 560f, 760f, 1200f))
            for (height in listOf(240f, 320f, 560f, 800f)) for (font in listOf(1f, 1.5f, 2f)) {
                val value = ResponsiveUiPolicy.layout(width, height, font)
                assertTrue(value.sheetWidthDp <= width && value.sheetHeightDp <= height)
                if (value.cameraRail) assertTrue(width - value.railWidthDp >= 320f)
            }
    }

    @Test fun aRebuiltCategoryRetainsScrollButADifferentCategoryStartsAtTop() {
        assertEquals(450, ResponsiveUiPolicy.restoredScroll(450, true))
        assertEquals(0, ResponsiveUiPolicy.restoredScroll(450, false))
        assertEquals(0, ResponsiveUiPolicy.restoredScroll(-3, true))
    }
}
