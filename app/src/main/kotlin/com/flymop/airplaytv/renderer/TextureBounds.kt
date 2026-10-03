package com.flymop.airplaytv.renderer

/** Safe sampling rectangle inside SurfaceTexture's crop/flip/rotation transform. */
internal object TextureBounds {
    fun update(matrix: FloatArray, width: Int, height: Int, result: FloatArray) {
        val x = matrix[12]
        val y = matrix[13]
        val x0 = minOf(minOf(x, x + matrix[0]), minOf(x + matrix[4], x + matrix[0] + matrix[4]))
        val x1 = maxOf(maxOf(x, x + matrix[0]), maxOf(x + matrix[4], x + matrix[0] + matrix[4]))
        val y0 = minOf(minOf(y, y + matrix[1]), minOf(y + matrix[5], y + matrix[1] + matrix[5]))
        val y1 = maxOf(maxOf(y, y + matrix[1]), maxOf(y + matrix[5], y + matrix[1] + matrix[5]))
        // Clamp only the outermost samples, without zooming or stretching the picture.
        // The guard also covers decoders that leave the final edge texel undefined.
        // One edge texel plus the half-texel footprint of bilinear filtering.
        val dx = minOf(1.5f * (x1 - x0) / width.coerceAtLeast(1), (x1 - x0) * 0.5f)
        val dy = minOf(1.5f * (y1 - y0) / height.coerceAtLeast(1), (y1 - y0) * 0.5f)
        result[0] = x0 + dx
        result[1] = y0 + dy
        result[2] = x1 - dx
        result[3] = y1 - dy
    }
}
