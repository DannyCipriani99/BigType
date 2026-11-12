package com.bigtype.app

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
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

        findViewById<SwitchMaterial>(R.id.switchEnabled).apply {
            isChecked = Prefs.isEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, v -> Prefs.setEnabled(this@MainActivity, v) }
        }

        findViewById<SwitchMaterial>(R.id.switchSkipNumbers).apply {
            isChecked = Prefs.skipNumbers(this@MainActivity)
            setOnCheckedChangeListener { _, v -> Prefs.setSkipNumbers(this@MainActivity, v) }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
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
