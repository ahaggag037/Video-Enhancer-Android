package com.haggag.videoenhancer

object LumaTiler {
    fun enhance(
        input: FloatArray,
        width: Int,
        height: Int,
        tileSize: Int = 256,
        overlap: Int = 32,
        transform: (FloatArray) -> FloatArray
    ): FloatArray {
        require(width > 0 && height > 0)
        require(input.size == width * height)
        val xStarts = TileGrid.starts(width, tileSize, overlap)
        val yStarts = TileGrid.starts(height, tileSize, overlap)
        val sum = FloatArray(input.size)
        val weight = FloatArray(input.size)

        for (top in yStarts) {
            for (left in xStarts) {
                val tile = FloatArray(tileSize * tileSize)
                for (ty in 0 until tileSize) {
                    val sy = (top + ty).coerceAtMost(height - 1)
                    val srcRow = sy * width
                    val tileRow = ty * tileSize
                    for (tx in 0 until tileSize) {
                        val sx = (left + tx).coerceAtMost(width - 1)
                        tile[tileRow + tx] = input[srcRow + sx]
                    }
                }

                val enhanced = transform(tile)
                require(enhanced.size == tile.size)
                for (ty in 0 until tileSize) {
                    val y = top + ty
                    if (y >= height) break
                    val outRow = y * width
                    val tileRow = ty * tileSize
                    for (tx in 0 until tileSize) {
                        val x = left + tx
                        if (x >= width) break
                        val outIndex = outRow + x
                        sum[outIndex] += enhanced[tileRow + tx].coerceIn(0f, 1f)
                        weight[outIndex] += 1f
                    }
                }
            }
        }

        return FloatArray(input.size) { i ->
            if (weight[i] > 0f) (sum[i] / weight[i]).coerceIn(0f, 1f) else input[i].coerceIn(0f, 1f)
        }
    }
}
