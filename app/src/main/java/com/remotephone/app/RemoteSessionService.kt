package com.remotephone.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * Host side (the phone being controlled). Foreground service with
 * mediaProjection type. Opens two TCP servers on local Wi-Fi:
 *  - control port 18081: JSON-lines control messages + pairing handshake
 *  - video port 18080: length-prefixed H.264 frames
 *
 * Flow: controller connects to the control port with the 6-digit code shown
 * in the app. On a match, an accept dialog is shown on this phone. Only
 * after the user accepts AND approves the system screen-capture prompt does
 * streaming start. Any disconnect tears the session down and the service
 * goes back to listening.
 */
class RemoteSessionService : Service() {

    companion object {
        const val ACTION_START_HOST = "com.remotephone.app.START_HOST"
        const val ACTION_STOP = "com.remotephone.app.STOP"
        const val ACTION_PROJECTION_RESULT = "com.remotephone.app.PROJECTION_RESULT"
        const val ACTION_DECLINE = "com.remotephone.app.DECLINE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_PEER_IP = "peer_ip"
        const val NOTIF_ID = 1001
        const val CHANNEL_ID = "remotephone_session"
        private const val TAG = "RemotePhoneHost"
        private const val BITRATE = 2_500_000
        private const val FRAME_RATE = 30
        private const val I_FRAME_INTERVAL = 2
    }

    private val running = AtomicBoolean(false)
    private var controlServer: ServerSocket? = null
    private var videoServer: ServerSocket? = null
    private var controlSocket: Socket? = null
    private var videoSocket: Socket? = null
    private var controlOut: OutputStream? = null
    private var videoOut: OutputStream? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    private var encoderThread: Thread? = null
    private var acceptThread: Thread? = null

    @Volatile
    private var acceptLatch: CountDownLatch? = null

    @Volatile
    private var acceptResultCode: Int = 0

    @Volatile
    private var acceptResultData: Intent? = null

    @Volatile
    private var accepted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_HOST -> startHosting()
            ACTION_STOP -> stopHosting()
            ACTION_PROJECTION_RESULT -> {
                acceptResultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                acceptResultData = intent.getParcelableExtra(EXTRA_RESULT_DATA)
                accepted = true
                acceptLatch?.countDown()
            }
            ACTION_DECLINE -> {
                accepted = false
                acceptLatch?.countDown()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopHosting()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ setup

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "RemotePhone session",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows while this phone is sharing its screen"
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun notificationText(): String = when (HostSession.state.value) {
        HostState.IDLE -> "RemotePhone is not hosting"
        HostState.LISTENING -> "Waiting for a controller — tap to open RemotePhone"
        HostState.WAITING_ACCEPT -> "Connection request from ${HostSession.peerIp.value}"
        HostState.STREAMING -> "Sharing your screen with ${HostSession.peerIp.value} — tap to stop"
    }

    private fun updateNotification() {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, RemoteSessionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("RemotePhone")
            .setContentText(notificationText())
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .build()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, notif)
    }

    private fun startForeground() {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, RemoteSessionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("RemotePhone")
            .setContentText(notificationText())
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    // ------------------------------------------------------------- hosting

    private fun startHosting() {
        if (running.getAndSet(true)) return
        val code = (100000 + Random.nextInt(900000)).toString()
        HostSession.pairCode.value = code
        HostSession.hostIp.value = NetworkUtils.localIpv4() ?: "?"
        HostSession.captureSize.value = NetworkUtils.captureSize()
        HostSession.state.value = HostState.LISTENING
        HostSession.status.value = "Waiting for a controller on Wi-Fi"
        startForeground()
        acceptThread = Thread({ acceptLoop() }, "remotephone-accept").apply { start() }
        Log.i(TAG, "hosting started, code=$code ip=${HostSession.hostIp.value}")
    }

    fun stopHosting() {
        if (!running.getAndSet(false)) {
            // Still make sure everything is torn down.
        }
        tearDownSession()
        try { controlServer?.close() } catch (_: Exception) {}
        try { videoServer?.close() } catch (_: Exception) {}
        controlServer = null
        videoServer = null
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(NOTIF_ID)
        } catch (_: Exception) {}
        HostSession.reset()
        stopSelf()
    }

    private fun acceptLoop() {
        try {
            controlServer = ServerSocket(Protocol.CONTROL_PORT)
            videoServer = ServerSocket(Protocol.VIDEO_PORT)
            Log.i(TAG, "listening on ${Protocol.CONTROL_PORT} / ${Protocol.VIDEO_PORT}")
            while (running.get()) {
                val socket = try {
                    controlServer?.accept()
                } catch (e: Exception) {
                    if (running.get()) Log.w(TAG, "control accept failed", e)
                    break
                } ?: break
                if (!running.get()) {
                    try { socket.close() } catch (_: Exception) {}
                    break
                }
                handleControlClient(socket)
            }
        } catch (e: Exception) {
            if (running.get()) {
                Log.e(TAG, "accept loop failed", e)
                HostSession.status.value = "Could not open ports: ${e.message}"
            }
        }
    }

    private fun handleControlClient(socket: Socket) {
        val peerIp = socket.inetAddress?.hostAddress ?: "?"
        Log.i(TAG, "control connection from $peerIp")
        try {
            socket.soTimeout = 15_000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val out = socket.getOutputStream()
            val helloLine = reader.readLine() ?: return
            val hello = JSONObject(helloLine)
            if (hello.optString("type") != "hello" || hello.optString("code") != HostSession.pairCode.value) {
                out.write((Protocol.denied("wrong code") + "\n").toByteArray(Charsets.UTF_8))
                out.flush()
                Log.w(TAG, "wrong pairing code from $peerIp")
                return
            }
            if (HostSession.state.value == HostState.STREAMING ||
                HostSession.state.value == HostState.WAITING_ACCEPT
            ) {
                out.write((Protocol.denied("busy") + "\n").toByteArray(Charsets.UTF_8))
                out.flush()
                return
            }
            controlSocket = socket
            controlOut = out
            HostSession.peerIp.value = peerIp
            HostSession.state.value = HostState.WAITING_ACCEPT
            HostSession.status.value = "Connection request from $peerIp"
            updateNotification()

            // Ask the user. The dialog activity reports back via
            // ACTION_PROJECTION_RESULT (accepted) or ACTION_DECLINE.
            accepted = false
            acceptLatch = CountDownLatch(1)
            val dialogIntent = Intent(this, PairRequestActivity::class.java).apply {
                putExtra(EXTRA_PEER_IP, peerIp)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(dialogIntent)
            val answered = acceptLatch?.await(60, TimeUnit.SECONDS) == true
            acceptLatch = null
            if (!answered || !accepted) {
                sendControl(Protocol.denied("declined"))
                Log.i(TAG, "connection from $peerIp declined or timed out")
                closeControlSocket()
                HostSession.state.value = HostState.LISTENING
                HostSession.status.value = "Waiting for a controller on Wi-Fi"
                updateNotification()
                return
            }
            socket.soTimeout = 0
            startStreaming(socket, reader)
        } catch (e: Exception) {
            Log.w(TAG, "control client error ($peerIp)", e)
        } finally {
            if (controlSocket === socket) {
                tearDownSession()
                if (running.get()) {
                    HostSession.state.value = HostState.LISTENING
                    HostSession.status.value = "Controller disconnected — waiting"
                    updateNotification()
                }
            } else {
                try { socket.close() } catch (_: Exception) {}
            }
        }
    }

    // ------------------------------------------------------------ streaming

    private fun startStreaming(controlSocket: Socket, reader: BufferedReader) {
        val resultData = acceptResultData ?: run {
            Log.e(TAG, "no MediaProjection result data")
            return
        }
        val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = try {
            pm.getMediaProjection(acceptResultCode, resultData)
        } catch (e: Exception) {
            Log.e(TAG, "getMediaProjection failed", e)
            HostSession.status.value = "Screen capture failed: ${e.message}"
            return
        }
        if (projection == null) {
            Log.e(TAG, "getMediaProjection returned null")
            HostSession.status.value = "Screen capture failed"
            return
        }
        mediaProjection = projection
        val (cw, ch) = NetworkUtils.captureSize()
        HostSession.captureSize.value = Pair(cw, ch)
        sendControl(Protocol.ready(cw, ch))

        // Wait for the controller to open the video socket, then start encoding.
        val video = try {
            videoServer?.soTimeout = 15_000
            videoServer?.accept()
        } catch (e: Exception) {
            Log.w(TAG, "video accept failed", e)
            null
        } ?: run {
            tearDownSession()
            return
        }
        videoSocket = video
        videoOut = BufferedOutputStream(video.getOutputStream())
        Log.i(TAG, "video client connected, starting encoder ${cw}x$ch")

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, cw, ch).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder = enc
        enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = enc.createInputSurface()
        enc.start()
        val metrics = resources.displayMetrics
        virtualDisplay = projection.createVirtualDisplay(
            "RemotePhone",
            cw, ch, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface, null, null,
        )
        HostSession.state.value = HostState.STREAMING
        HostSession.status.value = "Sharing your screen with ${HostSession.peerIp.value}"
        updateNotification()

        encoderThread = Thread({ encoderLoop(enc) }, "remotephone-encoder").apply { start() }

        // Control-message loop (blocking read until the controller goes away).
        try {
            while (running.get()) {
                val line = reader.readLine() ?: break
                handleControlMessage(line)
            }
        } catch (e: Exception) {
            Log.i(TAG, "control read ended", e)
        }
    }

    private fun encoderLoop(enc: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (running.get() && HostSession.state.value == HostState.STREAMING) {
                val idx = enc.dequeueOutputBuffer(info, 10_000)
                if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) continue
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
                if (idx < 0) continue
                val buf = enc.getOutputBuffer(idx)
                if (buf == null) {
                    enc.releaseOutputBuffer(idx, false)
                    continue
                }
                val data = ByteArray(info.size)
                buf.position(info.offset)
                buf.get(data)
                enc.releaseOutputBuffer(idx, false)
                if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    sendVideoFrame(0, data)
                } else if (info.size > 0) {
                    sendVideoFrame(1, data)
                }
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
            }
        } catch (e: Exception) {
            Log.i(TAG, "encoder loop ended", e)
        }
    }

    /** Video frame wire format: 4-byte big-endian length, 1-byte type (0=config, 1=frame), payload. */
    private fun sendVideoFrame(type: Int, payload: ByteArray) {
        val out = videoOut ?: return
        synchronized(out) {
            try {
                val header = ByteBuffer.allocate(5)
                header.putInt(payload.size + 1)
                header.put(type.toByte())
                out.write(header.array())
                out.write(payload)
                out.flush()
            } catch (e: Exception) {
                Log.i(TAG, "video write failed", e)
                try { videoSocket?.close() } catch (_: Exception) {}
            }
        }
    }

    private fun sendControl(msg: String) {
        val out = controlOut ?: return
        synchronized(out) {
            try {
                out.write((msg + "\n").toByteArray(Charsets.UTF_8))
                out.flush()
            } catch (e: Exception) {
                Log.i(TAG, "control write failed", e)
            }
        }
    }

    private fun handleControlMessage(line: String) {
        val a11y = RemoteControlAccessibilityService.instance
        try {
            val msg = JSONObject(line)
            when (msg.optString("type")) {
                "bye" -> {
                    Log.i(TAG, "controller said bye")
                    try { controlSocket?.close() } catch (_: Exception) {}
                }
                "tap" -> {
                    if (a11y == null) {
                        Log.w(TAG, "tap ignored: accessibility service not enabled")
                        return
                    }
                    a11y.tap(msg.optDouble("x", 0.0).toFloat(), msg.optDouble("y", 0.0).toFloat())
                }
                "swipe" -> {
                    if (a11y == null) {
                        Log.w(TAG, "swipe ignored: accessibility service not enabled")
                        return
                    }
                    a11y.swipe(
                        msg.optDouble("x1", 0.0).toFloat(),
                        msg.optDouble("y1", 0.0).toFloat(),
                        msg.optDouble("x2", 0.0).toFloat(),
                        msg.optDouble("y2", 0.0).toFloat(),
                        msg.optInt("duration", 300),
                    )
                }
                "key" -> {
                    if (a11y == null) {
                        Log.w(TAG, "key ignored: accessibility service not enabled")
                        return
                    }
                    when (msg.optString("action")) {
                        "back" -> a11y.pressBack()
                        "home" -> a11y.pressHome()
                        "recents" -> a11y.pressRecents()
                    }
                }
                "text" -> {
                    if (a11y == null) {
                        Log.w(TAG, "text ignored: accessibility service not enabled")
                        return
                    }
                    a11y.typeText(msg.optString("text", ""))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "bad control message: $line", e)
        }
    }

    // ------------------------------------------------------------ teardown

    private fun closeControlSocket() {
        try { controlSocket?.close() } catch (_: Exception) {}
        controlSocket = null
        controlOut = null
    }

    private fun tearDownSession() {
        try { encoderThread?.interrupt() } catch (_: Exception) {}
        encoderThread = null
        try { encoder?.stop() } catch (_: Exception) {}
        try { encoder?.release() } catch (_: Exception) {}
        encoder = null
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null
        acceptResultData = null
        try { videoSocket?.close() } catch (_: Exception) {}
        videoSocket = null
        videoOut = null
        closeControlSocket()
        if (HostSession.state.value == HostState.STREAMING ||
            HostSession.state.value == HostState.WAITING_ACCEPT
        ) {
            HostSession.state.value = HostState.LISTENING
        }
    }
}
