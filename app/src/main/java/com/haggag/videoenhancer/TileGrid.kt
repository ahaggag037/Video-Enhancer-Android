package com.haggag.videoenhancer

object TileGrid {
    fun starts(length: Int, tileSize: Int, overlap: Int): List<Int> {
        require(length > 0)
        require(tileSize > 0)
        require(overlap >= 0 && overlap < tileSize)
        if (length <= tileSize) return listOf(0)

        val stride = tileSize - overlap
        val last = length - tileSize
        val result = mutableListOf<Int>()
        var position = 0
        while (position < last) {
            result += position
            position += stride
        }
        if (result.lastOrNull() != last) result += last
        return result.distinct()
    }
}
