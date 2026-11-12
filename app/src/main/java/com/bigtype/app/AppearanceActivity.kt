package com.bigtype.app

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import java.io.File

/** شاشة تخصيص مظهر الكرة العائمة مع معاينة حية. */
class AppearanceActivity : AppCompatActivity() {

    private lateinit var previewBox: FrameLayout
    private var preview: BubbleView? = null
    private lateinit var sizeLabel: TextView
    private lateinit var sizeSeek: SeekBar
    private lateinit var opLabel: TextView
    private lateinit var opSeek: SeekBar
    private lateinit var mediaLabel: TextView
    private lateinit var switchIcon: SwitchMaterial
    private lateinit var switchBorder: SwitchMaterial
    private lateinit var switchRound: SwitchMaterial

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importMedia(uri)
    }

    private val presets = intArrayOf(
        0xFF1E6B52.toInt(), 0xFF1976D2.toInt(), 0xFFD32F2F.toInt(), 0xFFF9A825.toInt(),
        0xFF7B1FA2.toInt(), 0xFFE91E63.toInt(), 0xFF00BCD4.toInt(), 0xFF607D8B.toInt(),
        0xFF000000.toInt(), 0xFFFFFFFF.toInt()
    )

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_appearance)

        previewBox = findViewById(R.id.previewBox)
        sizeLabel = findViewById(R.id.sizeLabel)
        sizeSeek = findViewById(R.id.sizeSeek)
        opLabel = findViewById(R.id.opLabel)
        opSeek = findViewById(R.id.opSeek)
        mediaLabel = findViewById(R.id.mediaLabel)
        switchIcon = findViewById(R.id.switchIcon)
        switchBorder = findViewById(R.id.switchBorder)
        switchRound = findViewById(R.id.switchRound)

        // الحجم: 8..96
        sizeSeek.max = 88
        sizeSeek.progress = Prefs.bubbleSize(this) - 8
        sizeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    Prefs.setBubbleSize(this@AppearanceActivity, p + 8)
                    refreshAll()
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        findViewById<MaterialButton>(R.id.btnSizeO).setOnClickListener { setSize(12) }
        findViewById<MaterialButton>(R.id.btnSizeS).setOnClickListener { setSize(22) }
        findViewById<MaterialButton>(R.id.btnSizeM).setOnClickListener { setSize(32) }
        findViewById<MaterialButton>(R.id.btnSizeL).setOnClickListener { setSize(48) }

        // الشفافية: 20..100
        opSeek.max = 80
        opSeek.progress = Prefs.bubbleOpacity(this) - 20
        opSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    Prefs.setBubbleOpacity(this@AppearanceActivity, p + 20)
                    refreshAll()
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        buildColorRow()
        findViewById<MaterialButton>(R.id.btnCustomColor).setOnClickListener { askCustomColor() }

        switchIcon.isChecked = Prefs.bubbleIcon(this)
        switchIcon.setOnCheckedChangeListener { _, v -> Prefs.setBubbleIcon(this, v); refreshAll() }
        switchBorder.isChecked = Prefs.bubbleBorder(this)
        switchBorder.setOnCheckedChangeListener { _, v -> Prefs.setBubbleBorder(this, v); refreshAll() }
        switchRound.isChecked = Prefs.bubbleRound(this)
        switchRound.setOnCheckedChangeListener { _, v -> Prefs.setBubbleRound(this, v); refreshAll() }

        findViewById<MaterialButton>(R.id.btnPickMedia).setOnClickListener {
            picker.launch(arrayOf("image/*", "video/*"))
        }
        findViewById<MaterialButton>(R.id.btnRemoveMedia).setOnClickListener {
            BubbleView.mediaFile(this).delete()
            Prefs.setMedia(this, "")
            refreshAll()
        }
        findViewById<MaterialButton>(R.id.btnResetPos).setOnClickListener {
            Prefs.resetFreePos(this)
            Toast.makeText(this, "أُعيد موضع الكرة الحرة", Toast.LENGTH_SHORT).show()
        }
        findViewById<MaterialButton>(R.id.btnReset).setOnClickListener {
            BubbleView.mediaFile(this).delete()
            Prefs.resetAppearance(this)
            switchIcon.isChecked = true
            switchBorder.isChecked = true
            switchRound.isChecked = false
            sizeSeek.progress = Prefs.bubbleSize(this) - 8
            opSeek.progress = Prefs.bubbleOpacity(this) - 20
            refreshAll()
        }

        refreshAll()
    }

    private fun setSize(dp: Int) {
        Prefs.setBubbleSize(this, dp)
        sizeSeek.progress = dp - 8
        refreshAll()
    }

    private fun refreshAll() {
        sizeLabel.text = "الحجم: ${Prefs.bubbleSize(this)}dp"
        opLabel.text = "الشفافية: ${Prefs.bubbleOpacity(this)}%"
        mediaLabel.text = when (Prefs.mediaType(this)) {
            "image" -> "الخلفية الحالية: صورة"
            "gif" -> "الخلفية الحالية: GIF متحرك"
            "video" -> "الخلفية الحالية: فيديو MP4"
            else -> "الخلفية الحالية: لون"
        }
        previewBox.removeAllViews()
        preview?.release()
        val size = BubbleView.touchPx(this)
        val bv = BubbleView(this)
        previewBox.addView(bv, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        preview = bv
    }

    private fun buildColorRow() {
        val row = findViewById<LinearLayout>(R.id.colorRow)
        row.removeAllViews()
        for (col in presets) {
            val v = View(this)
            val lp = LinearLayout.LayoutParams(dp(36), dp(36))
            lp.setMargins(dp(4), dp(4), dp(4), dp(4))
            v.layoutParams = lp
            val bg = GradientDrawable()
            bg.shape = GradientDrawable.OVAL
            bg.setColor(col)
            bg.setStroke(dp(1), 0x66888888)
            v.background = bg
            v.setOnClickListener {
                Prefs.setBubbleColor(this, col)
                refreshAll()
            }
            row.addView(v)
        }
    }

    private fun askCustomColor() {
        val et = EditText(this)
        et.hint = "#RRGGBB"
        et.setText(String.format("#%06X", 0xFFFFFF and Prefs.bubbleColor(this)))
        AlertDialog.Builder(this)
            .setTitle("لون مخصص")
            .setView(et)
            .setPositiveButton("تطبيق") { _, _ ->
                try {
                    var s = et.text.toString().trim()
                    if (!s.startsWith("#")) s = "#$s"
                    Prefs.setBubbleColor(this, Color.parseColor(s))
                    refreshAll()
                } catch (e: Exception) {
                    Toast.makeText(this, "صيغة اللون غير صحيحة", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun importMedia(uri: Uri) {
        val mime = contentResolver.getType(uri) ?: ""
        val type = when {
            mime == "image/gif" -> "gif"
            mime.startsWith("video/") -> "video"
            mime.startsWith("image/") -> "image"
            else -> null
        }
        if (type == null) {
            Toast.makeText(this, "نوع الملف غير مدعوم", Toast.LENGTH_SHORT).show()
            return
        }
        Thread {
            val tmp = File(filesDir, "bubble_media.tmp")
            try {
                val input = contentResolver.openInputStream(uri) ?: throw IllegalStateException()
                input.use { inp ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > 30L * 1024 * 1024) throw IllegalStateException("big")
                            out.write(buf, 0, n)
                        }
                    }
                }
                val dest = BubbleView.mediaFile(this)
                dest.delete()
                tmp.renameTo(dest)
                runOnUiThread {
                    Prefs.setMedia(this, type)
                    refreshAll()
                }
            } catch (e: Exception) {
                tmp.delete()
                runOnUiThread {
                    Toast.makeText(this, "تعذّر استيراد الملف (الحد الأقصى 30MB)", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    override fun onDestroy() {
        preview?.release()
        super.onDestroy()
    }
}
