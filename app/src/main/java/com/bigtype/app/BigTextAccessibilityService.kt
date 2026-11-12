package com.bigtype.app

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast

/**
 * يراقب الحقول النصية في كل التطبيقات؛ عند تركيز حقل قابل للتعديل يعرض زراً عائماً صغيراً بجانبه.
 * الضغط على الزر يفتح [EditorPanel] (نافذة عائمة)، وزر النقل يكتب النص في الحقل الأصلي.
 */
class BigTextAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: BigTextAccessibilityService? = null
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private lateinit var uiCtx: Context

    private var bubble: View? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private var panel: EditorPanel? = null

    private var targetNode: AccessibilityNodeInfo? = null
    private var targetPkg: String? = null
    private var candidate: AccessibilityNodeInfo? = null

    private val refreshRunnable = Runnable { refreshBubble() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        uiCtx = ContextThemeWrapper(this, R.style.Theme_BigType)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || panel != null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val src = event.source
                if (src != null && looksEditable(src)) candidate = src
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> candidate = null
        }
        scheduleRefresh(150)
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        handler.removeCallbacksAndMessages(null)
        hideBubble()
        panel?.forceClose()
        panel = null
        instance = null
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------------
    // كشف الحقل المركّز
    // ------------------------------------------------------------------

    private fun looksEditable(n: AccessibilityNodeInfo): Boolean =
        n.isEditable || (n.className?.toString()?.contains("EditText") == true)

    private fun isNumericField(n: AccessibilityNodeInfo): Boolean {
        val cls = n.inputType and InputType.TYPE_MASK_CLASS
        return cls == InputType.TYPE_CLASS_NUMBER ||
            cls == InputType.TYPE_CLASS_PHONE ||
            cls == InputType.TYPE_CLASS_DATETIME
    }

    private fun isImeVisible(): Boolean =
        try {
            windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        } catch (e: Exception) {
            true
        }

    private fun scheduleRefresh(delayMs: Long) {
        handler.removeCallbacks(refreshRunnable)
        handler.postDelayed(refreshRunnable, delayMs)
    }

    private fun resolveFocusedEditable(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow
        val f = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (f != null && looksEditable(f)) return f
        val c = candidate
        if (c != null && c.refresh() && looksEditable(c) && c.isFocused && c.isVisibleToUser) return c
        return null
    }

    private fun refreshBubble() {
        if (panel != null) return
        if (!Prefs.isEnabled(this)) { hideBubble(); return }

        val node = resolveFocusedEditable()
        if (node == null) { hideBubble(); return }

        val pkg = node.packageName?.toString()
        if (pkg == null || Prefs.isExcluded(this, pkg)) { hideBubble(); return }
        if (node.isPassword) { hideBubble(); return }
        if (Prefs.skipNumbers(this) && isNumericField(node)) { hideBubble(); return }
        if (Prefs.imeOnly(this) && !isImeVisible()) { hideBubble(); return }
        if (!node.isVisibleToUser) { hideBubble(); return }

        val r = Rect()
        node.getBoundsInScreen(r)
        if (r.width() < dp(48) || r.height() < dp(16)) { hideBubble(); return }

        targetNode = node
        targetPkg = pkg
        showBubbleAt(r)
    }

    // ------------------------------------------------------------------
    // الزر العائم
    // ------------------------------------------------------------------

    private fun showBubbleAt(r: Rect) {
        val size = dp(40)
        val margin = dp(4)
        val dm = resources.displayMetrics

        var x = if (Prefs.bubbleLeft(this)) r.left + margin else r.right - size - margin
        var y = if (r.height() > size * 3) r.top + margin else r.centerY() - size / 2
        x = x.coerceIn(0, (dm.widthPixels - size).coerceAtLeast(0))
        y = y.coerceIn(0, (dm.heightPixels - size).coerceAtLeast(0))

        val existing = bubble
        val lp = bubbleLp
        if (existing != null && lp != null) {
            if (lp.x != x || lp.y != y) {
                lp.x = x
                lp.y = y
                try { wm.updateViewLayout(existing, lp) } catch (_: Exception) {}
            }
            return
        }

        val v = LayoutInflater.from(uiCtx).inflate(R.layout.overlay_bubble, null)
        v.setOnClickListener { openEditor() }
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = x
        params.y = y
        try {
            wm.addView(v, params)
            bubble = v
            bubbleLp = params
        } catch (e: Exception) {
            bubble = null
            bubbleLp = null
        }
    }

    private fun hideBubble() {
        val v = bubble ?: return
        try { wm.removeView(v) } catch (_: Exception) {}
        bubble = null
        bubbleLp = null
    }

    // ------------------------------------------------------------------
    // فتح المحرر وإعادة النص
    // ------------------------------------------------------------------

    private fun openEditor() {
        val node = targetNode ?: return
        node.refresh()

        val showingHint = Build.VERSION.SDK_INT >= 26 && node.isShowingHintText
        val text = if (showingHint) "" else node.text?.toString().orEmpty()
        var s = node.textSelectionStart
        var e = node.textSelectionEnd
        if (s < 0 || e < 0) { s = text.length; e = text.length }
        val hint = if (Build.VERSION.SDK_INT >= 26) node.hintText?.toString() else null

        hideBubble()
        val p = EditorPanel(
            service = this,
            ctx = uiCtx,
            wm = wm,
            originalText = text,
            selStart = s,
            selEnd = e,
            hint = hint,
            pkg = targetPkg
        ) { result, enter ->
            panel = null
            if (result != null) applyText(result, enter) else scheduleRefresh(300)
        }
        panel = p
        try {
            p.show()
        } catch (ex: Exception) {
            panel = null
            Toast.makeText(this, "تعذّر فتح المحرر", Toast.LENGTH_SHORT).show()
        }
    }

    fun excludeApp(pkg: String) {
        Prefs.addExcluded(this, pkg)
        Toast.makeText(this, "سيُخفى الزر في هذا التطبيق (يمكن التراجع من الإعدادات)", Toast.LENGTH_LONG).show()
    }

    private fun applyText(text: String, enter: Boolean) {
        handler.postDelayed({ trySetText(text, enter, 1) }, 250)
    }

    private fun trySetText(text: String, enter: Boolean, attempt: Int) {
        var node: AccessibilityNodeInfo? = null

        val saved = targetNode
        if (saved != null && saved.refresh() && looksEditable(saved)) node = saved

        if (node == null) {
            val f = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (f != null && looksEditable(f) && !f.isPassword &&
                f.packageName?.toString() == targetPkg
            ) node = f
        }

        if (node != null && setText(node, text)) {
            val n: AccessibilityNodeInfo = node
            if (enter && Build.VERSION.SDK_INT >= 30) {
                handler.postDelayed({
                    n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                }, 150)
            }
            scheduleRefresh(600)
            return
        }

        if (attempt < 6) {
            handler.postDelayed({ trySetText(text, enter, attempt + 1) }, 250)
        } else {
            fallbackPaste(text)
        }
    }

    private fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (ok) {
            val sel = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, text.length)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, text.length)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
        }
        return ok
    }

    /** خطة بديلة: نسخ النص للحافظة ومحاولة اللصق في الحقل. */
    private fun fallbackPaste(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("BigType", text))

        val node = targetNode
        var pasted = false
        if (node != null && node.refresh() && looksEditable(node)) {
            val sel = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                putInt(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,
                    node.text?.length ?: 0
                )
            }
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
            pasted = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }
        if (!pasted) {
            Toast.makeText(this, "تعذّر الإدخال المباشر. نُسخ النص — الصقه في الحقل.", Toast.LENGTH_LONG).show()
        }
        scheduleRefresh(600)
    }
}
