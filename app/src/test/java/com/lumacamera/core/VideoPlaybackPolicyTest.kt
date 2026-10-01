package com.lumacamera.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPlaybackPolicyTest {
    @Test fun completionCannotKeepScreenAwakeWhenMedia3RetainsPlayingIntent() {
        assertTrue(VideoPlaybackPolicy.shouldKeepScreenOn(true, true, true, true, false, false, false))
        assertFalse(VideoPlaybackPolicy.shouldKeepScreenOn(true, true, true, true, true, false, false))
    }

    @Test fun inactiveSuppressedMissingAndFailedPlayersReleaseScreenWakeRequest() {
        assertFalse(VideoPlaybackPolicy.shouldKeepScreenOn(true, false, true, true, false, false, false))
        assertFalse(VideoPlaybackPolicy.shouldKeepScreenOn(false, true, true, true, false, false, false))
        assertFalse(VideoPlaybackPolicy.shouldKeepScreenOn(true, true, false, true, false, false, false))
        assertFalse(VideoPlaybackPolicy.shouldKeepScreenOn(true, true, true, false, false, false, false))
        assertFalse(VideoPlaybackPolicy.shouldKeepScreenOn(true, true, true, true, false, true, false))
        assertFalse(VideoPlaybackPolicy.shouldKeepScreenOn(true, true, true, true, false, false, true))
    }
    @Test fun backgroundPausePreservesPlayingIntentAndPosition() {
        val foreground = VideoPlaybackPolicy.snapshot(12_345, 60_000, true, true, PlaybackCheckpoint(), false)
        val background = VideoPlaybackPolicy.snapshot(12_345, 60_000, false, false, foreground, false)
        assertEquals(PlaybackCheckpoint(12_345, true), background)
        assertEquals(background, VideoPlaybackPolicy.restore(background, 60_000))
    }

    @Test fun userPauseRemainsPausedAcrossStopAndReopen() {
        val foreground = VideoPlaybackPolicy.snapshot(32_100, 60_000, false, true, PlaybackCheckpoint(), false)
        assertEquals(PlaybackCheckpoint(32_100, false),
            VideoPlaybackPolicy.snapshot(32_100, 60_000, false, false, foreground, false))
    }

    @Test fun completionDoesNotReopenAtEndOrStartAutomatically() {
        for (resumed in listOf(true, false)) assertEquals(PlaybackCheckpoint(0, false),
            VideoPlaybackPolicy.snapshot(60_000, 60_000, true, resumed, PlaybackCheckpoint(59_000, true), true))
    }

    @Test fun unknownDurationKeepsLongPositionsWithoutIntegerOverflow() {
        val longPosition = 3_000_000_000L
        for (duration in listOf(null, -9223372036854775807L, -1L)) {
            assertEquals(PlaybackCheckpoint(longPosition, true), VideoPlaybackPolicy.restore(PlaybackCheckpoint(longPosition), duration))
        }
    }

    @Test fun negativeAndOutOfDurationPositionsAreClamped() {
        assertEquals(PlaybackCheckpoint(0L, false), VideoPlaybackPolicy.restore(PlaybackCheckpoint(Long.MIN_VALUE, false), 10_000))
        assertEquals(PlaybackCheckpoint(10_000L, true), VideoPlaybackPolicy.restore(PlaybackCheckpoint(Long.MAX_VALUE), 10_000))
        assertEquals(PlaybackCheckpoint(0L, true), VideoPlaybackPolicy.restore(PlaybackCheckpoint(50), 0))
    }

    @Test fun hostStartAloneCannotStartAudioAndBackgroundCannotResumeIt() {
        assertFalse(VideoPlaybackPolicy.shouldPlay(true, false, true))
        assertFalse(VideoPlaybackPolicy.shouldPlay(false, true, true))
        assertFalse(VideoPlaybackPolicy.shouldPlay(true, true, false))
        assertTrue(VideoPlaybackPolicy.shouldPlay(true, true, true))
    }

    @Test fun explicitReplayStartsAtZeroButOrdinaryPlayKeepsPausedPosition() {
        assertEquals(PlaybackCheckpoint(0L, true), VideoPlaybackPolicy.play(PlaybackCheckpoint(60_000, false), true, 60_000))
        assertEquals(PlaybackCheckpoint(0L, true), VideoPlaybackPolicy.play(PlaybackCheckpoint(90_000, false), false, 60_000))
        assertEquals(PlaybackCheckpoint(23_450, true), VideoPlaybackPolicy.play(PlaybackCheckpoint(23_450, false), false, 60_000))
    }

    @Test fun seekRequiresKnownPositiveDuration() {
        for (duration in listOf(null, -1L, 0L)) assertNull(VideoPlaybackPolicy.seekPosition(5_000, duration))
        assertNull(VideoPlaybackPolicy.seekPosition(5_000, 60_000L, 0))
        assertEquals(0, VideoPlaybackPolicy.seekProgress(20_000, null))
    }

    @Test fun seekTimelineSupportsLongClipsAndExactEndpointsWithoutIntOverflow() {
        val duration = 3_000_000_000L
        assertEquals(0L, VideoPlaybackPolicy.seekPosition(-100, duration))
        assertEquals(duration, VideoPlaybackPolicy.seekPosition(100_000, duration))
        assertEquals(1_500_000_000L, VideoPlaybackPolicy.seekPosition(5_000, duration))
        assertEquals(5_000, VideoPlaybackPolicy.seekProgress(1_500_000_000L, duration))
        assertEquals(10_000, VideoPlaybackPolicy.seekProgress(Long.MAX_VALUE, duration))
    }

    @Test fun skipClampsBoundsAndSaturatesPositiveOverflow() {
        assertEquals(0L, VideoPlaybackPolicy.skip(2_000, -5_000, 60_000))
        assertEquals(60_000L, VideoPlaybackPolicy.skip(59_000, 5_000, 60_000))
        assertEquals(17_000L, VideoPlaybackPolicy.skip(12_000, 5_000, null))
        assertEquals(Long.MAX_VALUE, VideoPlaybackPolicy.skip(Long.MAX_VALUE - 5, 50, null))
        assertEquals(0L, VideoPlaybackPolicy.skip(5, Long.MIN_VALUE, null))
    }

    @Test fun portraitLandscapeAndUnappliedRotationKeepDisplayAspect() {
        assertEquals(16f / 9f, VideoPlaybackPolicy.displayAspectRatio(1920, 1080, 1f, 0), .00001f)
        assertEquals(9f / 16f, VideoPlaybackPolicy.displayAspectRatio(1080, 1920, 1f, 0), .00001f)
        assertEquals(9f / 16f, VideoPlaybackPolicy.displayAspectRatio(1920, 1080, 1f, 90), .00001f)
        assertEquals(9f / 16f, VideoPlaybackPolicy.displayAspectRatio(1920, 1080, 1f, -90), .00001f)
        assertEquals(16f / 9f, VideoPlaybackPolicy.displayAspectRatio(0, 1080, Float.NaN, 0), .00001f)
    }

    @Test fun watchdogNeverRecoversPausedOrBackgroundPlayback() {
        assertNull(VideoPlaybackPolicy.stall(false, true, true, 50_000, 50_000, 50_000, true, true))
        assertNull(VideoPlaybackPolicy.stall(true, false, true, 50_000, 50_000, 50_000, true, true))
        assertNull(VideoPlaybackPolicy.stall(true, true, false, null, 50_000, 50_000, true, true))
    }

    @Test fun watchdogDistinguishesBufferingClockAndDecodedFrameStalls() {
        assertNull(VideoPlaybackPolicy.stall(true, true, false, 11_999, 0, 0, true, true))
        assertEquals(VideoPlaybackPolicy.Stall.BUFFERING,
            VideoPlaybackPolicy.stall(true, true, false, 12_000, 0, 0, true, true))
        assertEquals(VideoPlaybackPolicy.Stall.POSITION,
            VideoPlaybackPolicy.stall(true, true, true, null, 10_000, 0, true, true))
        assertEquals(VideoPlaybackPolicy.Stall.VIDEO_FRAMES,
            VideoPlaybackPolicy.stall(true, true, true, null, 500, 10_000, true, true))
        assertNull(VideoPlaybackPolicy.stall(true, true, true, null, 500, 10_000, false, true))
        assertNull(VideoPlaybackPolicy.stall(true, true, true, null, 500, 10_000, true, false))
    }

    @Test fun internalFallbackChangesStrategyAndStopsAfterNativePlayer() {
        val surface = VideoPlaybackPolicy.Backend.MEDIA3_SURFACE
        val texture = VideoPlaybackPolicy.Backend.MEDIA3_TEXTURE
        val native = VideoPlaybackPolicy.Backend.ANDROID_NATIVE
        assertEquals(texture, VideoPlaybackPolicy.nextBackend(surface))
        assertEquals(native, VideoPlaybackPolicy.nextBackend(texture))
        assertNull(VideoPlaybackPolicy.nextBackend(native))
        for (mode in VideoPlaybackPolicy.Backend.values()) assertNull(VideoPlaybackPolicy.nextBackend(mode, false))
    }

    @Test fun watchdogRecoversMissingSurfaceAndReadyWithoutFirstFrame() {
        assertNull(VideoPlaybackPolicy.stall(true, true, true, null, 0, 0, true, false, 7_999, false))
        assertEquals(VideoPlaybackPolicy.Stall.SURFACE,
            VideoPlaybackPolicy.stall(true, true, true, null, 0, 0, true, false, 8_000, false))
        assertNull(VideoPlaybackPolicy.stall(true, true, true, null, 0, 5_999, true, true, 6_000, false))
        assertEquals(VideoPlaybackPolicy.Stall.FIRST_FRAME,
            VideoPlaybackPolicy.stall(true, true, true, null, 0, 6_000, true, true, 6_000, false))
    }

    @Test fun nativeSeekWaitsForCompletionButCannotHangIndefinitely() {
        assertNull(VideoPlaybackPolicy.stall(true, true, false, null, 40_000, 40_000, true, true, 40_000, true, 11_999))
        assertEquals(VideoPlaybackPolicy.Stall.SEEK,
            VideoPlaybackPolicy.stall(true, true, false, null, 40_000, 40_000, true, true, 40_000, true, 12_000))
        assertNull(VideoPlaybackPolicy.stall(false, true, false, null, 40_000, 40_000, true, true, 40_000, true, 50_000))
    }

    @Test fun displayAndSeekUseTheSameBoundedTimeline() {
        assertEquals(0L, VideoPlaybackPolicy.displayPosition(-9223372036854775807L, 20_000L))
        assertEquals(20_000L, VideoPlaybackPolicy.displayPosition(900_000_000L, 20_000L))
        assertEquals(2_000L, VideoPlaybackPolicy.displayPosition(2_000L, null))
        assertEquals(10_000, VideoPlaybackPolicy.seekProgress(VideoPlaybackPolicy.displayPosition(Long.MAX_VALUE, 20_000L), 20_000L))
    }

    @Test fun nativeErrorUsesLastHealthyProgressWhilePendingSeeksRetainTheirTarget() {
        assertEquals(60_000L, VideoPlaybackPolicy.failurePosition(PlaybackCheckpoint(0L), 60_000L, true, false, 90_000L))
        assertEquals(75_000L, VideoPlaybackPolicy.failurePosition(PlaybackCheckpoint(75_000L), 60_000L, true, true, 90_000L))
        assertEquals(30_000L, VideoPlaybackPolicy.failurePosition(PlaybackCheckpoint(30_000L), 0L, false, false, null))
        assertEquals(90_000L, VideoPlaybackPolicy.failurePosition(PlaybackCheckpoint(0L), Long.MAX_VALUE, true, false, 90_000L))
    }

    @Test fun nativeFocusGainAndSurfaceRecreationCannotResumeDuringTimelineDrag() {
        // Gain clears suppression and recreating a surface makes output ready; neither ends the drag.
        assertFalse(VideoPlaybackPolicy.shouldRunNative(true, true, true, true, false, true, false))
        assertFalse(VideoPlaybackPolicy.shouldRunNative(true, true, true, false, true, true, false))
        assertTrue(VideoPlaybackPolicy.shouldRunNative(true, true, true, false, false, true, false))
        assertFalse(VideoPlaybackPolicy.shouldRunNative(true, false, true, false, false, true, false))
        assertFalse(VideoPlaybackPolicy.shouldRunNative(true, true, false, false, false, true, false))
        assertFalse(VideoPlaybackPolicy.shouldRunNative(true, true, true, false, false, false, false))
        assertFalse(VideoPlaybackPolicy.shouldRunNative(true, true, true, false, false, true, true))
    }
}
