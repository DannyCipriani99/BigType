package com.bigtype.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/** إعدادات التطبيق والمسودات (SharedPreferences). */
object Prefs {
    private const val FILE = "bigtype_prefs"

    // أنماط الاستدعاء
    const val MODE_NEAR = 0      // زر بجانب الحقل
    const val MODE_KEYBOARD = 1  // زر ثابت فوق لوحة المفاتيح
    const val MODE_FREE = 2      // كرة عائمة قابلة للسحب
    const val MODE_HIDDEN = 3    // بدون زر، استدعاء يدوي
    const val MODE_TAP = 4       // بدون زر، يفتح فوراً عند الضغط على الحقل

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun register(c: Context, l: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp(c).registerOnSharedPreferenceChangeListener(l)

    fun unregister(c: Context, l: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp(c).unregisterOnSharedPreferenceChangeListener(l)

    fun isEnabled(c: Context) = sp(c).getBoolean("enabled", true)
    fun setEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("enabled", v).apply()

    fun skipNumbers(c: Context) = sp(c).getBoolean("skip_numbers", true)
    fun setSkipNumbers(c: Context, v: Boolean) = sp(c).edit().putBoolean("skip_numbers", v).apply()

    /** إظهار الزر (وضع بجانب الحقل) فقط عندما تكون لوحة المفاتيح ظاهرة. */
    fun imeOnly(c: Context) = sp(c).getBoolean("ime_only", false)
    fun setImeOnly(c: Context, v: Boolean) = sp(c).edit().putBoolean("ime_only", v).apply()

    fun bubbleLeft(c: Context) = sp(c).getBoolean("bubble_left", false)
    fun setBubbleLeft(c: Context, v: Boolean) = sp(c).edit().putBoolean("bubble_left", v).apply()

    fun triggerMode(c: Context) = sp(c).getInt("trigger_mode", MODE_HIDDEN)
    fun setTriggerMode(c: Context, v: Int) = sp(c).edit().putInt("trigger_mode", v).apply()

    fun textSize(c: Context) = sp(c).getInt("text_size", 20)
    fun setTextSize(c: Context, v: Int) = sp(c).edit().putInt("text_size", v).apply()

    /** 0 تلقائي، 1 من اليمين، 2 من اليسار. */
    fun dirMode(c: Context) = sp(c).getInt("dir_mode", 0)
    fun setDirMode(c: Context, v: Int) = sp(c).edit().putInt("dir_mode", v).apply()

    // ---- مظهر الكرة ----
    fun bubbleSize(c: Context) = sp(c).getInt("bubble_size", 28)
    fun setBubbleSize(c: Context, v: Int) = sp(c).edit().putInt("bubble_size", v).apply()

    fun bubbleOpacity(c: Context) = sp(c).getInt("bubble_opacity", 90)
    fun setBubbleOpacity(c: Context, v: Int) = sp(c).edit().putInt("bubble_opacity", v).apply()

    fun bubbleColor(c: Context) = sp(c).getInt("bubble_color", 0xFF1E6B52.toInt())
    fun setBubbleColor(c: Context, v: Int) = sp(c).edit().putInt("bubble_color", v).apply()

    fun bubbleIcon(c: Context) = sp(c).getBoolean("bubble_icon", true)
    fun setBubbleIcon(c: Context, v: Boolean) = sp(c).edit().putBoolean("bubble_icon", v).apply()

    fun bubbleBorder(c: Context) = sp(c).getBoolean("bubble_border", true)
    fun setBubbleBorder(c: Context, v: Boolean) = sp(c).edit().putBoolean("bubble_border", v).apply()

    fun bubbleRound(c: Context) = sp(c).getBoolean("bubble_round", false)
    fun setBubbleRound(c: Context, v: Boolean) = sp(c).edit().putBoolean("bubble_round", v).apply()

    /** "" بدون، أو image / gif / video. */
    fun mediaType(c: Context) = sp(c).getString("media_type", "") ?: ""
    fun mediaVersion(c: Context) = sp(c).getLong("media_version", 0L)
    fun setMedia(c: Context, type: String) =
        sp(c).edit().putString("media_type", type)
            .putLong("media_version", System.currentTimeMillis()).apply()

    fun freeX(c: Context) = sp(c).getInt("free_x", -1)
    fun freeY(c: Context) = sp(c).getInt("free_y", -1)
    fun setFreePos(c: Context, x: Int, y: Int) =
        sp(c).edit().putInt("free_x", x).putInt("free_y", y).apply()

    fun resetFreePos(c: Context) = sp(c).edit().remove("free_x").remove("free_y").apply()

    fun resetAppearance(c: Context) {
        sp(c).edit()
            .remove("bubble_size").remove("bubble_opacity").remove("bubble_color")
            .remove("bubble_icon").remove("bubble_border").remove("bubble_round")
            .putString("media_type", "").putLong("media_version", System.currentTimeMillis())
            .apply()
    }

    /** بصمة تتغير عند تغيّر أي إعداد يؤثر على مظهر الكرة. */
    fun styleSignature(c: Context): String = listOf(
        bubbleSize(c), bubbleOpacity(c), bubbleColor(c), bubbleIcon(c), bubbleBorder(c),
        bubbleRound(c), mediaType(c), mediaVersion(c)
    ).joinToString("|")

    // ---- التطبيقات المستثناة ----
    fun excluded(c: Context): MutableSet<String> =
        HashSet(sp(c).getStringSet("excluded", emptySet()) ?: emptySet())

    fun setExcluded(c: Context, set: Set<String>) =
        sp(c).edit().putStringSet("excluded", HashSet(set)).apply()

    fun addExcluded(c: Context, pkg: String) {
        val s = excluded(c)
        s.add(pkg)
        setExcluded(c, s)
    }

    fun isExcluded(c: Context, pkg: String) = excluded(c).contains(pkg)

    // ---- المسودات ----
    fun drafts(c: Context): List<String> {
        val raw = sp(c).getString("drafts", "[]") ?: "[]"
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map { a.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addDraft(c: Context, text: String) {
        if (text.isBlank()) return
        val list = drafts(c).toMutableList()
        list.remove(text)
        list.add(0, text)
        while (list.size > 20) list.removeAt(list.size - 1)
        sp(c).edit().putString("drafts", JSONArray(list).toString()).apply()
    }

    fun clearDrafts(c: Context) = sp(c).edit().putString("drafts", "[]").apply()
}
