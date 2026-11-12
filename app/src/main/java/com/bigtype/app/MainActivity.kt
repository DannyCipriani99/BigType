package com.bigtype.app

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)

        findViewById<MaterialButton>(R.id.openSettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        bindSwitch(R.id.switchEnabled, Prefs.isEnabled(this)) { Prefs.setEnabled(this, it) }
        bindSwitch(R.id.switchSkipNumbers, Prefs.skipNumbers(this)) { Prefs.setSkipNumbers(this, it) }
        bindSwitch(R.id.switchKeyboardAfter, Prefs.keyboardAfter(this)) { Prefs.setKeyboardAfter(this, it) }
        bindSwitch(R.id.switchImeOnly, Prefs.imeOnly(this)) { Prefs.setImeOnly(this, it) }
        bindSwitch(R.id.switchLeft, Prefs.bubbleLeft(this)) { Prefs.setBubbleLeft(this, it) }

        val modeIds = intArrayOf(R.id.modeNear, R.id.modeKeyboard, R.id.modeFree, R.id.modeHidden, R.id.modeTap)
        val group = findViewById<RadioGroup>(R.id.modeGroup)
        group.check(modeIds[Prefs.triggerMode(this).coerceIn(0, modeIds.size - 1)])
        group.setOnCheckedChangeListener { _, id ->
            val idx = modeIds.indexOf(id)
            if (idx >= 0) Prefs.setTriggerMode(this, idx)
        }
        findViewById<MaterialButton>(R.id.btnAppearance).setOnClickListener {
            startActivity(Intent(this, AppearanceActivity::class.java))
        }

        findViewById<MaterialButton>(R.id.btnExcluded).setOnClickListener { showExcluded() }
        findViewById<MaterialButton>(R.id.btnClearDrafts).setOnClickListener {
            Prefs.clearDrafts(this)
            Toast.makeText(this, "تم مسح المسودات", Toast.LENGTH_SHORT).show()
        }
    }

    private fun bindSwitch(id: Int, initial: Boolean, onChange: (Boolean) -> Unit) {
        findViewById<SwitchMaterial>(id).apply {
            isChecked = initial
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
    }

    private fun appLabel(pkg: String): String =
        try {
            val info = packageManager.getApplicationInfo(pkg, 0)
            "${packageManager.getApplicationLabel(info)} ($pkg)"
        } catch (e: Exception) {
            pkg
        }

    private fun showExcluded() {
        val list = Prefs.excluded(this).toList().sorted()
        if (list.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("التطبيقات المستثناة")
                .setMessage("لا توجد تطبيقات مستثناة.\n\nلإخفاء الزر في تطبيق ما: افتح المحرر فيه ← زر ⋮ ← «إخفاء الزر في هذا التطبيق».")
                .setPositiveButton("حسناً", null)
                .show()
            return
        }
        val labels = list.map { appLabel(it) }.toTypedArray()
        val checked = BooleanArray(list.size) { true }
        AlertDialog.Builder(this)
            .setTitle("التطبيقات المستثناة (ألغِ التحديد لإرجاع الزر)")
            .setMultiChoiceItems(labels, checked) { _, i, c -> checked[i] = c }
            .setPositiveButton("حفظ") { _, _ ->
                Prefs.setExcluded(this, list.filterIndexed { i, _ -> checked[i] }.toSet())
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        val on = isServiceEnabled()
        status.setText(if (on) R.string.status_on else R.string.status_off)
        status.setTextColor(
            ContextCompat.getColor(
                this,
                if (on) android.R.color.holo_green_dark else android.R.color.holo_red_dark
            )
        )
    }

    private fun isServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val me = ComponentName(this, BigTextAccessibilityService::class.java).flattenToString()
        return enabled.split(':').any { it.equals(me, ignoreCase = true) }
    }
}
