package com.bigtype.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * نشاط شفاف يستدعي المحرر ثم يغلق نفسه. يُستخدم كأيقونة «فتح المحرر» في الشاشة الرئيسية،
 * ومن مربع الإشعارات السريعة. يترك الخدمة تنتظر قليلاً حتى يعود التركيز للتطبيق الأصلي.
 */
class InvokeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = BigTextAccessibilityService.instance
        if (s == null) {
            Toast.makeText(this, "فعّل خدمة المحرر الكبير أولاً", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java))
        } else {
            s.invoke(350)
        }
        finish()
        overridePendingTransition(0, 0)
    }
}
