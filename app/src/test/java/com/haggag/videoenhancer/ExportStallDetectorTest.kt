package com.haggag.videoenhancer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportStallDetectorTest {

    @Test
    fun stalls_when_progress_never_becomes_available_after_startup_timeout() {
        val detector = ExportStallDetector(startedAtMs = 0L, startupTimeoutMs = 30_000L, stallTimeoutMs = 60_000L)

        assertFalse(detector.observe(nowMs = 29_999L, progressPercent = null))
        assertTrue(detector.observe(nowMs = 30_001L, progressPercent = null))
    }

    @Test
    fun stalls_when_available_progress_does_not_advance() {
        val detector = ExportStallDetector(startedAtMs = 0L, startupTimeoutMs = 30_000L, stallTimeoutMs = 60_000L)

        assertFalse(detector.observe(nowMs = 1_000L, progressPercent = 3))
        assertFalse(detector.observe(nowMs = 60_999L, progressPercent = 3))
        assertTrue(detector.observe(nowMs = 61_001L, progressPercent = 3))
    }

    @Test
    fun progress_advance_resets_stall_clock() {
        val detector = ExportStallDetector(startedAtMs = 0L, startupTimeoutMs = 30_000L, stallTimeoutMs = 60_000L)

        assertFalse(detector.observe(nowMs = 1_000L, progressPercent = 2))
        assertFalse(detector.observe(nowMs = 50_000L, progressPercent = 4))
        assertFalse(detector.observe(nowMs = 109_999L, progressPercent = 4))
        assertTrue(detector.observe(nowMs = 110_001L, progressPercent = 4))
    }

    @Test
    fun completion_like_progress_does_not_report_stall() {
        val detector = ExportStallDetector(startedAtMs = 0L, startupTimeoutMs = 30_000L, stallTimeoutMs = 60_000L)

        assertFalse(detector.observe(nowMs = 1_000L, progressPercent = 100))
        assertFalse(detector.observe(nowMs = 100_000L, progressPercent = 100))
    }
}
