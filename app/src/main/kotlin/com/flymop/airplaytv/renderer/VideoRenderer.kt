package com.flymop.airplaytv.renderer

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import java.util.concurrent.locks.LockSupport
import android.view.Surface
import com.flymop.airplaytv.renderer.DecoderSelector.Companion.videoCaps

class VideoRenderer(ctx: Context) {

    private val lock = Object()
    private val pipeline = VideoPipeline()
    val selector = DecoderSelector(ctx)
    private var avcDecoder: MediaCodecInfo? = null
    private var hevcDecoder: MediaCodecInfo? = null
    private var maxFps = 0
    private var advertisedWidth = 1920
    private var advertisedHeight = 1080
    private var codec: MediaCodec? = null
    private var displaySurface: Surface? = null
    private var currentH265 = false
    private var videoWidth = 0
    private var videoHeight = 0
    private var firstFrameQueued = false

    private var outputWorker: OutputWorker? = null
    @Volatile private var outputFailure: Exception? = null

    // Receive rate and display submissions are deliberately separate.
    val fps: Int get() = pipeline.renderStats.snapshot(System.nanoTime()).fps
    val renderedFrames: Long get() = pipeline.renderStats.snapshot(System.nanoTime()).frames
    @Volatile var receivedFps = 0; private set
    @Volatile var bitrateBps = 0L; private set
    @Volatile var frameCount = 0L; private set
    @Volatile var codecName = ""; private set
    @Volatile var droppedFrames = 0L; private set
    val framePacingJitterUs: Long get() = pipeline.renderStats.snapshot(System.nanoTime()).jitterUs

    var enforceSdr = true
    var keyAllowFrameDrop = true
    @Volatile var scheduledOutputBufferRelease = true
    var benchmarkLog = false
    var benchmarkLogCallback: ((String) -> Unit)? = null
    private var _framesThisSec = 0
    private var _bytesThisSec = 0L
    private var _lastStatReset = 0L

    fun setResolution(w: Int, h: Int) {
        videoWidth = w
        videoHeight = h
        pipeline.setVideoSize(w, h)
    }

    // doesn't restart codec; decoder renders into pipeline's own persistent surface
    fun setSurface(surface: Surface) = synchronized(lock) {
        displaySurface = surface
        pipeline.setDisplaySurface(surface)
    }

    fun clearSurface(surface: Surface) = synchronized(lock) {
        if (displaySurface !== surface) return@synchronized
        displaySurface = null
        pipeline.setDisplaySurface(null)
    }

    fun selectDecoders(w: Int, h: Int, fps: Int, h265: Boolean): Boolean = synchronized(lock) {
        avcDecoder = selector.avc()
        hevcDecoder = if (h265) selector.hevc(avcDecoder, w, h, fps) else null
        maxFps = fps
        advertisedWidth = w
        advertisedHeight = h
        Log.i(TAG, "decoders: avc=${avcDecoder?.name} hevc=${hevcDecoder?.name}")
        hevcDecoder != null
    }

    // unknown limits default to 1080p
    fun maxResolution(): Pair<Int, Int> =
        listOfNotNull(avcDecoder?.let { it to DecoderSelector.AVC }, hevcDecoder?.let { it to DecoderSelector.HEVC })
            .map { (info, mime) ->
                runCatching { info.videoCaps(mime).let { it.supportedWidths.upper to it.supportedHeights.upper } }
                    .getOrDefault(1920 to 1080)
            }
            .reduceOrNull { (w1, h1), (w2, h2) -> maxOf(w1, w2) to maxOf(h1, h2) } ?: (1920 to 1080)

    // codec per mirror session; pipeline persists across sessions
    fun startSession() = synchronized(lock) { _resetStats() }

    fun stopSession() = synchronized(lock) { stopCodec() }

    private fun _resetStats() {
        receivedFps = 0; bitrateBps = 0; frameCount = 0; codecName = ""
        droppedFrames = 0
        pipeline.renderStats.reset()
        _lastStatReset = System.nanoTime()
        _framesThisSec = 0; _bytesThisSec = 0
    }

    private fun _updateStats(size: Int) {
        val now = System.nanoTime()
        if (now - _lastStatReset >= 1_000_000_000L) {
            receivedFps = (_framesThisSec * 1_000_000_000L / (now - _lastStatReset)).toInt()
            bitrateBps = _bytesThisSec * 8 * 1_000_000_000L / (now - _lastStatReset)
            _framesThisSec = 0
            _bytesThisSec = 0
            _lastStatReset = now
            if (benchmarkLog) _emitBenchmarkLine()
        }
        _framesThisSec++
        _bytesThisSec += size
        frameCount++
    }

    private fun _emitBenchmarkLine() {
        val msg = "renderFps=$fps receiveFps=$receivedFps bitrate=${bitrateBps / 1000}kbps " +
            "jitter=${framePacingJitterUs}us frames=$frameCount " +
            "dropped=$droppedFrames codec=$codecName " +
            "res=${videoWidth}x${videoHeight}"
        Log.i(BENCH_TAG, msg)
        benchmarkLogCallback?.invoke(msg)
    }

    fun feedFrame(data: ByteArray, ntpTimeNs: Long, isH265: Boolean) {
        synchronized(lock) {
            _updateStats(data.size)
            if (videoWidth == 0 || videoHeight == 0) return

            if (codec == null || isH265 != currentH265 || outputFailure != null) {
                // a stale reference frame decodes to corruption, so wait for a keyframe to (re)start
                if (!_isKeyframe(data, isH265)) {
                    if (codec != null) stopCodec()
                    return
                }
                stopCodec()
            }

            try {
                if (codec == null) startCodec(isH265)
                _feedToCodec(data, ntpTimeNs)
            } catch (e: Exception) {
                Log.w(TAG, "Codec error, resetting", e)
                stopCodec()
            }
        }
    }

    private fun _feedToCodec(data: ByteArray, ntpTimeNs: Long) {
        val c = codec ?: return
        // dropping a frame desyncs decoder until the next keyframe, but source would only send one on (re)connect
        val retries = if (firstFrameQueued) FEED_RETRIES else FIRST_FEED_RETRIES
        repeat(retries) {
            val idx = c.dequeueInputBuffer(FEED_WAIT_US)
            if (idx >= 0) {
                val buf = c.getInputBuffer(idx) ?: return
                buf.clear()
                buf.put(data)
                c.queueInputBuffer(idx, 0, data.size, ntpTimeNs / 1000, 0)
                firstFrameQueued = true
                return
            }
        }
        droppedFrames++
        Log.w(TAG, "Decoder input queue full; dropping frame. drops=$droppedFrames")
    }

    private fun _isKeyframe(data: ByteArray, isH265: Boolean): Boolean {
        if (data.size < 4) return false
        val limit = minOf(data.size - 4, 8192)
        var i = 0
        while (i <= limit) {
            val is4Byte = i + 4 <= data.size && data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()
            val is3Byte = !is4Byte && i + 3 <= data.size && data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()

            if (is4Byte || is3Byte) {
                val headerOffset = if (is4Byte) i + 4 else i + 3
                if (headerOffset < data.size) {
                    val key = if (isH265) {
                        val type = (data[headerOffset].toInt() shr 1) and 0x3F
                        type in 19..21 || type == 32 || type == 33
                    } else {
                        val type = data[headerOffset].toInt() and 0x1F
                        type == 5 || type == 7
                    }
                    if (key) return true
                }
                i += if (is4Byte) 4 else 3
            } else {
                i++
            }
        }
        return false
    }

    private fun startCodec(h265: Boolean) {
        pipeline.start()
        pipeline.setVideoSize(videoWidth, videoHeight)
        pipeline.setDecodedSize(videoWidth, videoHeight)
        val s = pipeline.inputSurface ?: return
        currentH265 = h265
        val mime = if (h265) DecoderSelector.HEVC else DecoderSelector.AVC
        val info = (if (h265) hevcDecoder else avcDecoder) ?: error("no decoder selected for $mime")

        firstFrameQueued = false
        try {
            _startWithLadder(info, mime, s, h265)
        } catch (e: Exception) {
            // strict hw decoders reject configs beyond their real limits
            val sw = selector.software(mime, videoWidth, videoHeight) ?: throw e
            Log.w(TAG, "Hardware decoder failed, trying software fallback", e)
            _startWithLadder(sw, mime, s, h265)
        }
        Log.i(TAG, "Video codec started: $mime ${videoWidth}x${videoHeight} ($codecName)")
    }

    private fun _startWithLadder(info: MediaCodecInfo, mime: String, s: Surface, h265: Boolean) {
        var tryNum = 0
        while (true) {
            val format = _format(mime, info)
            val more = selector.lowLatencyOptions(format, info, mime, tryNum)
            try {
                _startDecoder(MediaCodec.createByCodecName(info.name), format, s, h265)
                return
            } catch (e: Exception) {
                if (!more) throw e
                Log.w(TAG, "configure try $tryNum failed: $format", e)
                tryNum++
            }
        }
    }

    private fun _format(mime: String, info: MediaCodecInfo) = MediaFormat.createVideoFormat(mime, videoWidth, videoHeight).apply {
        setInteger(MediaFormat.KEY_FRAME_RATE, maxFps)
        if (selector.adaptive(info, mime)) {
            // The first picture can be the portrait Control Center. Reserve the
            // advertised landscape size too, before the sender rotates into video.
            val caps = info.videoCaps(mime)
            val maxW = maxOf(videoWidth, advertisedWidth).coerceAtMost(caps.supportedWidths.upper)
            val maxH = maxOf(videoHeight, advertisedHeight).coerceAtMost(caps.supportedHeights.upper)
            setInteger(MediaFormat.KEY_MAX_WIDTH, maxW)
            setInteger(MediaFormat.KEY_MAX_HEIGHT, maxH)
            Log.i(TAG, "Adaptive decoder bounds: ${maxW}x${maxH}; initial ${videoWidth}x${videoHeight}")
        }
        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf(videoWidth * videoHeight * 3 / 4, 1024 * 1024))
        if (enforceSdr) {
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        }
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            setInteger(MediaFormat.KEY_ALLOW_FRAME_DROP, if (keyAllowFrameDrop) 1 else 0)
        }
    }

    private fun _startDecoder(c: MediaCodec, format: MediaFormat, surface: Surface, h265: Boolean) {
        try {
            c.configure(format, surface, null, 0)
            c.start()
        } catch (e: Exception) {
            try { c.release() } catch (_: Exception) {}
            throw e
        }
        codec = c
        outputFailure = null
        outputWorker = OutputWorker(c).also { it.start() }
        codecName = (if (h265) "H.265" else "H.264") + " (${c.name})"
    }

    private fun stopCodec() {
        outputWorker?.stopAndJoin()
        outputWorker = null
        outputFailure = null
        codec?.let {
            try {
                it.stop()
                it.release()
            } catch (_: Exception) {}
        }
        codec = null
    }

    private inner class OutputWorker(private val decoder: MediaCodec) {
        @Volatile private var running = true
        private val thread = Thread({ run() }, "VideoDecodeOutput")
        private val pacer = FramePacer()

        fun start() = thread.start()

        fun stopAndJoin() {
            running = false
            LockSupport.unpark(thread)
            thread.join()
        }

        private fun run() {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY)
            val info = MediaCodec.BufferInfo()
            try {
                while (running) {
                    val index = decoder.dequeueOutputBuffer(info, 10_000L)
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val format = decoder.outputFormat
                        pipeline.setDecodedSize(format.getInteger(MediaFormat.KEY_WIDTH),
                            format.getInteger(MediaFormat.KEY_HEIGHT))
                        continue
                    }
                    if (index < 0) continue
                    if (!running) {
                        decoder.releaseOutputBuffer(index, false)
                        break
                    }
                    if (scheduledOutputBufferRelease) {
                        val target = pacer.releaseTimeNs(info.presentationTimeUs, System.nanoTime())
                        // SurfaceTexture consumers may ignore codec presentation deadlines.
                        // Pace here as well, without blocking the network/input thread.
                        while (running) {
                            val remaining = target - System.nanoTime()
                            if (remaining <= 0) break
                            LockSupport.parkNanos(minOf(remaining, 10_000_000L))
                        }
                    }
                    decoder.releaseOutputBuffer(index, running)
                }
            } catch (e: Exception) {
                if (running) {
                    outputFailure = e
                    Log.w(TAG, "Video output failed", e)
                }
            }
        }
    }

    fun release() = synchronized(lock) {
        stopCodec()
        pipeline.release()
        _resetStats()
    }

    companion object {
        private const val TAG = "VideoRenderer"
        private const val BENCH_TAG = "BENCHMARK"
        private const val FEED_WAIT_US = 20_000L
        private const val FEED_RETRIES = 10
        private const val FIRST_FEED_RETRIES = 50
    }
}
