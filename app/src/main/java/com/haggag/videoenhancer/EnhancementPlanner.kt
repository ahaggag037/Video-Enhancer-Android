package com.haggag.videoenhancer

enum class EngineMode {
    AUTO,
    LOCAL_AI_VDSR,
    FAST_GPU
}

enum class ResolutionTarget(val landscapeWidth: Int, val landscapeHeight: Int) {
    ORIGINAL(0, 0),
    FHD(1920, 1080),
    QHD(2560, 1440),
    UHD(3840, 2160)
}

data class EnhancementPlan(
    val useAi: Boolean,
    val effectiveEngine: EngineMode,
    val aiWidth: Int,
    val aiHeight: Int,
    val outputWidth: Int,
    val outputHeight: Int
)

object EnhancementPlanner {
    fun plan(
        sourceWidth: Int,
        sourceHeight: Int,
        target: ResolutionTarget,
        requestedEngine: EngineMode
    ): EnhancementPlan {
        require(sourceWidth > 0 && sourceHeight > 0)

        val portrait = sourceHeight > sourceWidth
        val targetPair = when (target) {
            ResolutionTarget.ORIGINAL -> sourceWidth to sourceHeight
            else -> if (portrait) {
                target.landscapeHeight to target.landscapeWidth
            } else {
                target.landscapeWidth to target.landscapeHeight
            }
        }

        val outputWidth = targetPair.first
        val outputHeight = targetPair.second
        val sourceLong = maxOf(sourceWidth, sourceHeight)
        val outputLong = maxOf(outputWidth, outputHeight)
        val isUpscale = outputLong > sourceLong

        val effectiveEngine = when (requestedEngine) {
            EngineMode.FAST_GPU -> EngineMode.FAST_GPU
            EngineMode.LOCAL_AI_VDSR -> EngineMode.LOCAL_AI_VDSR
            EngineMode.AUTO -> {
                val lowResolution = minOf(sourceWidth, sourceHeight) <= 720
                if (lowResolution && isUpscale) EngineMode.LOCAL_AI_VDSR else EngineMode.FAST_GPU
            }
        }

        val useAi = effectiveEngine == EngineMode.LOCAL_AI_VDSR
        if (!useAi) {
            return EnhancementPlan(
                useAi = false,
                effectiveEngine = effectiveEngine,
                aiWidth = sourceWidth,
                aiHeight = sourceHeight,
                outputWidth = outputWidth,
                outputHeight = outputHeight
            )
        }

        val workingLongEdge = when (requestedEngine) {
            EngineMode.AUTO -> minOf(outputLong, 1280)
            EngineMode.LOCAL_AI_VDSR -> minOf(outputLong, 1920)
            EngineMode.FAST_GPU -> sourceLong
        }
        val workingShortEdge = when {
            portrait -> ((workingLongEdge.toDouble() * sourceWidth / sourceHeight).toInt()).coerceAtLeast(2)
            else -> ((workingLongEdge.toDouble() * sourceHeight / sourceWidth).toInt()).coerceAtLeast(2)
        }

        val rawAiWidth = if (portrait) workingShortEdge else workingLongEdge
        val rawAiHeight = if (portrait) workingLongEdge else workingShortEdge
        val aiWidth = rawAiWidth.coerceAtMost(outputWidth).even()
        val aiHeight = rawAiHeight.coerceAtMost(outputHeight).even()

        return EnhancementPlan(
            useAi = true,
            effectiveEngine = effectiveEngine,
            aiWidth = aiWidth,
            aiHeight = aiHeight,
            outputWidth = outputWidth,
            outputHeight = outputHeight
        )
    }

    private fun Int.even(): Int = if (this % 2 == 0) this else this - 1
}
