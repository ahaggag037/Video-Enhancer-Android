package com.haggag.videoenhancer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TileGridTest {

    @Test
    fun positionsCoverDimensionAndPinLastTileToEdge() {
        val starts = TileGrid.starts(length = 1280, tileSize = 256, overlap = 16)

        assertEquals(0, starts.first())
        assertEquals(1024, starts.last())
        assertEquals(starts.distinct(), starts)
        starts.zipWithNext().forEach { (a, b) ->
            assertTrue("tile gap between $a and $b", b - a <= 240)
        }
    }

    @Test
    fun shortDimensionUsesSingleTileAtZero() {
        assertEquals(listOf(0), TileGrid.starts(length = 180, tileSize = 256, overlap = 16))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOverlapThatConsumesWholeTile() {
        TileGrid.starts(length = 1000, tileSize = 256, overlap = 256)
    }
}
