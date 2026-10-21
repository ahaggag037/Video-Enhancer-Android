package com.haggag.videoenhancer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LumaTilerTest {

    @Test
    fun tilesWholeFrameAndAveragesOverlapsWithoutSeams() {
        val width = 300
        val height = 270
        val input = FloatArray(width * height) { index -> (index % width) / width.toFloat() }

        val output = LumaTiler.enhance(
            input = input,
            width = width,
            height = height,
            tileSize = 256,
            overlap = 32
        ) { tile -> FloatArray(tile.size) { i -> (tile[i] + 0.1f).coerceAtMost(1f) } }

        assertEquals(input.size, output.size)
        for (i in input.indices) {
            assertEquals((input[i] + 0.1f).coerceAtMost(1f), output[i], 0.0001f)
        }
    }

    @Test
    fun edgePaddingNeverCreatesOutOfRangeValues() {
        val input = FloatArray(64 * 64) { 0.95f }
        val output = LumaTiler.enhance(input, 64, 64, 256, 32) { tile ->
            FloatArray(tile.size) { 1.2f }
        }

        assertTrue(output.all { it in 0f..1f })
        assertTrue(output.all { it == 1f })
    }
}
