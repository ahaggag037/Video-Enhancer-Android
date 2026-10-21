package com.haggag.videoenhancer

import kotlin.math.ceil

object AiTileGrid {
    const val TILE_SIZE = 128
    const val OVERLAP = 16
    const val SCALE = 4
    private const val STEP = TILE_SIZE - OVERLAP

    data class Tile(
        val srcX: Int,
        val srcY: Int,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val dstX: Int,
        val dstY: Int
    )

    fun tiles(width: Int, height: Int): List<Tile> {
        require(width > 0 && height > 0) { "Image dimensions must be positive" }

        if (width <= TILE_SIZE && height <= TILE_SIZE) {
            return listOf(
                Tile(
                    srcX = 0,
                    srcY = 0,
                    sourceWidth = width,
                    sourceHeight = height,
                    dstX = 0,
                    dstY = 0
                )
            )
        }

        val countX = if (width <= TILE_SIZE) 1 else ceil(width.toDouble() / STEP).toInt()
        val countY = if (height <= TILE_SIZE) 1 else ceil(height.toDouble() / STEP).toInt()
        val result = ArrayList<Tile>(countX * countY)

        for (tileY in 0 until countY) {
            val srcY = if (height <= TILE_SIZE) 0 else (tileY * STEP).coerceAtMost(height - TILE_SIZE)
            for (tileX in 0 until countX) {
                val srcX = if (width <= TILE_SIZE) 0 else (tileX * STEP).coerceAtMost(width - TILE_SIZE)
                val sourceWidth = minOf(TILE_SIZE, width - srcX)
                val sourceHeight = minOf(TILE_SIZE, height - srcY)
                val tile = Tile(
                    srcX = srcX,
                    srcY = srcY,
                    sourceWidth = sourceWidth,
                    sourceHeight = sourceHeight,
                    dstX = srcX * SCALE,
                    dstY = srcY * SCALE
                )
                if (result.lastOrNull()?.srcX != tile.srcX || result.lastOrNull()?.srcY != tile.srcY) {
                    result += tile
                }
            }
        }
        return result.distinctBy { it.srcX to it.srcY }
    }
}
