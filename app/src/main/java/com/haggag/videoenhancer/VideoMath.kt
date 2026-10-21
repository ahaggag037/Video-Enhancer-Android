package com.haggag.videoenhancer

import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.roundToLong

object VideoMath {
    fun progressPercent(current: Int, total: Int): Int {
        if (total <= 0) return 0
        return ((current.coerceIn(0, total).toDouble() / total.toDouble()) * 100.0)
            .roundToInt()
            .coerceIn(0, 100)
    }

    fun presentationTimeUs(frameIndex: Int, fps: Double): Long {
        require(frameIndex >= 0) { "Frame index must be non-negative" }
        require(fps > 0.0 && fps.isFinite()) { "FPS must be positive" }
        return (frameIndex.toDouble() * 1_000_000.0 / fps).roundToLong()
    }

    fun frameCount(durationMs: Long, fps: Double): Int {
        if (durationMs <= 0L || fps <= 0.0 || !fps.isFinite()) return 0
        return ceil(durationMs.toDouble() * fps / 1_000.0).toInt().coerceAtLeast(1)
    }

    fun recommendedBitrate(width: Int, height: Int, fps: Double): Int {
        require(width > 0 && height > 0) { "Video dimensions must be positive" }
        val safeFps = fps.coerceIn(1.0, 120.0)
        val raw = width.toDouble() * height.toDouble() * safeFps * 0.12
        val rounded = (raw / 500_000.0).roundToInt() * 500_000
        return rounded.coerceIn(2_000_000, 50_000_000)
    }
}
