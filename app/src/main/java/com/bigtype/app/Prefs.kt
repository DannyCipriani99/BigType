package com.bigtype.app

import android.content.Context
import org.json.JSONArray

/** إعدادات التطبيق والمسودات (SharedPreferences). */
object Prefs {
    private const val FILE = "bigtype_prefs"

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isEnabled(c: Context) = sp(c).getBoolean("enabled", true)
    fun setEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("enabled", v).apply()

    fun skipNumbers(c: Context) = sp(c).getBoolean("skip_numbers", true)
    fun setSkipNumbers(c: Context, v: Boolean) = sp(c).edit().putBoolean("skip_numbers", v).apply()

    /** إظهار الزر فقط عندما تكون لوحة المفاتيح ظاهرة. */
    fun imeOnly(c: Context) = sp(c).getBoolean("ime_only", false)
    fun setImeOnly(c: Context, v: Boolean) = sp(c).edit().putBoolean("ime_only", v).apply()

    fun bubbleLeft(c: Context) = sp(c).getBoolean("bubble_left", false)
    fun setBubbleLeft(c: Context, v: Boolean) = sp(c).edit().putBoolean("bubble_left", v).apply()

    fun textSize(c: Context) = sp(c).getInt("text_size", 20)
    fun setTextSize(c: Context, v: Int) = sp(c).edit().putInt("text_size", v).apply()

    /** 0 تلقائي، 1 من اليمين، 2 من اليسار. */
    fun dirMode(c: Context) = sp(c).getInt("dir_mode", 0)
    fun setDirMode(c: Context, v: Int) = sp(c).edit().putInt("dir_mode", v).apply()

    // ---- التطبيقات المستثناة ----
    fun excluded(c: Context): Set<String> =
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
