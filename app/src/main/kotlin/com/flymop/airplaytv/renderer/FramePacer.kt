package com.flymop.airplaytv.renderer

/** Map sender timestamps to a bounded local playout window; owned by the decoder drain thread. */
internal class FramePacer(private val cushionNs: Long = 40_000_000L) {
    private var basePtsUs = Long.MIN_VALUE
    private var baseNs = 0L
    private var previousPtsUs = Long.MIN_VALUE

    fun releaseTimeNs(ptsUs: Long, nowNs: Long): Long {
        if (ptsUs <= 0) {
            basePtsUs = Long.MIN_VALUE
            previousPtsUs = Long.MIN_VALUE
            return nowNs
        }
        val deltaUs = if (basePtsUs == Long.MIN_VALUE) 0 else ptsUs - basePtsUs
        var target = baseNs + deltaUs * 1000L
        if (basePtsUs == Long.MIN_VALUE || ptsUs < previousPtsUs ||
            target < nowNs - 150_000_000L || target > nowNs + 250_000_000L) {
            basePtsUs = ptsUs
            baseNs = nowNs + cushionNs
            target = baseNs
        }
        previousPtsUs = ptsUs
        return maxOf(nowNs, target)
    }
}
