package com.bigtype.app

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * يستمع للضغط على الحقول النصية في أي تطبيق، فيفتح [EditorActivity]،
 * ثم يعيد كتابة النص في الحقل الأصلي عند الضغط على زر النقل.
 */
class BigTextAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: BigTextAccessibilityService? = null
            private set

        private const val SUPPRESS_AFTER_CLOSE_MS = 1500L
        private const val MAX_ATTEMPTS = 5
        private const val RETRY_DELAY_MS = 250L
    }

    private val handler = Handler(Looper.getMainLooper())

    /** الحقل الذي ضغط عليه المستخدم (نسخة منه لنعود إليها). */
    private var targetNode: AccessibilityNodeInfo? = null
    private var targetPackage: String? = null
    private var targetViewId: String? = null

    private var editorOpen = false
    private var suppressUntil = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED) return
        if (!Prefs.isEnabled(this)) return
        if (editorOpen || SystemClock.uptimeMillis() < suppressUntil) return

        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return // لا نتدخل في تطبيقنا نفسه

        val source = event.source ?: return
        if (!source.isEditable) return
        if (source.isPassword) return // لا نلمس كلمات المرور أبداً
        if (!source.isEnabled) return
        if (Prefs.skipNumbers(this) && isNumericField(source)) return

        openEditor(source, pkg)
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // فتح المحرر
    // ------------------------------------------------------------------

    private fun isNumericField(node: AccessibilityNodeInfo): Boolean {
        val cls = node.inputType and InputType.TYPE_MASK_CLASS
        return cls == InputType.TYPE_CLASS_NUMBER ||
            cls == InputType.TYPE_CLASS_PHONE ||
            cls == InputType.TYPE_CLASS_DATETIME
    }

    @Suppress("DEPRECATION")
    private fun openEditor(source: AccessibilityNodeInfo, pkg: String) {
        targetNode = AccessibilityNodeInfo.obtain(source)
        targetPackage = pkg
        targetViewId = source.viewIdResourceName

        val showingHint = Build.VERSION.SDK_INT >= 26 && source.isShowingHintText
        val text = if (showingHint) "" else source.text?.toString().orEmpty()
        val hint = if (Build.VERSION.SDK_INT >= 26) source.hintText?.toString() else null

        editorOpen = true
        val intent = Intent(this, EditorActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
            putExtra(EditorActivity.EXTRA_TEXT, text)
            putExtra(EditorActivity.EXTRA_HINT, hint)
        }
        startActivity(intent)
    }

    /** يستدعيها المحرر عند إغلاقه (سواء بالنقل أو بالإلغاء). */
    fun onEditorClosed() {
        editorOpen = false
        suppressUntil = SystemClock.uptimeMillis() + SUPPRESS_AFTER_CLOSE_MS
    }

    // ------------------------------------------------------------------
    // إعادة النص إلى الحقل الأصلي
    // ------------------------------------------------------------------

    /** يستدعيها المحرر عند الضغط على زر النقل. */
    fun applyText(text: String) {
        suppressUntil = SystemClock.uptimeMillis() + SUPPRESS_AFTER_CLOSE_MS + 1500L
        // ننتظر قليلاً حتى تعود نافذة التطبيق الأصلي إلى الواجهة.
        handler.postDelayed({ trySetText(text, 1) }, 300L)
    }

    private fun trySetText(text: String, attempt: Int) {
        var done = false

        // 1) الحقل الذي عليه التركيز الآن في النافذة النشطة
        val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.isEditable && matchesTarget(focused)) {
            done = setText(focused, text)
        }

        // 2) النسخة المحفوظة من الحقل بعد تحديثها
        if (!done) {
            val saved = targetNode
            if (saved != null && saved.refresh() && saved.isEditable) {
                done = setText(saved, text)
            }
        }

        if (done) {
            targetNode = null
            return
        }

        if (attempt < MAX_ATTEMPTS) {
            handler.postDelayed({ trySetText(text, attempt + 1) }, RETRY_DELAY_MS)
        } else {
            fallbackPaste(text)
        }
    }

    private fun matchesTarget(node: AccessibilityNodeInfo): Boolean {
        if (node.isPassword) return false
        val pkgOk = targetPackage == null || node.packageName?.toString() == targetPackage
        val id = targetViewId
        val idOk = id == null || node.viewIdResourceName == null || node.viewIdResourceName == id
        return pkgOk && idOk
    }

    private fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (ok) {
            // ضع المؤشر في نهاية النص
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
        copyToClipboard(text)

        val node = targetNode
        var pasted = false
        if (node != null && node.refresh() && node.isEditable) {
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
        targetNode = null
        if (!pasted) {
            Toast.makeText(this, R.string.fallback_copied, Toast.LENGTH_LONG).show()
        }
    }

    fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("BigType", text))
    }
}
