package com.ngoc.phoneremote

import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Bundle
import android.os.IBinder
import android.view.Surface
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import kotlin.concurrent.thread

// Quay man hinh bang quyen shell: khong can hop thoai xin quyen, khong dung khi khoa may.
// Nen H.264 toi 120 FPS, gui qua socket phoneremote_video.
// Goi tin: 1 byte loai, 4 byte do dai, du lieu. Loai 0 = JSON cau hinh, loai 1 = video (8 byte pts, 1 byte co, H.264).
object ShellVideo {

    private const val SOCKET_NAME = "phoneremote_video"
    @Volatile private var FPS = 120
    @Volatile private var MAX_LONG_SIDE = 4096 // 4096 = giữ đúng độ phân giải gốc của màn hình
    @Volatile private var BITRATE = 32_000_000
    @Volatile private var settingsChanged = false
    private const val FLAG_KEY = 1
    private const val FLAG_CONFIG = 2

    @Volatile private var wantKeyFrame = false

    /** PC đổi cài đặt: áp dụng ngay bằng cách dựng lại bộ nén. */
    fun applySettings(maxSize: Int, fps: Int, bitrate: Int) {
        val newMax = if (maxSize <= 0) 4096 else maxSize.coerceIn(480, 4096)
        val newFps = fps.coerceIn(15, 144)
        val newBitrate = bitrate.coerceIn(1_000_000, 80_000_000)
        if (newMax == MAX_LONG_SIDE && newFps == FPS && newBitrate == BITRATE) return
        MAX_LONG_SIDE = newMax
        FPS = newFps
        BITRATE = newBitrate
        settingsChanged = true
        println("SETTINGS max=$newMax fps=$newFps bitrate=$newBitrate")
        System.out.flush()
    }

    fun serve() {
        val server = LocalServerSocket(SOCKET_NAME)
        println("VIDEO_READY")
        System.out.flush()
        while (true) {
            val s = server.accept()
            try {
                stream(s)
            } catch (e: Throwable) {
                println("INFO video client closed: $e")
            } finally {
                try { s.close() } catch (e: Throwable) {}
            }
        }
    }

    private fun createMirror(name: String, w: Int, h: Int, surface: Surface): VirtualDisplay {
        val m = android.hardware.display.DisplayManager::class.java.getMethod(
            "createVirtualDisplay",
            String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Surface::class.java
        )
        return m.invoke(null, name, w, h, 0, surface) as VirtualDisplay
    }

    // mode 0: Baseline (không sắp xếp lại khung → bộ giải mã trên PC xuất hình ngay, độ trễ thấp nhất)
    // mode 1: mặc định của máy + tham số nâng cao; mode 2: cấu hình cơ bản
    private fun buildFormat(w: Int, h: Int, mode: Int): MediaFormat =
        MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 50_000L)
            if (mode == 0) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel52)
            }
            if (mode <= 1) {
                setInteger(MediaFormat.KEY_OPERATING_RATE, FPS)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                setFloat("max-fps-to-encoder", FPS.toFloat())
            }
        }

    private fun stream(s: LocalSocket) {
        val out = DataOutputStream(BufferedOutputStream(s.outputStream, 256 * 1024))

        // Bất kỳ gói nào PC gửi trên kênh này = xin khung hình chính
        thread(name = "video-reader", isDaemon = true) {
            try {
                val input = s.inputStream
                val buf = ByteArray(256)
                while (input.read(buf) >= 0) wantKeyFrame = true
            } catch (e: Throwable) {}
        }

        while (true) {
            // Mỗi vòng = một phiên nén theo kích thước màn hình hiện tại (xoay máy → vòng mới)
            val (realW, realH) = ShellInput.displaySize(true)
            val longSide = maxOf(realW, realH)
            val scale = if (longSide > MAX_LONG_SIDE) MAX_LONG_SIDE.toFloat() / longSide else 1f
            // Chỉ làm tròn xuống số chẵn (H.264 yêu cầu), không thu nhỏ
            val encW = ((realW * scale).toInt() / 2) * 2
            val encH = ((realH * scale).toInt() / 2) * 2

            var codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            var mode = 0
            while (true) {
                try {
                    codec.configure(buildFormat(encW, encH, mode), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    break
                } catch (e: Throwable) {
                    codec.release()
                    if (mode >= 2) throw e
                    mode++
                    codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                }
            }
            println("ENCODER mode=$mode ${encW}x$encH")
            System.out.flush()
            val surface = codec.createInputSurface()
            var display: VirtualDisplay? = null
            try {
                display = createMirror("phoneremote", encW, encH, surface)
                codec.start()

                val cfg = JSONObject()
                    .put("width", encW).put("height", encH)
                    .put("realWidth", realW).put("realHeight", realH)
                    .put("fps", FPS)
                    .toString().toByteArray(Charsets.UTF_8)
                out.writeByte(0); out.writeInt(cfg.size); out.write(cfg); out.flush()

                val info = MediaCodec.BufferInfo()
                var lastSizeCheck = System.currentTimeMillis()
                var sizeChanged = false
                settingsChanged = false
                while (!sizeChanged) {
                    if (settingsChanged) break
                    if (wantKeyFrame) {
                        wantKeyFrame = false
                        try {
                            codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
                        } catch (e: Throwable) {}
                    }
                    val i = codec.dequeueOutputBuffer(info, 100_000)
                    if (i >= 0) {
                        val buf = codec.getOutputBuffer(i)
                        if (buf != null && info.size > 0) {
                            val bytes = ByteArray(info.size)
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            buf.get(bytes)
                            var flags = 0
                            if (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) flags = flags or FLAG_KEY
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) flags = flags or FLAG_CONFIG
                            out.writeByte(1)
                            out.writeInt(bytes.size + 9)
                            out.writeLong(info.presentationTimeUs)
                            out.writeByte(flags)
                            out.write(bytes)
                            out.flush()
                        }
                        codec.releaseOutputBuffer(i, false)
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastSizeCheck > 400) {
                        lastSizeCheck = now
                        val (w, h) = ShellInput.displaySize(true)
                        if (w != realW || h != realH) sizeChanged = true
                    }
                }
            } finally {
                try { display?.release() } catch (e: Throwable) {}
                try { codec.stop() } catch (e: Throwable) {}
                try { codec.release() } catch (e: Throwable) {}
                try { surface.release() } catch (e: Throwable) {}
            }
        }
    }

    // ---------------- Tắt / bật tấm nền màn hình (máy vẫn chạy và vẫn truyền hình) ----------------

    private var cachedTokens: List<IBinder>? = null

    private fun displayTokens(): List<IBinder> {
        cachedTokens?.let { if (it.isNotEmpty()) return it }
        val tokens = ArrayList<IBinder>()
        try {
            // Android 14+: lớp DisplayControl nằm trong services.jar
            val cp = System.getenv("SYSTEMSERVERCLASSPATH")
            val factory = Class.forName("com.android.internal.os.ClassLoaderFactory")
            val create = factory.getDeclaredMethod(
                "createClassLoader", String::class.java, String::class.java, String::class.java,
                ClassLoader::class.java, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, String::class.java
            )
            val loader = create.invoke(null, cp, null, null, ClassLoader.getSystemClassLoader(), 0, true, null) as ClassLoader
            val dc = loader.loadClass("com.android.server.display.DisplayControl")
            val load = Runtime::class.java.getDeclaredMethod("loadLibrary0", Class::class.java, String::class.java)
            load.isAccessible = true
            load.invoke(Runtime.getRuntime(), dc, "android_servers")
            val ids = dc.getMethod("getPhysicalDisplayIds").invoke(null) as LongArray
            val getToken = dc.getMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType)
            for (id in ids) (getToken.invoke(null, id) as? IBinder)?.let { tokens.add(it) }
        } catch (e: Throwable) {
            println("INFO DisplayControl: $e")
        }
        if (tokens.isEmpty()) {
            try {
                val sc = Class.forName("android.view.SurfaceControl")
                (sc.getMethod("getInternalDisplayToken").invoke(null) as? IBinder)?.let { tokens.add(it) }
            } catch (e: Throwable) {
                println("INFO getInternalDisplayToken: $e")
            }
        }
        if (tokens.isNotEmpty()) cachedTokens = tokens
        return tokens
    }

    @Synchronized
    fun setScreenPower(on: Boolean): Boolean {
        return try {
            val tokens = displayTokens()
            if (tokens.isEmpty()) return false
            val sc = Class.forName("android.view.SurfaceControl")
            val m = sc.getMethod("setDisplayPowerMode", IBinder::class.java, Int::class.javaPrimitiveType)
            for (t in tokens) m.invoke(null, t, if (on) 2 else 0) // 2 = bình thường, 0 = tắt
            true
        } catch (e: Throwable) {
            println("WARN setScreenPower: $e")
            false
        }
    }
}
