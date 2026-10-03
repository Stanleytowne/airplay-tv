package com.flymop.airplaytv.renderer

import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Counts unique textures actually submitted to the display, rather than received NAL packets. */
internal class RenderStats {
    data class Snapshot(val fps: Int, val frames: Long, val jitterUs: Long)
    private val intervals = LongArray(120)
    private var next = 0
    private var count = 0
    private var frames = 0L
    private var lastNs = 0L

    @Synchronized fun record(nowNs: Long) {
        if (lastNs > 0 && nowNs > lastNs) {
            intervals[next] = nowNs - lastNs
            next = (next + 1) % intervals.size
            count = minOf(count + 1, intervals.size)
        }
        lastNs = nowNs
        frames++
    }

    @Synchronized fun reset() {
        next = 0; count = 0; frames = 0; lastNs = 0
    }

    @Synchronized fun snapshot(nowNs: Long): Snapshot {
        if (count == 0 || nowNs - lastNs > 1_000_000_000L) return Snapshot(0, frames, 0)
        var sum = 0.0
        var squares = 0.0
        for (i in 0 until count) {
            val value = intervals[i].toDouble()
            sum += value
            squares += value * value
        }
        val mean = sum / count
        val jitter = sqrt((squares / count - mean * mean).coerceAtLeast(0.0)) / 1000.0
        return Snapshot((1_000_000_000.0 / mean).roundToInt(), frames, jitter.toLong())
    }
}
