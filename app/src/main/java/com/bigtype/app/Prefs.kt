package com.bigtype.app

import android.content.Context

/** إعدادات بسيطة تُحفظ في SharedPreferences. */
object Prefs {
    private const val FILE = "bigtype_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_SKIP_NUMBERS = "skip_numbers"

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isEnabled(c: Context) = sp(c).getBoolean(KEY_ENABLED, true)
    fun setEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean(KEY_ENABLED, v).apply()

    fun skipNumbers(c: Context) = sp(c).getBoolean(KEY_SKIP_NUMBERS, true)
    fun setSkipNumbers(c: Context, v: Boolean) = sp(c).edit().putBoolean(KEY_SKIP_NUMBERS, v).apply()
}
