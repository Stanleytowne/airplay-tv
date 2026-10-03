package com.flymop.airplaytv.renderer

import org.junit.Assert.*
import org.junit.Test

class TextureBoundsTest {
    private fun identity() = FloatArray(16).apply { this[0] = 1f; this[5] = 1f; this[10] = 1f; this[15] = 1f }

    @Test fun rightEdgeFilterCannotReadUndefinedLastColumn() {
        val bounds = FloatArray(4)
        TextureBounds.update(identity(), 1920, 1080, bounds)
        // Texture coordinate -> texel-space center; bilinear filtering must stay at/before column 1918.
        assertEquals(1918f, bounds[2] * 1920 - 0.5f, 0.0002f)
        assertEquals(1f, bounds[0] * 1920 - 0.5f, 0.0002f)
        assertTrue(bounds[0] < 0.5f && bounds[2] > 0.5f)
    }

    @Test fun croppedAndFlippedTextureRemainsInsideItsValidRectangle() {
        val matrix = identity().apply { this[0] = 0.75f; this[5] = -0.8f; this[12] = 0.1f; this[13] = 0.9f }
        val bounds = FloatArray(4)
        TextureBounds.update(matrix, 1920, 1080, bounds)
        assertTrue(bounds[0] > 0.1f && bounds[2] < 0.85f)
        assertTrue(bounds[1] > 0.1f && bounds[3] < 0.9f)
        assertTrue(bounds[0] < bounds[2] && bounds[1] < bounds[3])
    }

    @Test fun rotationAndSmallSizesDoNotInvertBounds() {
        val matrix = identity().apply { this[0] = 0f; this[1] = 1f; this[4] = -1f; this[5] = 0f; this[12] = 1f }
        val bounds = FloatArray(4)
        TextureBounds.update(matrix, 1080, 1920, bounds)
        assertTrue(bounds.all { it > 0f && it < 1f })
        TextureBounds.update(matrix, 1, 0, bounds)
        assertArrayEquals(floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f), bounds, 0.00001f)
    }
}
