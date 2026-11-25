package com.remotephone.app

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * H.264 decoder for the controller side. Receives codec-config frames
 * (SPS+PPS) and video frames from the host's video socket and renders them
 * to the given Surface.
 */
class VideoDecoder(private val surface: Surface) {
    private var codec: MediaCodec? = null
    private var configured = false
    private var pendingConfig: ByteArray? = null
    private var pendingWidth = 0
    private var pendingHeight = 0

    @Synchronized
    fun onConfig(data: ByteArray, width: Int, height: Int) {
        if (configured) return
        pendingConfig = data
        pendingWidth = width
        pendingHeight = height
        tryConfigure()
    }

    @Synchronized
    fun onFrame(data: ByteArray, ptsUs: Long) {
        val c = codec ?: return
        try {
            val idx = c.dequeueInputBuffer(10_000)
            if (idx >= 0) {
                val buf = c.getInputBuffer(idx) ?: return
                buf.clear()
                buf.put(data)
                c.queueInputBuffer(idx, 0, data.size, ptsUs, 0)
            }
            drain(c)
        } catch (e: Exception) {
            Log.w(TAG, "decoder feed failed", e)
        }
    }

    private fun drain(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var out = c.dequeueOutputBuffer(info, 0)
        while (out >= 0) {
            c.releaseOutputBuffer(out, true)
            out = c.dequeueOutputBuffer(info, 0)
        }
    }

    private fun tryConfigure() {
        val config = pendingConfig ?: return
        val nals = Protocol.splitAnnexB(config)
        if (nals.size < 2) {
            Log.w(TAG, "codec config has ${nals.size} NALs, waiting for more")
            return
        }
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC, pendingWidth, pendingHeight,
        ).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(nals[0]))
            setByteBuffer("csd-1", ByteBuffer.wrap(nals[1]))
        }
        try {
            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            c.configure(format, surface, null, 0)
            c.start()
            codec = c
            configured = true
            Log.i(TAG, "decoder configured ${pendingWidth}x$pendingHeight")
        } catch (e: Exception) {
            Log.e(TAG, "decoder configure failed", e)
        }
    }

    @Synchronized
    fun release() {
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
        configured = false
        pendingConfig = null
    }

    companion object {
        private const val TAG = "RemotePhoneDecoder"
    }
}
