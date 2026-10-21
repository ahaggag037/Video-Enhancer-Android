package com.haggag.videoenhancer

import org.junit.Assert.assertEquals
import org.junit.Test

class EnhancementPlannerTest {

    @Test
    fun smart_uses_ai_for_low_resolution_upscale() {
        val plan = EnhancementPlanner.plan(
            sourceWidth = 426,
            sourceHeight = 240,
            outputChoice = EnhancementPlanner.OutputChoice.FULL_HD,
            engineChoice = EnhancementPlanner.EngineChoice.SMART
        )

        assertEquals(EnhancementPlanner.Path.AI_DETAIL, plan.path)
        assertEquals(1920, plan.targetWidth)
        assertEquals(1080, plan.targetHeight)
    }

    @Test
    fun smart_uses_fast_path_when_source_is_already_large() {
        val plan = EnhancementPlanner.plan(
            sourceWidth = 1920,
            sourceHeight = 1080,
            outputChoice = EnhancementPlanner.OutputChoice.FULL_HD,
            engineChoice = EnhancementPlanner.EngineChoice.SMART
        )

        assertEquals(EnhancementPlanner.Path.FAST_GPU, plan.path)
    }

    @Test
    fun explicit_ai_stays_ai_even_for_large_source() {
        val plan = EnhancementPlanner.plan(
            sourceWidth = 1920,
            sourceHeight = 1080,
            outputChoice = EnhancementPlanner.OutputChoice.ORIGINAL,
            engineChoice = EnhancementPlanner.EngineChoice.AI_DETAIL
        )

        assertEquals(EnhancementPlanner.Path.AI_DETAIL, plan.path)
    }

    @Test
    fun portrait_target_preserves_aspect_ratio() {
        val size = EnhancementPlanner.targetSize(
            sourceWidth = 720,
            sourceHeight = 1280,
            outputChoice = EnhancementPlanner.OutputChoice.FULL_HD
        )

        assertEquals(1080, size.width)
        assertEquals(1920, size.height)
    }

    @Test
    fun target_dimensions_are_even_for_yuv420() {
        val size = EnhancementPlanner.targetSize(
            sourceWidth = 853,
            sourceHeight = 480,
            outputChoice = EnhancementPlanner.OutputChoice.QHD
        )

        assertEquals(2560, size.width)
        assertEquals(0, size.height % 2)
    }
}
