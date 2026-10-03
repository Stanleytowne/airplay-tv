#include <cassert>
#include <cstdio>
#include <vector>
#include "timeline_buffer.h"

int main() {
    DelayTracker adaptive(48000, 2, 0, 99);
    assert(adaptive.target() == 3840); // 40 ms of stereo samples before the first packet.
    adaptive.noteOutputBufferFrames(1152);
    for (int i = 0; i < 100; ++i)
        adaptive.observe(i * 10'000'000LL, i * 10'000'000LL + 10'000'000LL, 10'000'000LL);
    assert(adaptive.target() >= 3840);
    for (int i = 100; i < 120; ++i)
        adaptive.observe(i * 10'000'000LL, i * 10'000'000LL + 100'000'000LL, 10'000'000LL);
    assert(adaptive.target() > 3840 && adaptive.target() <= adaptive.ceil());
    for (int i = 120; i < 200; ++i)
        adaptive.observe(i * 10'000'000LL, i * 10'000'000LL + 2'000'000'000LL, 10'000'000LL);
    assert(adaptive.target() <= 19200); // No more than 200 ms adaptive cushion.
    adaptive.reanchor();
    assert(adaptive.target() == 3840); // A discontinuity clears old stall history.
    DelayTracker fixed(48000, 2, 25, 99);
    fixed.observe(1, 1'000'000'000LL, 10'000'000LL);
    assert(fixed.target() == 2400); // Preserve an explicitly configured fixed cushion.

    TimelineBuffer buffer(48000, 2, 0, 99);
    std::vector<int16_t> packet(480, 1000), output(480), more(4800, 1000);
    const int64_t pts = monoNs();
    buffer.write(packet.data(), packet.size(), pts);
    buffer.read(output.data(), 240);
    for (auto sample : output) assert(sample == 0); // Only 5 ms buffered: keep priming.
    assert(buffer.debugInfo().metrics.underruns == 0);
    buffer.write(more.data(), more.size(), pts + 5'000'000LL);
    buffer.read(output.data(), 240);
    for (auto sample : output) assert(sample == 1000);
    assert(buffer.debugInfo().metrics.underruns == 0);
    puts("PASS: adaptive audio starts with headroom, grows for jitter, preserves fixed settings, primes without underrunning");
}
