package com.haggag.videoenhancer

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoMathTest {

    @Test
    fun progress_is_safe_and_clamped() {
        assertEquals(0, VideoMath.progressPercent(0, 0))
        assertEquals(0, VideoMath.progressPercent(-2, 10))
        assertEquals(50, VideoMath.progressPercent(5, 10))
        assertEquals(100, VideoMath.progressPercent(15, 10))
    }

    @Test
    fun presentation_timestamp_preserves_frame_cadence() {
        assertEquals(0L, VideoMath.presentationTimeUs(0, 30.0))
        assertEquals(1_000_000L, VideoMath.presentationTimeUs(30, 30.0))
        assertEquals(500_000L, VideoMath.presentationTimeUs(15, 30.0))
    }

    @Test
    fun one_second_at_30fps_has_30_frames() {
        assertEquals(30, VideoMath.frameCount(durationMs = 1_000L, fps = 30.0))
    }

    @Test
    fun bitrate_scales_with_resolution_and_frame_rate() {
        assertEquals(7_500_000, VideoMath.recommendedBitrate(1920, 1080, 30.0))
        assertEquals(2_000_000, VideoMath.recommendedBitrate(320, 240, 24.0))
    }
}
