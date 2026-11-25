package com.ngoc.phoneremote

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Surface
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Quay màn hình → nén H.264 (tới 120 FPS) → gửi cho PC qua cổng 27183 (PC nối bằng "adb forward" qua cáp USB).
 * Đồng thời nhận lệnh điều khiển từ PC và chuyển cho InputService.
 *
 * Gói tin: [1 byte loại][4 byte độ dài][dữ liệu]
 *   Điện thoại → PC: 0 = JSON cấu hình, 1 = video ([8 byte pts][1 byte cờ][H.264 Annex-B]), 2 = JSON thông báo
 *   PC → điện thoại: 0 = JSON lệnh
 */
class CaptureService : Service() {

    companion object {
        const val TAG = "PhoneRemote"
        const val PORT = 27183
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "com.ngoc.phoneremote.STOP"
        const val FPS = 120
        const val MAX_LONG_SIDE = 1920
        const val BITRATE = 20_000_000
        const val FLAG_KEY = 1
        const val FLAG_CONFIG = 2

        @Volatile
        var running = false
        @Volatile
        var clientConnected = false
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var encThread: Thread? = null
    @Volatile private var encRunning = false
    @Volatile private var csd: ByteArray? = null

    private var server: ServerSocket? = null
    @Volatile private var out: DataOutputStream? = null
    @Volatile private var client: Socket? = null
    private val outLock = Any()
    private val encLock = Any()

    private var encW = 0
    private var encH = 0
    private var realW = 0
    private var realH = 0
    private var dpi = 320

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var displayManager: DisplayManager

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) {}
        override fun onDisplayRemoved(id: Int) {}
        override fun onDisplayChanged(id: Int) {
            if (id != Display.DEFAULT_DISPLAY || !running) return
            val m = realMetrics()
            if (m.widthPixels != realW || m.heightPixels != realH) {
                // Xoay màn hình → dựng lại bộ nén theo kích thước mới
                thread { restartEncoder() }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY

        val code = intent?.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra(EXTRA_DATA)
        if (code != Activity.RESULT_OK || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        startAsForeground()

        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = try {
            mpm.getMediaProjection(code, data)
        } catch (e: Exception) {
            Log.e(TAG, "getMediaProjection failed", e)
            null
        }
        if (mp == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, mainHandler)

        running = true
        try {
            synchronized(encLock) { startEncoderLocked() }
        } catch (e: Exception) {
            Log.e(TAG, "startEncoder failed", e)
            stopSelf()
            return START_NOT_STICKY
        }
        displayManager.registerDisplayListener(displayListener, mainHandler)
        startServer()
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "capture"
        nm.createNotificationChannel(NotificationChannel(channelId, "Phone Remote", NotificationManager.IMPORTANCE_LOW))
        val n: Notification = Notification.Builder(this, channelId)
            .setContentTitle("Phone Remote đang chạy")
            .setContentText("PC có thể xem và điều khiển điện thoại này qua cáp USB")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }

    private fun realMetrics(): DisplayMetrics {
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        displayManager.getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(m)
        return m
    }

    // ---------------- Bộ nén video ----------------

    private fun buildFormat(w: Int, h: Int, extras: Boolean): MediaFormat =
        MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
            if (extras) {
                setInteger(MediaFormat.KEY_OPERATING_RATE, FPS)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                setFloat("max-fps-to-encoder", FPS.toFloat())
            }
        }

    private fun startEncoderLocked() {
        val m = realMetrics()
        realW = m.widthPixels
        realH = m.heightPixels
        dpi = m.densityDpi
        val longSide = maxOf(realW, realH)
        val scale = if (longSide > MAX_LONG_SIDE) MAX_LONG_SIDE.toFloat() / longSide else 1f
        encW = ((realW * scale).toInt() / 8) * 8
        encH = ((realH * scale).toInt() / 8) * 8

        var c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            c.configure(buildFormat(encW, encH, true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            // Một số máy không nhận các tham số nâng cao → thử lại cấu hình cơ bản
            Log.w(TAG, "configure with extras failed, retrying basic", e)
            c.release()
            c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            c.configure(buildFormat(encW, encH, false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        val surface = c.createInputSurface()
        c.start()
        codec = c
        inputSurface = surface
        csd = null

        val vd = virtualDisplay
        if (vd == null) {
            virtualDisplay = projection!!.createVirtualDisplay(
                "PhoneRemote", encW, encH, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null
            )
        } else {
            vd.resize(encW, encH, dpi)
            vd.surface = surface
        }

        encRunning = true
        encThread = thread(name = "encoder") { encoderLoop(c) }
        sendConfig()
    }

    private fun stopEncoderLocked() {
        encRunning = false
        try { encThread?.join(1000) } catch (e: Exception) {}
        encThread = null
        try { virtualDisplay?.surface = null } catch (e: Exception) {}
        try { codec?.stop() } catch (e: Exception) {}
        try { codec?.release() } catch (e: Exception) {}
        try { inputSurface?.release() } catch (e: Exception) {}
        codec = null
        inputSurface = null
    }

    private fun restartEncoder() {
        synchronized(encLock) {
            if (!running) return
            try {
                stopEncoderLocked()
                startEncoderLocked()
            } catch (e: Exception) {
                Log.e(TAG, "restartEncoder failed", e)
            }
        }
    }

    private fun encoderLoop(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (encRunning) {
                val i = c.dequeueOutputBuffer(info, 100_000)
                if (i < 0) continue
                val buf = c.getOutputBuffer(i)
                if (buf != null && info.size > 0) {
                    val bytes = ByteArray(info.size)
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    buf.get(bytes)
                    var flags = 0
                    if (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) flags = flags or FLAG_KEY
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        flags = flags or FLAG_CONFIG
                        csd = bytes
                    }
                    sendVideo(bytes, info.presentationTimeUs, flags)
                }
                c.releaseOutputBuffer(i, false)
            }
        } catch (e: Exception) {
            if (encRunning) Log.e(TAG, "encoder loop error", e)
        }
    }

    private fun requestKeyFrame() {
        try {
            codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (e: Exception) {}
    }

    // ---------------- Gửi dữ liệu ----------------

    private fun sendPacket(type: Int, payload: ByteArray) {
        synchronized(outLock) {
            val o = out ?: return
            try {
                o.writeByte(type)
                o.writeInt(payload.size)
                o.write(payload)
                o.flush()
            } catch (e: Exception) {
                closeClient()
            }
        }
    }

    private fun sendVideo(data: ByteArray, pts: Long, flags: Int) {
        synchronized(outLock) {
            val o = out ?: return
            try {
                o.writeByte(1)
                o.writeInt(data.size + 9)
                o.writeLong(pts)
                o.writeByte(flags)
                o.write(data)
                o.flush()
            } catch (e: Exception) {
                closeClient()
            }
        }
    }

    private fun sendConfig() {
        val json = JSONObject()
            .put("width", encW).put("height", encH)
            .put("realWidth", realW).put("realHeight", realH)
            .put("fps", FPS)
            .put("accessibility", InputService.instance != null)
        sendPacket(0, json.toString().toByteArray(Charsets.UTF_8))
    }

    private fun sendNotice(msg: String) {
        sendPacket(2, JSONObject().put("msg", msg).toString().toByteArray(Charsets.UTF_8))
    }

    private fun closeClient() {
        try { client?.close() } catch (e: Exception) {}
        client = null
        out = null
        clientConnected = false
    }

    // ---------------- Máy chủ nhận kết nối từ PC ----------------

    private fun startServer() {
        thread(name = "server") {
            try {
                // Chỉ nghe trên localhost: chỉ PC qua "adb forward" (cáp USB) mới nối được
                val ss = ServerSocket(PORT, 1, InetAddress.getByName("127.0.0.1"))
                server = ss
                while (running) {
                    val s = ss.accept()
                    s.tcpNoDelay = true
                    synchronized(outLock) {
                        closeClient()
                        client = s
                        out = DataOutputStream(s.getOutputStream().buffered(256 * 1024))
                        clientConnected = true
                    }
                    sendConfig()
                    csd?.let { sendVideo(it, 0, FLAG_CONFIG) }
                    requestKeyFrame()
                    thread(name = "reader") { readCommands(s) }
                }
            } catch (e: Exception) {
                if (running) Log.e(TAG, "server error", e)
            }
        }
    }

    private fun readCommands(s: Socket) {
        var warned = false
        try {
            val input = DataInputStream(s.getInputStream().buffered())
            while (running && !s.isClosed) {
                val type = input.readUnsignedByte()
                val len = input.readInt()
                if (len < 0 || len > 1_000_000) break
                val bytes = ByteArray(len)
                input.readFully(bytes)
                if (type != 0) continue
                val cmd = JSONObject(String(bytes, Charsets.UTF_8))
                val t = cmd.optString("t")
                if (t == "keyframe") { requestKeyFrame(); continue }
                if (t == "vol") {
                    val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val dir = if (cmd.optInt("d") > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
                    continue
                }
                val svc = InputService.instance
                if (svc == null) {
                    if (!warned) {
                        warned = true
                        sendNotice("Chưa bật Trợ năng (Accessibility) cho Phone Remote trên điện thoại nên chưa điều khiển được.")
                    }
                    continue
                }
                val x = (cmd.optDouble("x", 0.0) * realW).toFloat().coerceIn(0f, (realW - 1).toFloat())
                val y = (cmd.optDouble("y", 0.0) * realH).toFloat().coerceIn(0f, (realH - 1).toFloat())
                when (t) {
                    "down" -> svc.touchDown(x, y)
                    "move" -> svc.touchMove(x, y)
                    "up" -> svc.touchUp(x, y)
                    "swipe" -> {
                        val x2 = (cmd.optDouble("x2", 0.0) * realW).toFloat().coerceIn(0f, (realW - 1).toFloat())
                        val y2 = (cmd.optDouble("y2", 0.0) * realH).toFloat().coerceIn(0f, (realH - 1).toFloat())
                        svc.swipe(x, y, x2, y2, cmd.optLong("ms", 150))
                    }
                    "text" -> svc.inputText(cmd.optString("s"))
                    "bksp" -> svc.backspace()
                    "enter" -> svc.enter()
                    "key" -> svc.globalKey(cmd.optString("k"))
                }
            }
        } catch (e: Exception) {
            // PC ngắt kết nối
        } finally {
            synchronized(outLock) { if (client === s) closeClient() }
            try { s.close() } catch (e: Exception) {}
        }
    }

    override fun onDestroy() {
        running = false
        try { displayManager.unregisterDisplayListener(displayListener) } catch (e: Exception) {}
        try { server?.close() } catch (e: Exception) {}
        synchronized(outLock) { closeClient() }
        synchronized(encLock) { stopEncoderLocked() }
        try { virtualDisplay?.release() } catch (e: Exception) {}
        try { projection?.stop() } catch (e: Exception) {}
        virtualDisplay = null
        projection = null
        super.onDestroy()
    }
}
