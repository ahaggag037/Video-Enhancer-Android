/*
 * Real-ESRGAN LiteRT integration.
 * Tiling/inference structure is adapted from Google AI Edge LiteRT model-zoo samples.
 * Copyright 2026 The Google AI Edge Authors. Licensed under Apache-2.0.
 */
package com.haggag.videoenhancer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import com.google.ai.edge.litert.Accelerator

/**
 * Genuine learned x4 super-resolution using Real-ESRGAN General x4v3.
 * The final bitmap is rendered directly at the requested size so we never allocate
 * a full 4x video frame when the requested export is smaller than native x4 output.
 */
class RealEsrganUpscaler private constructor(
    private val runner: LiteRtModelRunner,
    val backend: Backend
) : AutoCloseable {

    enum class Backend { GPU, CPU }

    companion object {
        private const val TAG = "RealEsrganUpscaler"
        const val MODEL_NAME = "realesr_general_x4v3.tflite"
        private const val OUTPUT_TILE = AiTileGrid.TILE_SIZE * AiTileGrid.SCALE

        fun create(context: Context): RealEsrganUpscaler {
            val gpuAttempt = runCatching {
                RealEsrganUpscaler(
                    LiteRtModelRunner.fromAssets(context, MODEL_NAME, Accelerator.GPU),
                    Backend.GPU
                )
            }
            gpuAttempt.getOrNull()?.let {
                Log.i(TAG, "Real-ESRGAN compiled on LiteRT GPU")
                return it
            }

            Log.w(TAG, "GPU compilation unavailable; falling back to CPU", gpuAttempt.exceptionOrNull())
            return RealEsrganUpscaler(
                LiteRtModelRunner.fromAssets(context, MODEL_NAME, Accelerator.CPU),
                Backend.CPU
            )
        }
    }

    private val inputPixels = IntArray(AiTileGrid.TILE_SIZE * AiTileGrid.TILE_SIZE)
    private val inputFloats = FloatArray(AiTileGrid.TILE_SIZE * AiTileGrid.TILE_SIZE * 3)
    private val outputPixels = IntArray(OUTPUT_TILE * OUTPUT_TILE)
    private val outputTileBitmap = Bitmap.createBitmap(OUTPUT_TILE, OUTPUT_TILE, Bitmap.Config.ARGB_8888)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    fun upscale(
        input: Bitmap,
        requestedWidth: Int,
        requestedHeight: Int,
        onProgress: ((Float) -> Unit)? = null
    ): Bitmap {
        require(requestedWidth > 0 && requestedHeight > 0) { "Requested size must be positive" }
        check(!input.isRecycled) { "Input bitmap is recycled" }

        val result = Bitmap.createBitmap(requestedWidth, requestedHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val tiles = AiTileGrid.tiles(input.width, input.height)
        val nativeWidth = input.width * AiTileGrid.SCALE.toFloat()
        val nativeHeight = input.height * AiTileGrid.SCALE.toFloat()
        val targetScaleX = requestedWidth / nativeWidth
        val targetScaleY = requestedHeight / nativeHeight

        for ((index, tile) in tiles.withIndex()) {
            fillInputTile(input, tile)
            val output = runner.run(inputFloats)
            decodeNchwOutput(output)
            outputTileBitmap.setPixels(
                outputPixels,
                0,
                OUTPUT_TILE,
                0,
                0,
                OUTPUT_TILE,
                OUTPUT_TILE
            )

            val validOutWidth = tile.sourceWidth * AiTileGrid.SCALE
            val validOutHeight = tile.sourceHeight * AiTileGrid.SCALE
            val sourceRect = Rect(0, 0, validOutWidth, validOutHeight)
            val destination = RectF(
                tile.dstX * targetScaleX,
                tile.dstY * targetScaleY,
                (tile.dstX + validOutWidth) * targetScaleX,
                (tile.dstY + validOutHeight) * targetScaleY
            )
            canvas.drawBitmap(outputTileBitmap, sourceRect, destination, paint)
            onProgress?.invoke((index + 1).toFloat() / tiles.size.toFloat())
        }

        return result
    }

    private fun fillInputTile(input: Bitmap, tile: AiTileGrid.Tile) {
        inputPixels.fill(0)
        input.getPixels(
            inputPixels,
            0,
            AiTileGrid.TILE_SIZE,
            tile.srcX,
            tile.srcY,
            tile.sourceWidth,
            tile.sourceHeight
        )

        // Edge replication avoids introducing black padding into the neural model.
        for (y in 0 until tile.sourceHeight) {
            val row = y * AiTileGrid.TILE_SIZE
            val edge = inputPixels[row + tile.sourceWidth - 1]
            for (x in tile.sourceWidth until AiTileGrid.TILE_SIZE) {
                inputPixels[row + x] = edge
            }
        }
        val lastValidRow = (tile.sourceHeight - 1) * AiTileGrid.TILE_SIZE
        for (y in tile.sourceHeight until AiTileGrid.TILE_SIZE) {
            System.arraycopy(
                inputPixels,
                lastValidRow,
                inputPixels,
                y * AiTileGrid.TILE_SIZE,
                AiTileGrid.TILE_SIZE
            )
        }

        var index = 0
        for (pixel in inputPixels) {
            inputFloats[index++] = Color.red(pixel) / 255f
            inputFloats[index++] = Color.green(pixel) / 255f
            inputFloats[index++] = Color.blue(pixel) / 255f
        }
    }

    private fun decodeNchwOutput(output: FloatArray) {
        val plane = OUTPUT_TILE * OUTPUT_TILE
        require(output.size >= plane * 3) {
            "Unexpected Real-ESRGAN output size: ${output.size}"
        }
        for (i in 0 until plane) {
            val r = (output[i].coerceIn(0f, 1f) * 255f).toInt()
            val g = (output[plane + i].coerceIn(0f, 1f) * 255f).toInt()
            val b = (output[plane * 2 + i].coerceIn(0f, 1f) * 255f).toInt()
            outputPixels[i] = 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
        }
    }

    override fun close() {
        runCatching { runner.close() }
        if (!outputTileBitmap.isRecycled) outputTileBitmap.recycle()
    }
}
