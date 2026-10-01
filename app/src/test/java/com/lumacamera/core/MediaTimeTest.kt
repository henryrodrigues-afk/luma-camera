package com.lumacamera.core

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaTimeTest {
    @Test fun unavailableMetadataDoesNotPresentAZeroLengthRecording() {
        for (value in listOf(null, 0L, -1L, Long.MIN_VALUE + 1L)) assertEquals("—:—", MediaTime.format(value))
    }

    @Test fun elapsedPlaybackCanExplicitlyStartAtZero() {
        assertEquals("0:00", MediaTime.format(0L, allowZero = true))
        assertEquals("—:—", MediaTime.format(-1L, allowZero = true))
    }

    @Test fun timeUnitsUseMillisecondsAndRetainHourBoundaries() {
        assertEquals("0:10", MediaTime.format(10_500L))
        assertEquals("59:59", MediaTime.format(3_599_999L))
        assertEquals("1:00:00", MediaTime.format(3_600_000L))
        assertEquals("25:01:02", MediaTime.format(90_062_000L))
    }
}
