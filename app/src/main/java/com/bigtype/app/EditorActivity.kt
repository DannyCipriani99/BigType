package com.bigtype.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

/** نافذة الكتابة الكبيرة التي تظهر عند الضغط على أي حقل نص. */
class EditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TEXT = "extra_text"
        const val EXTRA_HINT = "extra_hint"
    }

    private lateinit var editText: EditText
    private lateinit var counter: TextView
    private var originalText = ""
    private var sent = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)

        editText = findViewById(R.id.editText)
        counter = findViewById(R.id.counter)
        val sendButton = findViewById<MaterialButton>(R.id.sendButton)

        originalText = intent.getStringExtra(EXTRA_TEXT).orEmpty()
        val hint = intent.getStringExtra(EXTRA_HINT)

        editText.setText(originalText)
        editText.setSelection(editText.text.length)
        if (!hint.isNullOrBlank()) editText.hint = hint
        updateCounter()

        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = updateCounter()
        })

        sendButton.setOnClickListener { sendBack() }
        findViewById<MaterialButton>(R.id.clearButton).setOnClickListener {
            editText.setText("")
        }
        findViewById<MaterialButton>(R.id.closeButton).setOnClickListener { confirmClose() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = confirmClose()
        })
    }

    private fun updateCounter() {
        counter.text = getString(R.string.chars_count, editText.text.length)
    }

    private fun sendBack() {
        val text = editText.text.toString()
        val service = BigTextAccessibilityService.instance
        if (service != null) {
            sent = true
            service.applyText(text)
        } else {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("BigType", text))
            Toast.makeText(this, R.string.service_not_running, Toast.LENGTH_LONG).show()
        }
        finish()
    }

    private fun confirmClose() {
        if (editText.text.toString() == originalText) {
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.discard_title)
            .setMessage(R.string.discard_message)
            .setPositiveButton(R.string.discard_yes) { _, _ -> finish() }
            .setNegativeButton(R.string.discard_no, null)
            .show()
    }

    override fun onDestroy() {
        BigTextAccessibilityService.instance?.onEditorClosed()
        super.onDestroy()
    }
}
