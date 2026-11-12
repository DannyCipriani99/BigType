package com.bigtype.app

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import kotlin.math.hypot

/**
 * يراقب الحقول النصية في كل التطبيقات ويعرض زر استدعاء (حسب النمط المختار)،
 * ثم يفتح [EditorPanel] ويعيد النص إلى الحقل الأصلي.
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

    private var bubble: BubbleView? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private var bubbleSig = ""
    private var panel: EditorPanel? = null

    private var targetNode: AccessibilityNodeInfo? = null
    private var targetPkg: String? = null
    private var candidate: AccessibilityNodeInfo? = null

    private var armedUntil = 0L
    private var suppressUntil = 0L
    private var lastWindowChange = 0L
    private var lastInvoke = 0L

    private val refreshRunnable = Runnable { refreshBubble() }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        scheduleRefresh(50)
    }

    /** استدعاء من تطبيقات الأتمتة (MacroDroid / Tasker …) عبر Broadcast. */
    private val invokeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            invoke(0)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        uiCtx = ContextThemeWrapper(this, R.style.Theme_BigType)
        Prefs.register(this, prefsListener)
        try {
            val f = IntentFilter(InvokeReceiver.ACTION)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(invokeReceiver, f, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(invokeReceiver, f)
            }
        } catch (_: Exception) {}
        scheduleRefresh(100)
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        handler.removeCallbacksAndMessages(null)
        try { Prefs.unregister(this, prefsListener) } catch (_: Exception) {}
        try { unregisterReceiver(invokeReceiver) } catch (_: Exception) {}
        hideBubble()
        panel?.forceClose()
        panel = null
        instance = null
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun now() = SystemClock.uptimeMillis()

    // ------------------------------------------------------------------
    // الأحداث
    // ------------------------------------------------------------------

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || panel != null) return

        val type = event.eventType
        when (type) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val src = event.source
                if (src != null && looksEditable(src)) candidate = src
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                candidate = null
                lastWindowChange = now()
            }
        }

        // فتح فوري عند الضغط على الحقل (نمط «عند الضغط» أو بعد الاستدعاء اليدوي)
        val armed = now() < armedUntil
        val tapMode = Prefs.triggerMode(this) == Prefs.MODE_TAP
        if ((armed || tapMode) &&
            (type == AccessibilityEvent.TYPE_VIEW_CLICKED || type == AccessibilityEvent.TYPE_VIEW_FOCUSED) &&
            now() >= suppressUntil
        ) {
            val src = event.source
            val autoFocus = type == AccessibilityEvent.TYPE_VIEW_FOCUSED && !armed &&
                now() - lastWindowChange < 1200
            if (src != null && looksEditable(src) && !autoFocus && canOpen(src, armed)) {
                armedUntil = 0L
                openEditorFor(src)
                return
            }
        }

        scheduleRefresh(150)
    }

    private fun canOpen(n: AccessibilityNodeInfo, explicit: Boolean): Boolean {
        if (!n.isEnabled || n.isPassword) return false
        if (explicit) return true
        val pkg = n.packageName?.toString() ?: return false
        if (Prefs.isExcluded(this, pkg)) return false
        if (Prefs.skipNumbers(this) && isNumericField(n)) return false
        return true
    }

    private fun looksEditable(n: AccessibilityNodeInfo): Boolean =
        n.isEditable || (n.className?.toString()?.contains("EditText") == true)

    private fun isNumericField(n: AccessibilityNodeInfo): Boolean {
        val cls = n.inputType and InputType.TYPE_MASK_CLASS
        return cls == InputType.TYPE_CLASS_NUMBER ||
            cls == InputType.TYPE_CLASS_PHONE ||
            cls == InputType.TYPE_CLASS_DATETIME
    }

    private fun imeWindow(): AccessibilityWindowInfo? =
        try {
            windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        } catch (e: Exception) {
            null
        }

    private fun scheduleRefresh(delayMs: Long) {
        handler.removeCallbacks(refreshRunnable)
        handler.postDelayed(refreshRunnable, delayMs)
    }

    private fun resolveFocusedEditable(): AccessibilityNodeInfo? {
        val f = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (f != null && looksEditable(f)) return f
        val c = candidate
        if (c != null && c.refresh() && looksEditable(c) && c.isFocused && c.isVisibleToUser) return c
        return null
    }

    // ------------------------------------------------------------------
    // الاستدعاء اليدوي (Broadcast / Activity من تطبيقات الأتمتة، أو الكرة)
    // ------------------------------------------------------------------

    private fun appRoots(): List<AccessibilityNodeInfo> {
        val roots = try {
            windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .mapNotNull { it.root }
        } catch (e: Exception) {
            emptyList()
        }
        return roots.ifEmpty { listOfNotNull(rootInActiveWindow) }
    }

    private fun findFocusedAnywhere(): AccessibilityNodeInfo? {
        for (r in appRoots()) {
            val f = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (f != null && looksEditable(f)) return f
        }
        val c = candidate
        if (c != null && c.refresh() && looksEditable(c) && c.isFocused) return c
        return null
    }

    private fun collectEditables(): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        appRoots().forEach { queue.add(it) }
        var visited = 0
        while (queue.isNotEmpty() && visited < 1500) {
            val n = queue.removeFirst()
            visited++
            if (looksEditable(n) && n.isVisibleToUser && n.isEnabled && !n.isPassword) out.add(n)
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { queue.add(it) }
            }
        }
        return out
    }

    /**
     * يفتح المحرر للحقل المركّز، أو للحقل الوحيد الظاهر، وإلا ينتظر أن تضغط على الحقل المطلوب.
     */
    fun invoke(delayMs: Long) {
        val t = now()
        if (t - lastInvoke < 400) return   // تجاهل الاستدعاء المكرر
        lastInvoke = t
        handler.postDelayed({ doInvoke() }, delayMs)
    }

    private fun doInvoke() {
        if (panel != null) return

        val focused = findFocusedAnywhere()
        if (focused != null) {
            if (focused.isPassword) {
                Toast.makeText(this, "حقول كلمات المرور غير مدعومة", Toast.LENGTH_SHORT).show()
                return
            }
            openEditorFor(focused)
            return
        }

        val editables = collectEditables()
        if (editables.size == 1) {
            openEditorFor(editables[0])
            return
        }

        armedUntil = now() + 12_000
        Toast.makeText(
            this,
            if (editables.isEmpty()) "لا توجد حقول ظاهرة. اضغط على الحقل عند ظهوره"
            else "اضغط على الحقل الذي تريد الكتابة فيه",
            Toast.LENGTH_SHORT
        ).show()
    }

    // ------------------------------------------------------------------
    // الزر العائم حسب النمط
    // ------------------------------------------------------------------

    private fun refreshBubble() {
        if (panel != null) return
        if (!Prefs.isEnabled(this)) { hideBubble(); return }

        when (Prefs.triggerMode(this)) {
            Prefs.MODE_NEAR -> refreshNearField()
            Prefs.MODE_KEYBOARD -> refreshAboveKeyboard()
            Prefs.MODE_FREE -> refreshFree()
            else -> hideBubble()
        }
    }

    private fun refreshNearField() {
        val node = resolveFocusedEditable()
        if (node == null) { hideBubble(); return }

        val pkg = node.packageName?.toString()
        if (pkg == null || Prefs.isExcluded(this, pkg)) { hideBubble(); return }
        if (node.isPassword) { hideBubble(); return }
        if (Prefs.skipNumbers(this) && isNumericField(node)) { hideBubble(); return }
        if (Prefs.imeOnly(this) && imeWindow() == null) { hideBubble(); return }
        if (!node.isVisibleToUser) { hideBubble(); return }

        val r = Rect()
        node.getBoundsInScreen(r)
        if (r.width() < dp(48) || r.height() < dp(16)) { hideBubble(); return }

        targetNode = node
        targetPkg = pkg

        val size = BubbleView.touchPx(this)
        val margin = dp(4)
        val x = if (Prefs.bubbleLeft(this)) r.left + margin else r.right - size - margin
        val y = if (r.height() > size * 3) r.top + margin else r.centerY() - size / 2
        placeBubble(x, y, size)
    }

    private fun refreshAboveKeyboard() {
        val ime = imeWindow()
        if (ime == null) { hideBubble(); return }
        val focused = resolveFocusedEditable()
        if (focused != null) {
            val pkg = focused.packageName?.toString()
            if (focused.isPassword || (pkg != null && Prefs.isExcluded(this, pkg))) {
                hideBubble(); return
            }
        }
        val r = Rect()
        ime.getBoundsInScreen(r)
        val size = BubbleView.touchPx(this)
        val dm = resources.displayMetrics
        val x = if (Prefs.bubbleLeft(this)) dp(8) else dm.widthPixels - size - dp(8)
        val y = r.top - size - dp(4)
        placeBubble(x, y, size)
    }

    private fun refreshFree() {
        val pkg = rootInActiveWindow?.packageName?.toString()
        if (pkg != null && Prefs.isExcluded(this, pkg)) { hideBubble(); return }
        val size = BubbleView.touchPx(this)
        val dm = resources.displayMetrics
        var x = Prefs.freeX(this)
        var y = Prefs.freeY(this)
        if (x < 0 || y < 0) {
            x = dm.widthPixels - size - dp(4)
            y = dm.heightPixels / 2
        }
        placeBubble(x, y, size)
    }

    private fun placeBubble(xIn: Int, yIn: Int, size: Int) {
        val dm = resources.displayMetrics
        val x = xIn.coerceIn(0, (dm.widthPixels - size).coerceAtLeast(0))
        val y = yIn.coerceIn(0, (dm.heightPixels - size).coerceAtLeast(0))

        // تغيّر المظهر → أعد بناء الكرة
        val sig = Prefs.styleSignature(this)
        if (bubble != null && sig != bubbleSig) hideBubble()

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

        val v = BubbleView(uiCtx)
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
        attachTouch(v, params)
        try {
            wm.addView(v, params)
            bubble = v
            bubbleLp = params
            bubbleSig = sig
        } catch (e: Exception) {
            v.release()
            bubble = null
            bubbleLp = null
        }
    }

    /** نقرة = استدعاء، وسحب = تحريك (في نمط الكرة الحرة). */
    private fun attachTouch(v: BubbleView, lp: WindowManager.LayoutParams) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = lp.x
                    startY = lp.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && hypot(dx, dy) > dp(8)) moved = true
                    if (moved && Prefs.triggerMode(this) == Prefs.MODE_FREE) {
                        val dm = resources.displayMetrics
                        lp.x = (startX + dx).toInt().coerceIn(0, (dm.widthPixels - lp.width).coerceAtLeast(0))
                        lp.y = (startY + dy).toInt().coerceIn(0, (dm.heightPixels - lp.height).coerceAtLeast(0))
                        try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        onBubbleTap()
                    } else if (Prefs.triggerMode(this) == Prefs.MODE_FREE) {
                        Prefs.setFreePos(this, lp.x, lp.y)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun hideBubble() {
        val v = bubble ?: return
        try { wm.removeView(v) } catch (_: Exception) {}
        v.release()
        bubble = null
        bubbleLp = null
    }

    private fun onBubbleTap() {
        if (Prefs.triggerMode(this) == Prefs.MODE_NEAR && targetNode != null) {
            openEditor()
        } else {
            invoke(0)
        }
    }

    // ------------------------------------------------------------------
    // فتح المحرر وإعادة النص
    // ------------------------------------------------------------------

    private fun openEditorFor(node: AccessibilityNodeInfo) {
        if (panel != null) return
        targetNode = node
        targetPkg = node.packageName?.toString()
        openEditor()
    }

    private fun openEditor() {
        if (panel != null) return
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
            suppressUntil = now() + 1800
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
            suppressUntil = now() + 2500
            if (!enter && Prefs.keyboardAfter(this)) {
                handler.postDelayed({ showKeyboardFor(n) }, 300)
            }
            scheduleRefresh(900)
            return
        }

        if (attempt < 6) {
            handler.postDelayed({ trySetText(text, enter, attempt + 1) }, 250)
        } else {
            fallbackPaste(text)
        }
    }

    /**
     * يعيد التركيز للحقل ثم ينقر عليه: الحقول الأصلية (TextView) تفتح لوحة المفاتيح
     * عند استقبال ACTION_CLICK وهي مركّزة.
     */
    private fun showKeyboardFor(node: AccessibilityNodeInfo) {
        try {
            if (!node.refresh()) return
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            handler.postDelayed({
                try {
                    node.refresh()
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                } catch (_: Exception) {}
            }, 120)
        } catch (_: Exception) {}
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
