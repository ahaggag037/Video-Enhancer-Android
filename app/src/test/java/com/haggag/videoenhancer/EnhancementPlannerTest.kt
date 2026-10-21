package com.haggag.videoenhancer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnhancementPlannerTest {

    @Test
    fun autoUsesVdsrForLowResolutionUpscaleAndCapsAiWorkAt720p() {
        val plan = EnhancementPlanner.plan(
            sourceWidth = 426,
            sourceHeight = 240,
            target = ResolutionTarget.FHD,
            requestedEngine = EngineMode.AUTO
        )

        assertTrue(plan.useAi)
        assertEquals(EngineMode.LOCAL_AI_VDSR, plan.effectiveEngine)
        assertEquals(1280, plan.aiWidth)
        assertEquals(720, plan.aiHeight)
        assertEquals(1920, plan.outputWidth)
        assertEquals(1080, plan.outputHeight)
    }

    @Test
    fun autoStaysFastForAlready1080pSource() {
        val plan = EnhancementPlanner.plan(
            sourceWidth = 1920,
            sourceHeight = 1080,
            target = ResolutionTarget.FHD,
            requestedEngine = EngineMode.AUTO
        )

        assertFalse(plan.useAi)
        assertEquals(EngineMode.FAST_GPU, plan.effectiveEngine)
        assertEquals(1920, plan.outputWidth)
        assertEquals(1080, plan.outputHeight)
    }

    @Test
    fun fastGpuNeverInvokesAi() {
        val plan = EnhancementPlanner.plan(
            sourceWidth = 640,
            sourceHeight = 360,
            target = ResolutionTarget.QHD,
            requestedEngine = EngineMode.FAST_GPU
        )

        assertFalse(plan.useAi)
        assertEquals(EngineMode.FAST_GPU, plan.effectiveEngine)
        assertEquals(2560, plan.outputWidth)
        assertEquals(1440, plan.outputHeight)
    }

    @Test
    fun forcedLocalAiUsesUpTo1080pWorkingResolution() {
        val plan = EnhancementPlanner.plan(
            sourceWidth = 1280,
            sourceHeight = 720,
            target = ResolutionTarget.UHD,
            requestedEngine = EngineMode.LOCAL_AI_VDSR
        )

        assertTrue(plan.useAi)
        assertEquals(1920, plan.aiWidth)
        assertEquals(1080, plan.aiHeight)
        assertEquals(3840, plan.outputWidth)
        assertEquals(2160, plan.outputHeight)
    }

    @Test
    fun portraitKeepsPortraitOrientation() {
        val plan = EnhancementPlanner.plan(
            sourceWidth = 360,
            sourceHeight = 640,
            target = ResolutionTarget.FHD,
            requestedEngine = EngineMode.AUTO
        )

        assertTrue(plan.useAi)
        assertEquals(720, plan.aiWidth)
        assertEquals(1280, plan.aiHeight)
        assertEquals(1080, plan.outputWidth)
        assertEquals(1920, plan.outputHeight)
    }
}
