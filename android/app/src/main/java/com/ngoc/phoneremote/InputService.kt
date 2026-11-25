package com.ngoc.phoneremote

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Thực hiện chạm / vuốt / nhập chữ theo lệnh từ PC.
 * Chạm "sống": mỗi đoạn di chuyển là một stroke nối tiếp (continueStroke) nên kéo/cuộn theo tay ngay lập tức.
 */
class InputService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: InputService? = null
        private const val SEGMENT_MS = 16L
    }

    private val handler = Handler(Looper.getMainLooper())

    // Trạng thái chạm đang diễn ra (chỉ dùng trên main thread)
    private var stroke: GestureDescription.StrokeDescription? = null
    private var active = false
    private var busy = false
    private var pendingUp = false
    private var lastX = 0f
    private var lastY = 0f
    private var targetX = 0f
    private var targetY = 0f

    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // ---------------- Chạm sống ----------------

    fun touchDown(x: Float, y: Float) = handler.post {
        if (active) return@post
        active = true
        pendingUp = false
        lastX = x; lastY = y; targetX = x; targetY = y
        val path = Path().apply { moveTo(x, y); lineTo(x, y) }
        val s = GestureDescription.StrokeDescription(path, 0, SEGMENT_MS, true)
        stroke = s
        dispatch(s)
    }

    fun touchMove(x: Float, y: Float) = handler.post {
        if (!active) return@post
        targetX = x; targetY = y
        if (!busy) step()
    }

    fun touchUp(x: Float, y: Float) = handler.post {
        if (!active) return@post
        targetX = x; targetY = y
        pendingUp = true
        if (!busy) step()
    }

    private fun step() {
        if (!active) return
        val prev = stroke ?: return
        val path = Path().apply { moveTo(lastX, lastY); lineTo(targetX, targetY) }
        val willContinue = !pendingUp
        val next = try {
            prev.continueStroke(path, 0, SEGMENT_MS, willContinue)
        } catch (e: Exception) {
            resetTouch(); return
        }
        lastX = targetX; lastY = targetY
        stroke = next
        if (!willContinue) active = false
        dispatch(next)
    }

    private fun dispatch(s: GestureDescription.StrokeDescription) {
        busy = true
        val gesture = GestureDescription.Builder().addStroke(s).build()
        val ok = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                busy = false
                if (active) step() else stroke = null
            }

            override fun onCancelled(g: GestureDescription?) {
                resetTouch()
            }
        }, handler)
        if (!ok) resetTouch()
    }

    private fun resetTouch() {
        busy = false
        active = false
        pendingUp = false
        stroke = null
    }

    /** Vuốt một lần (dùng cho lăn chuột). */
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long) = handler.post {
        if (active || busy) return@post
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val s = GestureDescription.StrokeDescription(path, 0, ms.coerceIn(20, 3000))
        dispatchGesture(GestureDescription.Builder().addStroke(s).build(), null, null)
    }

    // ---------------- Phím hệ thống ----------------

    fun globalKey(name: String) = handler.post {
        when (name) {
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            "lock" -> if (Build.VERSION.SDK_INT >= 28) performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        }
    }

    // ---------------- Nhập chữ (hỗ trợ Unicode: tiếng Việt, tiếng Trung...) ----------------

    private fun focusedInput(): AccessibilityNodeInfo? =
        try { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } catch (e: Exception) { null }

    private fun currentText(node: AccessibilityNodeInfo): String {
        if (node.isShowingHintText) return ""
        return node.text?.toString() ?: ""
    }

    private fun setText(node: AccessibilityNodeInfo, text: String, cursor: Int) {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        val sel = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
    }

    fun inputText(s: String) = handler.post {
        val node = focusedInput() ?: return@post
        val text = currentText(node)
        var start = node.textSelectionStart
        var end = node.textSelectionEnd
        if (start < 0 || end < 0 || start > text.length || end > text.length) {
            start = text.length; end = text.length
        }
        if (start > end) { val t = start; start = end; end = t }
        val newText = text.substring(0, start) + s + text.substring(end)
        setText(node, newText, start + s.length)
    }

    fun backspace() = handler.post {
        val node = focusedInput() ?: return@post
        val text = currentText(node)
        if (text.isEmpty()) return@post
        var start = node.textSelectionStart
        var end = node.textSelectionEnd
        if (start < 0 || end < 0 || start > text.length || end > text.length) {
            start = text.length; end = text.length
        }
        if (start > end) { val t = start; start = end; end = t }
        if (start == end) {
            if (start == 0) return@post
            // Xoá trọn một ký tự (kể cả ký tự ghép 2 đơn vị UTF-16)
            val from = text.offsetByCodePoints(start, -1)
            setText(node, text.substring(0, from) + text.substring(end), from)
        } else {
            setText(node, text.substring(0, start) + text.substring(end), start)
        }
    }

    fun enter() = handler.post {
        val node = focusedInput() ?: return@post
        if (Build.VERSION.SDK_INT >= 30) {
            val ok = node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            if (ok) return@post
        }
        // Dự phòng: chèn xuống dòng
        val text = currentText(node)
        val pos = node.textSelectionEnd.let { if (it < 0 || it > text.length) text.length else it }
        setText(node, text.substring(0, pos) + "\n" + text.substring(pos), pos + 1)
    }
}
