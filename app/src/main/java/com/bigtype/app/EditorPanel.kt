package com.bigtype.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.Spannable
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip

/**
 * لوحة الكتابة العائمة (نافذة Accessibility Overlay).
 * [onDone] يستلم النص عند النقل، أو null عند الإلغاء، ومعه خيار الضغط على Enter بعد النقل.
 */
class EditorPanel(
    private val service: BigTextAccessibilityService,
    private val ctx: Context,
    private val wm: WindowManager,
    private val originalText: String,
    selStart: Int,
    selEnd: Int,
    hint: String?,
    private val pkg: String?,
    private val onDone: (String?, Boolean) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())

    private val root: PanelRoot =
        LayoutInflater.from(ctx).inflate(R.layout.overlay_editor, null) as PanelRoot
    private val card: View = root.findViewById(R.id.card)
    private val editor: EditText = root.findViewById(R.id.editor)
    private val counter: TextView = root.findViewById(R.id.counter)
    private val btnUndo: View = root.findViewById(R.id.btnUndo)
    private val btnRedo: View = root.findViewById(R.id.btnRedo)
    private val searchBar: View = root.findViewById(R.id.searchBar)
    private val findInput: EditText = root.findViewById(R.id.findInput)
    private val replaceInput: EditText = root.findViewById(R.id.replaceInput)
    private val matchInfo: TextView = root.findViewById(R.id.matchInfo)
    private val searchError: TextView = root.findViewById(R.id.searchError)
    private val chipRegex: Chip = root.findViewById(R.id.chipRegex)
    private val chipCase: Chip = root.findViewById(R.id.chipCase)
    private val chipWord: Chip = root.findViewById(R.id.chipWord)

    private var done = false
    private var expanded = false

    // سجل التراجع/الإعادة
    private val history = ArrayList<String>()
    private var hIdx = 0
    private var restoring = false
    private val commitRunnable = Runnable { commitHistory() }

    // البحث
    private var matches: List<Match> = emptyList()
    private var cur = -1
    private val hlSpans = ArrayList<Any>()
    private val searchRunnable = Runnable { runSearch(keep = true, select = false) }

    init {
        root.onBack = { cancel() }
        applyCardHeight()

        editor.textSize = Prefs.textSize(ctx).toFloat()
        editor.setText(originalText)
        val len = editor.text.length
        editor.setSelection(selStart.coerceIn(0, len), selEnd.coerceIn(0, len))
        if (!hint.isNullOrBlank()) editor.hint = hint
        history.add(originalText)
        applyDirection()
        updateCounter()
        updateUndoState()

        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!restoring) {
                    handler.removeCallbacks(commitRunnable)
                    handler.postDelayed(commitRunnable, 500)
                }
                updateCounter()
                if (searchBar.visibility == View.VISIBLE) {
                    handler.removeCallbacks(searchRunnable)
                    handler.postDelayed(searchRunnable, 250)
                }
            }
        })

        findInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = runSearch(keep = false, select = true)
        })
        findInput.setOnEditorActionListener { _, _, _ -> step(1); true }

        val chipListener = { _: android.widget.CompoundButton, _: Boolean ->
            runSearch(keep = false, select = true)
        }
        chipRegex.setOnCheckedChangeListener(chipListener)
        chipCase.setOnCheckedChangeListener(chipListener)
        chipWord.setOnCheckedChangeListener(chipListener)

        root.findViewById<View>(R.id.btnPrev).setOnClickListener { step(-1) }
        root.findViewById<View>(R.id.btnNext).setOnClickListener { step(1) }
        root.findViewById<View>(R.id.btnReplace).setOnClickListener { replaceCurrent() }
        root.findViewById<View>(R.id.btnReplaceAll).setOnClickListener { replaceAll() }

        btnUndo.setOnClickListener { undo() }
        btnRedo.setOnClickListener { redo() }
        root.findViewById<View>(R.id.btnFind).setOnClickListener { toggleSearch() }
        root.findViewById<View>(R.id.btnMore).setOnClickListener { showMore(it) }
        root.findViewById<View>(R.id.btnExpand).setOnClickListener {
            expanded = !expanded
            applyCardHeight()
        }
        root.findViewById<View>(R.id.btnClose).setOnClickListener { cancel() }

        root.findViewById<MaterialButton>(R.id.btnSend).setOnClickListener { send(false) }
        val enterBtn = root.findViewById<MaterialButton>(R.id.btnSendEnter)
        if (Build.VERSION.SDK_INT >= 30) {
            enterBtn.visibility = View.VISIBLE
            enterBtn.setOnClickListener { send(true) }
        }
    }

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------------
    // العرض والإغلاق
    // ------------------------------------------------------------------

    fun show() {
        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            0,
            PixelFormat.TRANSLUCENT
        )
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        wm.addView(root, lp)
        editor.requestFocus()
        handler.postDelayed({
            val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
        }, 150)
    }

    /** إغلاق بلا أي استدعاء رجوع (عند إيقاف الخدمة). */
    fun forceClose() {
        if (done) return
        done = true
        handler.removeCallbacksAndMessages(null)
        try { wm.removeView(root) } catch (_: Exception) {}
    }

    private fun close(result: String?, enter: Boolean) {
        if (done) return
        done = true
        handler.removeCallbacksAndMessages(null)
        try { wm.removeView(root) } catch (_: Exception) {}
        onDone(result, enter)
    }

    private fun cancel() {
        val t = editor.text.toString()
        if (t != originalText && t.isNotBlank()) {
            Prefs.addDraft(ctx, t)
            Toast.makeText(ctx, "حُفظ النص في المسودات", Toast.LENGTH_SHORT).show()
        }
        close(null, false)
    }

    private fun send(enter: Boolean) {
        val t = editor.text.toString()
        // نحفظ الأصل لإمكان الاسترجاع إن أخطأت في استبدال
        if (originalText.isNotBlank() && originalText != t) Prefs.addDraft(ctx, originalText)
        close(t, enter)
    }

    private fun applyCardHeight() {
        val dm = ctx.resources.displayMetrics
        val landscape = dm.widthPixels > dm.heightPixels
        val frac = if (expanded || landscape) 0.92 else 0.52
        val lp = card.layoutParams as FrameLayout.LayoutParams
        lp.height = (dm.heightPixels * frac).toInt()
        card.layoutParams = lp
    }

    private fun applyDirection() {
        when (Prefs.dirMode(ctx)) {
            1 -> {
                editor.layoutDirection = View.LAYOUT_DIRECTION_RTL
                editor.textDirection = View.TEXT_DIRECTION_RTL
            }
            2 -> {
                editor.layoutDirection = View.LAYOUT_DIRECTION_LTR
                editor.textDirection = View.TEXT_DIRECTION_LTR
            }
            else -> {
                editor.layoutDirection = View.LAYOUT_DIRECTION_INHERIT
                editor.textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            }
        }
    }

    private fun updateCounter() {
        val t = editor.text
        val words = t.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
        val lines = if (t.isEmpty()) 0 else t.count { it == '\n' } + 1
        counter.text = "${t.length} حرف · $words كلمة · $lines سطر"
    }

    // ------------------------------------------------------------------
    // التراجع والإعادة
    // ------------------------------------------------------------------

    private fun commitHistory() {
        val t = editor.text.toString()
        if (t == history[hIdx]) return
        while (history.size > hIdx + 1) history.removeAt(history.size - 1)
        history.add(t)
        hIdx = history.size - 1
        if (history.size > 100) {
            history.removeAt(0)
            hIdx--
        }
        updateUndoState()
    }

    private fun commitNow() {
        handler.removeCallbacks(commitRunnable)
        commitHistory()
    }

    private fun updateUndoState() {
        btnUndo.alpha = if (hIdx > 0) 1f else 0.35f
        btnRedo.alpha = if (hIdx < history.size - 1) 1f else 0.35f
    }

    private fun restore(text: String) {
        restoring = true
        editor.setText(text)
        editor.setSelection(editor.text.length)
        restoring = false
        updateUndoState()
        if (searchBar.visibility == View.VISIBLE) runSearch(keep = false, select = false)
    }

    private fun undo() {
        commitNow()
        if (hIdx > 0) {
            hIdx--
            restore(history[hIdx])
        }
    }

    private fun redo() {
        commitNow()
        if (hIdx < history.size - 1) {
            hIdx++
            restore(history[hIdx])
        }
    }

    // ------------------------------------------------------------------
    // البحث والاستبدال
    // ------------------------------------------------------------------

    private fun opts() = SearchOptions(chipRegex.isChecked, chipCase.isChecked, chipWord.isChecked)

    private fun toggleSearch() {
        val show = searchBar.visibility != View.VISIBLE
        searchBar.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            val s = minOf(editor.selectionStart, editor.selectionEnd)
            val e = maxOf(editor.selectionStart, editor.selectionEnd)
            if (s >= 0 && e > s && e - s <= 60) {
                val sel = editor.text.subSequence(s, e).toString()
                if (!sel.contains('\n')) findInput.setText(sel)
            }
            findInput.requestFocus()
            findInput.setSelection(findInput.text.length)
        } else {
            clearHighlights()
            editor.requestFocus()
        }
    }

    private fun clearHighlights() {
        val ed = editor.text
        for (sp in hlSpans) ed.removeSpan(sp)
        hlSpans.clear()
    }

    private fun showErr(msg: String) {
        searchError.text = msg
        searchError.visibility = View.VISIBLE
    }

    private fun runSearch(keep: Boolean, select: Boolean) {
        searchError.visibility = View.GONE
        val q = findInput.text.toString()
        if (q.isEmpty()) {
            clearHighlights()
            matches = emptyList()
            cur = -1
            matchInfo.text = ""
            return
        }
        try {
            val p = Finder.compile(q, opts())
            matches = Finder.findAll(p, editor.text.toString())
        } catch (e: SearchTimeout) {
            fail("التعبير بطيء جداً")
            return
        } catch (e: Exception) {
            fail("خطأ في التعبير")
            return
        }
        if (matches.isEmpty()) {
            clearHighlights()
            cur = -1
            matchInfo.text = "0"
            return
        }
        if (!keep || cur !in matches.indices) {
            val pos = minOf(editor.selectionStart, editor.selectionEnd).coerceAtLeast(0)
            val idx = matches.indexOfFirst { it.start >= pos }
            cur = if (idx < 0) 0 else idx
        }
        highlight(select)
    }

    private fun fail(msg: String) {
        clearHighlights()
        matches = emptyList()
        cur = -1
        matchInfo.text = "!"
        showErr(msg)
    }

    private fun highlight(select: Boolean) {
        clearHighlights()
        val ed = editor.text
        val yellow = 0xFFFFE08A.toInt()
        val orange = 0xFFFF9F43.toInt()
        for ((i, m) in matches.withIndex()) {
            if (i >= 1500 && i != cur) continue
            if (m.end <= m.start || m.end > ed.length) continue
            val bg = BackgroundColorSpan(if (i == cur) orange else yellow)
            val fg = ForegroundColorSpan(Color.BLACK)
            ed.setSpan(bg, m.start, m.end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            ed.setSpan(fg, m.start, m.end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            hlSpans.add(bg)
            hlSpans.add(fg)
        }
        if (cur in matches.indices) {
            matchInfo.text = "${cur + 1}/${matches.size}"
            if (select) {
                val m = matches[cur]
                if (m.end <= ed.length) {
                    editor.setSelection(m.start, m.end)
                    editor.bringPointIntoView(m.start)
                }
            }
        }
    }

    private fun step(dir: Int) {
        if (matches.isEmpty()) return
        cur = (cur + dir + matches.size) % matches.size
        highlight(true)
    }

    private fun replaceCurrent() {
        val q = findInput.text.toString()
        if (q.isEmpty()) return
        if (cur !in matches.indices) {
            runSearch(keep = false, select = true)
            if (cur !in matches.indices) return
        }
        try {
            val p = Finder.compile(q, opts())
            val text = editor.text.toString()
            val r = Finder.replaceAt(
                p, text, matches[cur].start, replaceInput.text.toString(), chipRegex.isChecked
            ) ?: return
            commitNow()
            editor.text.replace(r.first, r.second, r.third)
            editor.setSelection(r.first + r.third.length)
            commitNow()
            runSearch(keep = false, select = true)
        } catch (e: SearchTimeout) {
            showErr("التعبير بطيء جداً")
        } catch (e: Exception) {
            showErr("خطأ في الاستبدال: ${e.message}")
        }
    }

    private fun replaceAll() {
        val q = findInput.text.toString()
        if (q.isEmpty()) return
        try {
            val p = Finder.compile(q, opts())
            val text = editor.text.toString()
            val count = Finder.findAll(p, text).size
            if (count == 0) {
                Toast.makeText(ctx, "لا توجد نتائج", Toast.LENGTH_SHORT).show()
                return
            }
            val res = Finder.replaceAll(p, text, replaceInput.text.toString(), chipRegex.isChecked)
            commitNow()
            clearHighlights()
            editor.text.replace(0, editor.text.length, res)
            commitNow()
            runSearch(keep = false, select = false)
            Toast.makeText(ctx, "تم استبدال $count", Toast.LENGTH_SHORT).show()
        } catch (e: SearchTimeout) {
            showErr("التعبير بطيء جداً")
        } catch (e: Exception) {
            showErr("خطأ في الاستبدال: ${e.message}")
        }
    }

    // ------------------------------------------------------------------
    // القوائم والأدوات
    // ------------------------------------------------------------------

    private fun transform(f: (String) -> String) {
        val ed = editor.text
        var s = editor.selectionStart
        var e = editor.selectionEnd
        if (s > e) { val t = s; s = e; e = t }
        if (s < 0 || s == e) { s = 0; e = ed.length }
        val part = ed.subSequence(s, e).toString()
        val out = f(part)
        if (out == part) {
            Toast.makeText(ctx, "لا تغيير", Toast.LENGTH_SHORT).show()
            return
        }
        commitNow()
        clearHighlights()
        ed.replace(s, e, out)
        editor.setSelection(s, s + out.length)
        commitNow()
    }

    private fun showMore(anchor: View) {
        val pm = PopupMenu(ctx, anchor)
        val m = pm.menu
        val dirNames = arrayOf("تلقائي", "من اليمين", "من اليسار")
        m.add(0, 1, 0, "نسخ الكل")
        m.add(0, 2, 0, "لصق")
        m.add(0, 3, 0, "تحديد الكل")
        m.add(0, 4, 0, "مسح الكل")
        m.add(0, 5, 0, "المسودات الأخيرة")
        m.add(0, 6, 0, "أدوات النص…")
        m.add(0, 7, 0, "اتجاه النص: ${dirNames[Prefs.dirMode(ctx)]}")
        m.add(0, 8, 0, "تكبير الخط")
        m.add(0, 9, 0, "تصغير الخط")
        if (pkg != null) m.add(0, 10, 0, "إخفاء الزر في هذا التطبيق")
        pm.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> copyAll()
                2 -> paste()
                3 -> { editor.requestFocus(); editor.selectAll() }
                4 -> { commitNow(); editor.text.clear(); commitNow() }
                5 -> showDrafts(anchor)
                6 -> showTools(anchor)
                7 -> {
                    Prefs.setDirMode(ctx, (Prefs.dirMode(ctx) + 1) % 3)
                    applyDirection()
                }
                8 -> changeSize(2)
                9 -> changeSize(-2)
                10 -> {
                    service.excludeApp(pkg!!)
                    cancel()
                }
            }
            true
        }
        pm.show()
    }

    private fun showTools(anchor: View) {
        val pm = PopupMenu(ctx, anchor)
        val tools: List<Pair<String, (String) -> String>> = listOf(
            "إزالة المسافات الزائدة" to TextTools::collapseSpaces,
            "إزالة الأسطر الفارغة" to TextTools::removeEmptyLines,
            "حذف الأسطر المكررة" to TextTools::dedupeLines,
            "ترتيب الأسطر أبجدياً" to TextTools::sortLines,
            "عكس ترتيب الأسطر" to TextTools::reverseLines,
            "دمج الأسطر في سطر واحد" to TextTools::joinLines,
            "ترقيم الأسطر" to TextTools::numberLines,
            "أحرف كبيرة (English)" to TextTools::upper,
            "أحرف صغيرة (English)" to TextTools::lower,
            "أول حرف كبير (English)" to TextTools::title,
            "إزالة التشكيل والتطويل" to TextTools::removeDiacritics,
            "توحيد الألف والياء" to TextTools::normalizeArabic,
            "الأرقام إلى 0123" to TextTools::toWesternDigits,
            "الأرقام إلى ٠١٢٣" to TextTools::toArabicDigits
        )
        for ((i, t) in tools.withIndex()) pm.menu.add(0, i, 0, t.first)
        pm.setOnMenuItemClickListener { item ->
            transform(tools[item.itemId].second)
            true
        }
        pm.show()
    }

    private fun showDrafts(anchor: View) {
        val drafts = Prefs.drafts(ctx)
        if (drafts.isEmpty()) {
            Toast.makeText(ctx, "لا توجد مسودات", Toast.LENGTH_SHORT).show()
            return
        }
        val pm = PopupMenu(ctx, anchor)
        for ((i, d) in drafts.withIndex()) {
            val one = d.replace('\n', ' ').trim()
            pm.menu.add(0, i, 0, if (one.length > 40) one.take(40) + "…" else one)
        }
        pm.setOnMenuItemClickListener { item ->
            commitNow()
            editor.text.replace(0, editor.text.length, drafts[item.itemId])
            commitNow()
            true
        }
        pm.show()
    }

    private fun copyAll() {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("BigType", editor.text.toString()))
        Toast.makeText(ctx, "تم النسخ", Toast.LENGTH_SHORT).show()
    }

    private fun paste() {
        try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip
            val t = if (clip != null && clip.itemCount > 0) {
                clip.getItemAt(0).coerceToText(ctx)?.toString()
            } else null
            if (t.isNullOrEmpty()) {
                Toast.makeText(ctx, "الحافظة فارغة", Toast.LENGTH_SHORT).show()
                return
            }
            val s = minOf(editor.selectionStart, editor.selectionEnd).coerceAtLeast(0)
            val e = maxOf(editor.selectionStart, editor.selectionEnd).coerceAtLeast(0)
            commitNow()
            editor.text.replace(s, e, t)
            commitNow()
        } catch (e: Exception) {
            Toast.makeText(ctx, "تعذّر اللصق، استخدم الضغط المطوّل", Toast.LENGTH_SHORT).show()
        }
    }

    private fun changeSize(delta: Int) {
        val n = (Prefs.textSize(ctx) + delta).coerceIn(12, 40)
        Prefs.setTextSize(ctx, n)
        editor.textSize = n.toFloat()
    }
}
