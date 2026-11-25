package com.ngoc.phoneremote

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private val reqCapture = 1001
    private lateinit var status: TextView
    private lateinit var btnStart: Button
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad * 2, pad, pad)
        }
        val title = TextView(this).apply {
            text = "Phone Remote"
            textSize = 26f
            setTextColor(Color.BLACK)
        }
        status = TextView(this).apply {
            textSize = 15f
            setPadding(0, pad, 0, pad)
        }
        btnStart = Button(this).apply {
            setOnClickListener {
                if (CaptureService.running) {
                    startService(Intent(this@MainActivity, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
                    handler.postDelayed({ refresh() }, 300)
                } else {
                    val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    @Suppress("DEPRECATION")
                    startActivityForResult(mpm.createScreenCaptureIntent(), reqCapture)
                }
            }
        }
        val btnAcc = Button(this).apply {
            text = "Mở cài đặt Trợ năng (bật Phone Remote)"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        val help = TextView(this).apply {
            textSize = 13f
            setPadding(0, pad, 0, 0)
            text = "1. Bấm \"Bắt đầu\" rồi chọn chia sẻ toàn bộ màn hình.\n" +
                "2. Cắm cáp USB, mở app Phone Remote trên PC và bấm Kết nối.\n" +
                "Không cần bật Trợ năng: PC điều khiển qua cáp USB (Gỡ lỗi USB)."
        }

        root.addView(title)
        root.addView(status)
        root.addView(btnStart)
        root.addView(btnAcc)
        root.addView(help)
        setContentView(root)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
        maybeAutoStart(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private var autoStarted = false

    /** PC mở app kèm "autostart": tự bắt đầu truyền màn hình rồi lui về nền. */
    private fun maybeAutoStart(i: Intent?) {
        if (i == null || !i.getBooleanExtra("autostart", false)) return
        i.removeExtra("autostart")
        if (CaptureService.running) { moveTaskToBack(true); return }
        autoStarted = true
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), reqCapture)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun refresh() {
        val acc = InputService.instance != null
        val cap = CaptureService.running
        val pc = CaptureService.clientConnected
        status.text = "Trợ năng (điều khiển): " + (if (acc) "ĐÃ BẬT" else "CHƯA BẬT") + "\n" +
            "Truyền màn hình: " + (if (cap) "ĐANG CHẠY" else "ĐANG TẮT") + "\n" +
            "PC: " + (if (pc) "ĐÃ KẾT NỐI" else "chưa kết nối")
        btnStart.text = if (cap) "Dừng truyền màn hình" else "Bắt đầu truyền màn hình"
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != reqCapture) return
        if (resultCode == RESULT_OK && data != null) {
            val i = Intent(this, CaptureService::class.java)
                .putExtra(CaptureService.EXTRA_CODE, resultCode)
                .putExtra(CaptureService.EXTRA_DATA, data)
            startForegroundService(i)
            handler.postDelayed({ refresh() }, 500)
            if (autoStarted) {
                autoStarted = false
                handler.postDelayed({ moveTaskToBack(true) }, 400)
            }
        }
    }
}
