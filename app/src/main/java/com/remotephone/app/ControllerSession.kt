package com.remotephone.app

import android.util.Log
import android.view.Surface
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.DataInputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Controller side networking: control socket (JSON lines) + video socket
 * (length-prefixed H.264 frames). Feeds a VideoDecoder built on the
 * SurfaceView's surface.
 */
class ControllerSession {
    private val running = AtomicBoolean(false)
    private var controlSocket: Socket? = null
    private var videoSocket: Socket? = null
    private var controlOut: OutputStream? = null
    private var decoder: VideoDecoder? = null
    private var videoThread: Thread? = null
    private var pendingConfig: ByteArray? = null

    /** Creates the decoder as soon as a surface exists; the encoder only sends SPS/PPS once. */
    private fun ensureDecoder(surfaceProvider: () -> Surface?) {
        if (decoder != null) return
        val config = pendingConfig ?: return
        val surface = surfaceProvider() ?: return
        val d = VideoDecoder(surface)
        d.onConfig(config, videoWidth, videoHeight)
        decoder = d
    }

    @Volatile
    var videoWidth = 0
        private set

    @Volatile
    var videoHeight = 0
        private set

    fun connect(
        host: String,
        code: String,
        surfaceProvider: () -> Surface?,
    ) {
        if (running.getAndSet(true)) return
        ControllerSessionState.state.value = ControllerState.CONNECTING
        ControllerSessionState.status.value = "Connecting to $host…"
        Thread({
            try {
                val cs = Socket()
                cs.connect(InetSocketAddress(host, Protocol.CONTROL_PORT), 10_000)
                cs.soTimeout = 15_000
                controlSocket = cs
                val out = cs.getOutputStream()
                controlOut = out
                out.write((Protocol.hello(code) + "\n").toByteArray(Charsets.UTF_8))
                out.flush()
                val reader = BufferedReader(InputStreamReader(cs.getInputStream(), Charsets.UTF_8))
                // Wait for ready / denied.
                var ready = false
                while (running.get()) {
                    val line = reader.readLine() ?: break
                    val msg = JSONObject(line)
                    when (msg.optString("type")) {
                        "ready" -> {
                            videoWidth = msg.optInt("width", 0)
                            videoHeight = msg.optInt("height", 0)
                            ControllerSessionState.videoWidth.value = videoWidth
                            ControllerSessionState.videoHeight.value = videoHeight
                            ready = true
                            break
                        }
                        "denied" -> {
                            ControllerSessionState.state.value = ControllerState.ERROR
                            ControllerSessionState.status.value =
                                "Connection refused: ${msg.optString("reason", "?")}"
                            disconnect()
                            return@Thread
                        }
                    }
                }
                if (!ready || videoWidth <= 0) {
                    ControllerSessionState.state.value = ControllerState.ERROR
                    ControllerSessionState.status.value = "No answer from host"
                    disconnect()
                    return@Thread
                }
                cs.soTimeout = 0
                // Video socket.
                val vs = Socket()
                vs.connect(InetSocketAddress(host, Protocol.VIDEO_PORT), 10_000)
                videoSocket = vs
                ControllerSessionState.state.value = ControllerState.CONNECTED
                ControllerSessionState.status.value = "Connected — live screen"
                videoThread = Thread({
                    videoLoop(vs, surfaceProvider)
                }, "remotephone-video").apply { start() }
                // Keep reading control messages (none expected from host in v1).
                try {
                    while (running.get()) {
                        if (reader.readLine() == null) break
                    }
                } catch (_: Exception) {
                }
            } catch (e: Exception) {
                Log.w(TAG, "controller connect failed", e)
                if (running.get()) {
                    ControllerSessionState.state.value = ControllerState.ERROR
                    ControllerSessionState.status.value = "Could not connect: ${e.message}"
                }
            } finally {
                if (running.get()) {
                    // Control socket dropped while connected.
                    if (ControllerSessionState.state.value == ControllerState.CONNECTED) {
                        ControllerSessionState.state.value = ControllerState.DISCONNECTED
                        ControllerSessionState.status.value = "Disconnected"
                    }
                    disconnect()
                }
            }
        }, "remotephone-control").start()
    }

    private fun videoLoop(vs: Socket, surfaceProvider: () -> Surface?) {
        try {
            val input = DataInputStream(BufferedInputStream(vs.getInputStream()))
            var ptsUs = 0L
            while (running.get()) {
                val length = try {
                    input.readInt()
                } catch (e: Exception) {
                    break
                }
                if (length <= 0 || length > 8_000_000) {
                    Log.w(TAG, "bad video frame length $length")
                    break
                }
                val type = input.readByte().toInt()
                val payload = ByteArray(length - 1)
                input.readFully(payload)
                ptsUs += 33_333
                when (type) {
                    0 -> {
                        pendingConfig = payload
                        ensureDecoder(surfaceProvider)
                    }
                    1 -> {
                        ensureDecoder(surfaceProvider)
                        decoder?.onFrame(payload, ptsUs)
                    }
                }
            }
        } catch (e: Exception) {
            Log.i(TAG, "video loop ended", e)
        } finally {
            if (running.get() && ControllerSessionState.state.value == ControllerState.CONNECTED) {
                ControllerSessionState.state.value = ControllerState.DISCONNECTED
                ControllerSessionState.status.value = "Video stream ended"
            }
        }
    }

    fun sendTap(x: Float, y: Float) = send(Protocol.tap(x, y))

    fun sendSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Int) =
        send(Protocol.swipe(x1, y1, x2, y2, durationMs))

    fun sendKey(action: String) = send(Protocol.key(action))

    fun sendText(text: String) = send(Protocol.text(text))

    private fun send(msg: String) {
        val out = controlOut ?: return
        synchronized(out) {
            try {
                out.write((msg + "\n").toByteArray(Charsets.UTF_8))
                out.flush()
            } catch (e: Exception) {
                Log.w(TAG, "control send failed", e)
            }
        }
    }

    fun disconnect() {
        if (!running.getAndSet(false)) return
        try { controlOut?.let { send(Protocol.bye()) } } catch (_: Exception) {}
        try { controlSocket?.close() } catch (_: Exception) {}
        try { videoSocket?.close() } catch (_: Exception) {}
        controlSocket = null
        videoSocket = null
        controlOut = null
        videoThread?.interrupt()
        videoThread = null
        decoder?.release()
        decoder = null
        pendingConfig = null
        if (ControllerSessionState.state.value == ControllerState.CONNECTED ||
            ControllerSessionState.state.value == ControllerState.CONNECTING
        ) {
            ControllerSessionState.state.value = ControllerState.DISCONNECTED
            ControllerSessionState.status.value = "Disconnected"
        }
    }

    companion object {
        private const val TAG = "RemotePhoneCtrl"
    }
}
