package com.ngoc.phoneremote

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Build
import android.os.Process

// Thu am thanh dang phat tren dien thoai (REMOTE_SUBMIX) bang quyen shell va gui cho PC.
// Khi dang thu, am thanh khong phat ra loa dien thoai nua ma chi phat tren PC.
// Du lieu: PCM 16 bit, 48000 Hz, stereo, gui tho qua socket phoneremote_audio.
object ShellAudio {

    private const val SOCKET_NAME = "phoneremote_audio"
    const val SAMPLE_RATE = 48000
    private const val CHUNK_BYTES = 1920 // 10 ms (48000 * 2 kênh * 2 byte / 100)

    /** Ngữ cảnh giả để hệ thống ghi nhận âm thanh được thu bởi "com.android.shell". */
    private class FakeContext(base: Context?) : ContextWrapper(base) {
        override fun getPackageName(): String = "com.android.shell"
        override fun getOpPackageName(): String = "com.android.shell"
        override fun getApplicationContext(): Context = this
        override fun getAttributionSource(): AttributionSource =
            AttributionSource.Builder(Process.SHELL_UID).setPackageName("com.android.shell").build()
        override fun getDeviceId(): Int = 0
    }

    private var fakeContext: FakeContext = FakeContext(null)

    /**
     * Tiến trình chạy bằng app_process không có "ứng dụng hiện tại", trong khi AudioRecord cần nó.
     * Dựng một ActivityThread + Application tối thiểu mang danh com.android.shell.
     */
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    fun prepareAppContext() {
        try {
            val atClass = Class.forName("android.app.ActivityThread")
            val ctor = atClass.getDeclaredConstructor()
            ctor.isAccessible = true
            val at = ctor.newInstance()
            atClass.getDeclaredField("sCurrentActivityThread").apply { isAccessible = true }.set(null, at)

            // Ngữ cảnh hệ thống thật làm nền (để tra PackageManager...)
            try {
                val sys = atClass.getDeclaredMethod("getSystemContext").apply { isAccessible = true }.invoke(at) as Context
                fakeContext = FakeContext(sys)
            } catch (e: Throwable) {
                println("WARN getSystemContext: $e cause=${e.cause}")
            }

            val bindClass = Class.forName("android.app.ActivityThread\$AppBindData")
            val bindCtor = bindClass.getDeclaredConstructor()
            bindCtor.isAccessible = true
            val bind = bindCtor.newInstance()
            val appInfo = android.content.pm.ApplicationInfo().apply { packageName = "com.android.shell" }
            bindClass.getDeclaredField("appInfo").apply { isAccessible = true }.set(bind, appInfo)
            atClass.getDeclaredField("mBoundApplication").apply { isAccessible = true }.set(at, bind)

            val app = android.app.Application()
            ContextWrapper::class.java.getDeclaredField("mBase").apply { isAccessible = true }.set(app, fakeContext)
            atClass.getDeclaredField("mInitialApplication").apply { isAccessible = true }.set(at, app)
        } catch (e: Throwable) {
            println("WARN prepareAppContext: $e cause=${e.cause}")
        }
    }

    fun serve() {
        val server = LocalServerSocket(SOCKET_NAME)
        println("AUDIO_READY")
        System.out.flush()
        while (true) {
            val s = server.accept()
            try {
                stream(s)
            } catch (e: Throwable) {
                println("INFO audio closed: $e at " + e.stackTrace.take(6).joinToString(" < "))
                System.out.flush()
            } finally {
                try { s.close() } catch (e: Throwable) {}
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun createRecorder(): AudioRecord {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val b = AudioRecord.Builder()
        if (Build.VERSION.SDK_INT >= 31) b.setContext(fakeContext)
        b.setAudioSource(MediaRecorder.AudioSource.REMOTE_SUBMIX)
        b.setAudioFormat(format)
        b.setBufferSizeInBytes(maxOf(minBuf, CHUNK_BYTES) * 4)
        return b.build()
    }

    private fun stream(s: LocalSocket) {
        val rec = createRecorder()
        try {
            rec.startRecording()
            println("AUDIO_START state=" + rec.recordingState)
            System.out.flush()
            val out = s.outputStream
            val buf = ByteArray(CHUNK_BYTES)
            while (true) {
                val n = rec.read(buf, 0, buf.size)
                if (n < 0) throw IllegalStateException("AudioRecord.read = $n")
                if (n > 0) out.write(buf, 0, n)
            }
        } finally {
            try { rec.stop() } catch (e: Throwable) {}
            try { rec.release() } catch (e: Throwable) {}
        }
    }
}
