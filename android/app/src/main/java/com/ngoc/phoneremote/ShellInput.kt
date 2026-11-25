package com.ngoc.phoneremote

import android.content.ClipData
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Method

/**
 * Trình điều khiển chạy bằng quyền "shell" (PC khởi động qua: adb shell app_process ...).
 * Bơm sự kiện chạm/phím thật vào hệ thống nên KHÔNG cần quyền Trợ năng
 * (vivo Safe Center tự tắt Trợ năng của app lạ).
 *
 * Nhận lệnh JSON, mỗi lệnh một dòng, qua socket cục bộ "phoneremote_input".
 */
object ShellInput {

    private const val SOCKET_NAME = "phoneremote_input"

    private lateinit var inputManager: Any
    private lateinit var injectMethod: Method
    private var displayGlobal: Any? = null
    private var getDisplayInfo: Method? = null
    private var clipboard: Any? = null
    private var setClip: Method? = null
    private var getClip: Method? = null
    private var getClipDesc: Method? = null
    @Volatile private var lastClipStamp = -1L
    @Volatile private var clientOut: java.io.OutputStream? = null

    private var width = 1080
    private var height = 1920
    private var sizeAt = 0L

    private var downTime = 0L
    private var touching = false
    private val kcm: KeyCharacterMap by lazy { KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD) }

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            @Suppress("DEPRECATION")
            Looper.prepareMainLooper()
        } catch (e: Throwable) {}

        try {
            init()
        } catch (e: Throwable) {
            println("ERROR init: $e")
            return
        }

        // Kênh video chạy ở luồng riêng
        Thread({
            try { ShellVideo.serve() } catch (e: Throwable) { println("ERROR video: $e") }
        }, "video-server").start()

        ShellAudio.prepareAppContext() // phải chạy trên luồng chính (đã có Looper)
        // Kênh âm thanh chạy ở luồng riêng
        Thread({
            try { ShellAudio.serve() } catch (e: Throwable) { println("WARN audio: $e") }
        }, "audio-server").start()

        Thread({
            try { watchClipboard() } catch (e: Throwable) { println("WARN clipboard watch: $e") }
        }, "clipboard-watch").start()

        val server = LocalServerSocket(SOCKET_NAME)
        println("READY")
        System.out.flush()
        while (true) {
            val s = server.accept()
            try {
                handle(s)
            } catch (e: Throwable) {
                // PC ngắt kết nối
            } finally {
                clientOut = null
                if (touching) { try { touch(MotionEvent.ACTION_UP, lastX, lastY) } catch (e: Throwable) {} }
                try { s.close() } catch (e: Throwable) {}
            }
        }
    }

    private fun init() {
        inputManager = try {
            Class.forName("android.hardware.input.InputManagerGlobal").getMethod("getInstance").invoke(null)!!
        } catch (e: Throwable) {
            Class.forName("android.hardware.input.InputManager").getMethod("getInstance").invoke(null)!!
        }
        injectMethod = inputManager.javaClass.getMethod(
            "injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType
        )

        try {
            val dmg = Class.forName("android.hardware.display.DisplayManagerGlobal").getMethod("getInstance").invoke(null)
            displayGlobal = dmg
            getDisplayInfo = dmg!!.javaClass.getMethod("getDisplayInfo", Int::class.javaPrimitiveType)
        } catch (e: Throwable) {
            println("WARN display: $e")
        }

        try {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java).invoke(null, "clipboard") as IBinder
            val c = Class.forName("android.content.IClipboard\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            clipboard = c
            setClip = c!!.javaClass.methods.firstOrNull { it.name == "setPrimaryClip" }
            getClip = c.javaClass.methods.firstOrNull { it.name == "getPrimaryClip" }
            getClipDesc = c.javaClass.methods.firstOrNull { it.name == "getPrimaryClipDescription" }
        } catch (e: Throwable) {
            println("WARN clipboard: $e")
        }
        refreshSize(true)
    }

    fun displaySize(force: Boolean = false): Pair<Int, Int> {
        refreshSize(force)
        return Pair(width, height)
    }

    @Synchronized
    private fun refreshSize(force: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - sizeAt < 400) return
        sizeAt = now
        try {
            val info = getDisplayInfo?.invoke(displayGlobal, 0) ?: return
            width = info.javaClass.getField("logicalWidth").getInt(info)
            height = info.javaClass.getField("logicalHeight").getInt(info)
        } catch (e: Throwable) {}
    }

    private fun inject(ev: InputEvent) {
        injectMethod.invoke(inputManager, ev, 0) // 0 = không chờ (async)
    }

    // ---------------- Chạm ----------------

    private var lastX = 0f
    private var lastY = 0f

    private fun touch(action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downTime = now
        lastX = x; lastY = y
        val props = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = if (action == MotionEvent.ACTION_UP) 0f else 1f
            size = 1f
        })
        val ev = MotionEvent.obtain(
            downTime, now, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0
        )
        inject(ev)
        ev.recycle()
    }

    /** Lăn chuột: sự kiện cuộn thật (mượt, tức thì) thay vì giả lập vuốt. */
    private fun scroll(x: Float, y: Float, v: Float) {
        val now = SystemClock.uptimeMillis()
        val props = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            setAxisValue(MotionEvent.AXIS_VSCROLL, v)
        })
        val ev = MotionEvent.obtain(
            now, now, MotionEvent.ACTION_SCROLL, 1, props, coords, 0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_MOUSE, 0
        )
        inject(ev)
        ev.recycle()
    }

    private fun px(n: Double) = (n * width).toFloat().coerceIn(0f, (width - 1).toFloat())
    private fun py(n: Double) = (n * height).toFloat().coerceIn(0f, (height - 1).toFloat())

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long) {
        if (touching) return
        val steps = (ms / 8).toInt().coerceIn(2, 200)
        touch(MotionEvent.ACTION_DOWN, x1, y1)
        for (i in 1..steps) {
            Thread.sleep(8)
            val f = i.toFloat() / steps
            touch(MotionEvent.ACTION_MOVE, x1 + (x2 - x1) * f, y1 + (y2 - y1) * f)
        }
        touch(MotionEvent.ACTION_UP, x2, y2)
    }

    // ---------------- Phím & chữ ----------------

    private fun key(code: Int) {
        val now = SystemClock.uptimeMillis()
        for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            inject(KeyEvent(now, now, action, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD))
        }
    }

    /** Gọi một hàm của IClipboard, tự điền tham số theo kiểu (chữ ký khác nhau giữa các đời Android). */
    private fun callClip(m: Method, clip: ClipData?): Any? {
        var stringSeen = 0
        val params = m.parameterTypes.map { t ->
            when {
                t == ClipData::class.java -> clip
                t == String::class.java -> if (stringSeen++ == 0) "com.android.shell" else null
                t == Int::class.javaPrimitiveType -> 0
                t == Boolean::class.javaPrimitiveType -> false
                else -> null
            }
        }.toTypedArray()
        return m.invoke(clipboard, *params)
    }

    private fun clipStamp(): Long {
        val m = getClipDesc ?: return -1
        return try {
            (callClip(m, null) as? android.content.ClipDescription)?.timestamp ?: -1
        } catch (e: Throwable) { -1 }
    }

    private fun sendToPc(json: JSONObject) {
        val o = clientOut ?: return
        try {
            synchronized(this) {
                o.write(json.toString().toByteArray(Charsets.UTF_8))
                o.write(10) // xuống dòng
                o.flush()
            }
        } catch (e: Throwable) {}
    }

    /** Theo dõi bộ nhớ tạm của điện thoại: có nội dung mới (copy/cut) thì báo cho PC. */
    private fun watchClipboard() {
        lastClipStamp = clipStamp()
        while (true) {
            Thread.sleep(400)
            if (clientOut == null) continue
            val stamp = clipStamp()
            if (stamp == lastClipStamp || stamp < 0) continue
            lastClipStamp = stamp
            try {
                val clip = callClip(getClip ?: continue, null) as? ClipData ?: continue
                if (clip.itemCount == 0) continue
                val item = clip.getItemAt(0)
                val uri = item.uri
                val text = item.text?.toString()
                if (uri != null) {
                    val mime = try { clip.description.getMimeType(0) } catch (e: Throwable) { "" }
                    sendToPc(JSONObject().put("clip", "uri").put("uri", uri.toString()).put("mime", mime))
                } else if (!text.isNullOrEmpty()) {
                    sendToPc(JSONObject().put("clip", "text").put("s", text))
                }
            } catch (e: Throwable) {
                println("WARN clipboard read: $e")
            }
        }
    }

    private fun setClipboardUri(uri: String, mime: String): Boolean {
        val m = setClip ?: return false
        return try {
            val clip = ClipData(
                android.content.ClipDescription("phoneremote", arrayOf(mime)),
                ClipData.Item(android.net.Uri.parse(uri))
            )
            callClip(m, clip)
            lastClipStamp = clipStamp()
            true
        } catch (e: Throwable) {
            println("WARN setClipboardUri: $e")
            false
        }
    }

    private fun setClipboard(text: String): Boolean {
        val m = setClip ?: return false
        try {
            callClip(m, ClipData.newPlainText("phoneremote", text))
            lastClipStamp = clipStamp()   // thay đổi do PC đặt → không báo ngược lại PC
            return true
        } catch (e: Throwable) {
            println("WARN setClipboard: $e")
            return false
        }
    }

    @Suppress("unused")
    private fun setClipboardOld(text: String): Boolean {
        val m = setClip ?: return false
        return try {
            val clip = ClipData.newPlainText("phoneremote", text)
            var stringSeen = 0
            val params = m.parameterTypes.map { t ->
                when {
                    t == ClipData::class.java -> clip
                    t == String::class.java -> if (stringSeen++ == 0) "com.android.shell" else null
                    t == Int::class.javaPrimitiveType -> 0
                    t == Boolean::class.javaPrimitiveType -> false
                    else -> null
                }
            }.toTypedArray()
            m.invoke(clipboard, *params)
            true
        } catch (e: Throwable) {
            println("WARN setClipboard: $e")
            false
        }
    }

    private fun typeText(text: String) {
        if (text.isEmpty()) return
        // Ký tự bàn phím thường (ASCII): bơm phím thật
        val events = try { kcm.getEvents(text.toCharArray()) } catch (e: Throwable) { null }
        if (events != null) {
            for (e in events) inject(e)
            return
        }
        // Chữ có dấu / tiếng Trung...: đặt vào bộ nhớ tạm rồi dán
        if (setClipboard(text)) {
            Thread.sleep(40)
            key(KeyEvent.KEYCODE_PASTE)
            return
        }
        // Dự phòng: gửi chuỗi ký tự trực tiếp
        @Suppress("DEPRECATION")
        inject(KeyEvent(SystemClock.uptimeMillis(), text, KeyCharacterMap.VIRTUAL_KEYBOARD, 0))
    }

    // ---------------- Vòng nhận lệnh ----------------

    private fun handle(s: LocalSocket) {
        lastClipStamp = clipStamp()   // không gửi nội dung đã có sẵn từ trước khi kết nối
        clientOut = s.outputStream
        val reader = BufferedReader(InputStreamReader(s.inputStream, Charsets.UTF_8))
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue
            try {
                val c = JSONObject(line)
                when (c.optString("t")) {
                    "down" -> {
                        refreshSize()
                        touching = true
                        touch(MotionEvent.ACTION_DOWN, px(c.optDouble("x")), py(c.optDouble("y")))
                    }
                    "move" -> if (touching) touch(MotionEvent.ACTION_MOVE, px(c.optDouble("x")), py(c.optDouble("y")))
                    "up" -> if (touching) {
                        touching = false
                        touch(MotionEvent.ACTION_UP, px(c.optDouble("x")), py(c.optDouble("y")))
                    }
                    "swipe" -> {
                        refreshSize()
                        swipe(px(c.optDouble("x")), py(c.optDouble("y")), px(c.optDouble("x2")), py(c.optDouble("y2")), c.optLong("ms", 120))
                    }
                    "scroll" -> {
                        refreshSize()
                        scroll(px(c.optDouble("x")), py(c.optDouble("y")), c.optDouble("v", 0.0).toFloat())
                    }
                    "clipdebug" -> {
                        // Chẩn đoán: đặt clipboard như thể điện thoại tự copy, rồi in ra những gì đọc lại được
                        try {
                            if (c.has("s")) callClip(setClip!!, ClipData.newPlainText("test", c.optString("s")))
                            val desc = try { callClip(getClipDesc!!, null) } catch (e: Throwable) { "EXC $e cause=${e.cause}" }
                            val clip = try { callClip(getClip!!, null) } catch (e: Throwable) { "EXC $e cause=${e.cause}" }
                            println("CLIPDEBUG stamp=" + clipStamp() + " last=" + lastClipStamp + " desc=" + desc + " clip=" + clip +
                                " getDescSig=" + getClipDesc?.parameterTypes?.joinToString { it.simpleName } +
                                " getSig=" + getClip?.parameterTypes?.joinToString { it.simpleName })
                        } catch (e: Throwable) {
                            println("CLIPDEBUG failed: $e")
                        }
                        System.out.flush()
                    }
                    "clip" -> setClipboard(c.optString("s"))
                    "clipuri" -> setClipboardUri(c.optString("uri"), c.optString("mime", "image/png"))
                    "text" -> typeText(c.optString("s"))
                    "bksp" -> key(KeyEvent.KEYCODE_DEL)
                    "enter" -> key(KeyEvent.KEYCODE_ENTER)
                    "keycode" -> key(c.optInt("code"))
                    "key" -> when (c.optString("k")) {
                        "back" -> key(KeyEvent.KEYCODE_BACK)
                        "home" -> key(KeyEvent.KEYCODE_HOME)
                        "recents" -> key(KeyEvent.KEYCODE_APP_SWITCH)
                        "notifications" -> key(KeyEvent.KEYCODE_NOTIFICATION)
                        "lock" -> key(KeyEvent.KEYCODE_POWER)
                    }
                    "video" -> ShellVideo.applySettings(c.optInt("maxSize", 0), c.optInt("fps", 120), c.optInt("bitrate", 32_000_000))
                    "screen" -> {
                        val ok = ShellVideo.setScreenPower(c.optBoolean("on", true))
                        println("SCREEN " + (if (c.optBoolean("on", true)) "on" else "off") + " ok=" + ok)
                        System.out.flush()
                    }
                    "vol" -> key(if (c.optInt("d") > 0) KeyEvent.KEYCODE_VOLUME_UP else KeyEvent.KEYCODE_VOLUME_DOWN)
                }
            } catch (e: Throwable) {
                println("WARN cmd: $e")
            }
        }
    }
}
