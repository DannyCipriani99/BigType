package com.bigtype.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * يستقبل أمر الاستدعاء من تطبيقات الأتمتة (MacroDroid / Tasker …).
 * Action: com.bigtype.app.INVOKE
 */
class InvokeReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION = "com.bigtype.app.INVOKE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        BigTextAccessibilityService.instance?.invoke(0)
    }
}
