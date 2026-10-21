package com.haggag.videoenhancer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiTileGridTest {

    @Test
    fun exact_tile_size_produces_one_full_tile() {
        val tiles = AiTileGrid.tiles(128, 128)

        assertEquals(1, tiles.size)
        assertEquals(AiTileGrid.Tile(0, 0, 128, 128, 0, 0), tiles.single())
    }

    @Test
    fun non_multiple_edges_are_covered_without_source_overflow() {
        val width = 200
        val height = 130
        val tiles = AiTileGrid.tiles(width, height)

        assertEquals(4, tiles.size)
        assertTrue(tiles.any { it.srcX == 72 && it.srcY == 2 })
        for (tile in tiles) {
            assertTrue(tile.srcX >= 0)
            assertTrue(tile.srcY >= 0)
            assertTrue(tile.srcX + tile.sourceWidth <= width)
            assertTrue(tile.srcY + tile.sourceHeight <= height)
        }
    }

    @Test
    fun small_image_uses_one_padded_source_tile() {
        val tile = AiTileGrid.tiles(80, 60).single()

        assertEquals(80, tile.sourceWidth)
        assertEquals(60, tile.sourceHeight)
        assertEquals(0, tile.srcX)
        assertEquals(0, tile.srcY)
    }

    @Test
    fun destination_coordinates_are_four_times_source_coordinates() {
        val tiles = AiTileGrid.tiles(300, 220)

        for (tile in tiles) {
            assertEquals(tile.srcX * 4, tile.dstX)
            assertEquals(tile.srcY * 4, tile.dstY)
        }
    }
}
