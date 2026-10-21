package com.haggag.videoenhancer

import kotlin.math.min
import kotlin.math.roundToInt

object EnhancementPlanner {

    /**
     * Smart mode is deliberately bounded. Real-ESRGAN is genuine learned x4 SR, but applying
     * tiled neural inference to tens of thousands of video frames is not an interactive mobile
     * workload. This budget keeps Smart fast while leaving AI_DETAIL available when the user
     * explicitly wants maximum neural recovery.
     */
    private const val SMART_AI_TILE_INFERENCE_BUDGET = 3_600L

    enum class EngineChoice {
        SMART,
        AI_DETAIL,
        FAST_GPU
    }

    enum class Path {
        AI_DETAIL,
        FAST_GPU
    }

    enum class OutputChoice {
        ORIGINAL,
        FULL_HD,
        QHD,
        UHD_4K
    }

    data class Size(val width: Int, val height: Int)

    data class Plan(
        val path: Path,
        val targetWidth: Int,
        val targetHeight: Int,
        val requestedEngine: EngineChoice
    )

    fun plan(
        sourceWidth: Int,
        sourceHeight: Int,
        frameCount: Int,
        outputChoice: OutputChoice,
        engineChoice: EngineChoice
    ): Plan {
        require(sourceWidth > 0 && sourceHeight > 0) { "Source dimensions must be positive" }
        require(frameCount > 0) { "Frame count must be positive" }

        val target = targetSize(sourceWidth, sourceHeight, outputChoice)
        val sourcePixels = sourceWidth.toLong() * sourceHeight
        val targetPixels = target.width.toLong() * target.height
        val sourceLongEdge = maxOf(sourceWidth, sourceHeight)

        val path = when (engineChoice) {
            EngineChoice.AI_DETAIL -> Path.AI_DETAIL
            EngineChoice.FAST_GPU -> Path.FAST_GPU
            EngineChoice.SMART -> {
                val meaningfulUpscale = targetPixels > (sourcePixels * 5L / 4L)
                val tilesPerFrame = AiTileGrid.tiles(sourceWidth, sourceHeight).size.toLong()
                val estimatedNeuralTiles = tilesPerFrame * frameCount.toLong()
                val mobileAiWorkload = estimatedNeuralTiles <= SMART_AI_TILE_INFERENCE_BUDGET

                if (meaningfulUpscale && sourceLongEdge <= 1280 && mobileAiWorkload) {
                    Path.AI_DETAIL
                } else {
                    Path.FAST_GPU
                }
            }
        }

        return Plan(
            path = path,
            targetWidth = target.width,
            targetHeight = target.height,
            requestedEngine = engineChoice
        )
    }

    fun targetSize(
        sourceWidth: Int,
        sourceHeight: Int,
        outputChoice: OutputChoice
    ): Size {
        require(sourceWidth > 0 && sourceHeight > 0) { "Source dimensions must be positive" }

        if (outputChoice == OutputChoice.ORIGINAL) {
            return Size(makeEven(sourceWidth), makeEven(sourceHeight))
        }

        val landscape = sourceWidth >= sourceHeight
        val (boxWidth, boxHeight) = when (outputChoice) {
            OutputChoice.FULL_HD -> if (landscape) 1920 to 1080 else 1080 to 1920
            OutputChoice.QHD -> if (landscape) 2560 to 1440 else 1440 to 2560
            OutputChoice.UHD_4K -> if (landscape) 3840 to 2160 else 2160 to 3840
            OutputChoice.ORIGINAL -> error("Handled above")
        }

        val scale = min(
            boxWidth.toDouble() / sourceWidth.toDouble(),
            boxHeight.toDouble() / sourceHeight.toDouble()
        )

        val width = makeEven((sourceWidth * scale).roundToInt())
        val height = makeEven((sourceHeight * scale).roundToInt())
        return Size(width.coerceAtLeast(2), height.coerceAtLeast(2))
    }

    private fun makeEven(value: Int): Int {
        val safe = value.coerceAtLeast(2)
        return if (safe % 2 == 0) safe else safe + 1
    }
}
