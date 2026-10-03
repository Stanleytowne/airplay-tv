package com.flymop.airplaytv.renderer

import org.junit.Assert.*
import org.junit.Test

class PlaybackTimingTest {
    @Test fun burstyArrivalKeepsSenderCadence() {
        val pacer = FramePacer()
        val now = 10_000_000_000L
        val pts = 1_000_000L
        val first = pacer.releaseTimeNs(pts, now)
        assertEquals(now + 40_000_000L, first)
        // Two frames arrived together; their release times must still differ by one frame.
        val second = pacer.releaseTimeNs(pts + 16_667, now + 25_000_000L)
        val third = pacer.releaseTimeNs(pts + 33_334, now + 25_000_000L)
        assertEquals(16_667_000L, third - second)
        assertEquals(first + 16_667_000L, second)
    }

    @Test fun clockJumpsAndPausesCannotCreateLongWaits() {
        val pacer = FramePacer()
        val now = 10_000_000_000L
        pacer.releaseTimeNs(1_000_000L, now)
        assertEquals(now + 60_000_000L, pacer.releaseTimeNs(100_000_000L, now + 20_000_000L))
        assertEquals(now + 80_000_000L, pacer.releaseTimeNs(10L, now + 40_000_000L))
        assertEquals(now + 2_040_000_000L, pacer.releaseTimeNs(16_677L, now + 2_000_000_000L))
        assertEquals(now, pacer.releaseTimeNs(0, now))
    }

    @Test fun lateFrameIsReleasedWithoutAddingAnotherDelay() {
        val pacer = FramePacer()
        val now = 10_000_000_000L
        pacer.releaseTimeNs(1_000_000L, now)
        assertEquals(now + 80_000_000L, pacer.releaseTimeNs(1_016_667L, now + 80_000_000L))
    }

    @Test fun reportedRateCountsDisplaySubmissionsAndGoesIdle() {
        val stats = RenderStats()
        val start = 10_000_000_000L
        repeat(51) { stats.record(start + it * 20_000_000L) }
        val snapshot = stats.snapshot(start + 1_000_000_000L)
        assertEquals(50, snapshot.fps)
        assertEquals(51L, snapshot.frames)
        assertEquals(0L, snapshot.jitterUs)
        assertEquals(0, stats.snapshot(start + 2_100_000_000L).fps)
        stats.reset()
        assertEquals(0L, stats.snapshot(start).frames)
    }

    @Test fun unevenDisplayCadenceAppearsInJitter() {
        val stats = RenderStats()
        var time = 10_000_000_000L
        stats.record(time)
        repeat(60) {
            time += if (it % 2 == 0) 10_000_000L else 30_000_000L
            stats.record(time)
        }
        assertEquals(50, stats.snapshot(time).fps)
        assertEquals(10_000L, stats.snapshot(time).jitterUs)
    }
}
