package com.example.ble_viewer

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

class ForgotPasswordActivity : AppCompatActivity() {

    companion object {
        private const val PREFS = "SolematePrefs"
        private const val KEY_AUTH_PROVIDER = "auth_provider"
        private const val KEY_GOOGLE_EMAIL = "google_email"
        private const val PROVIDER_GOOGLE = "google"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_forgot_password)

        if (LanguagePickerHelper.consumeTransitionFlag(this)) {
            val content = findViewById<View>(android.R.id.content)
            content.alpha = 0f
            content.animate().alpha(1f).setDuration(280L).start()
        }

        val identifierEdit = findViewById<EditText>(R.id.reset_identifier_edittext)
        val passwordEdit = findViewById<EditText>(R.id.reset_password_edittext)
        val confirmPasswordEdit = findViewById<EditText>(R.id.reset_confirm_password_edittext)
        val saveButton = findViewById<MaterialButton>(R.id.reset_password_button)
        val cancelLink = findViewById<TextView>(R.id.cancel_reset_link)
        val messageView = findViewById<TextView>(R.id.reset_message)
        val languageButton = findViewById<ImageButton>(R.id.language_button)

        setupPasswordToggle(passwordEdit)
        setupPasswordToggle(confirmPasswordEdit)

        languageButton.setOnClickListener {
            LanguagePickerHelper.show(this)
        }

        cancelLink.setOnClickListener {
            finish()
        }

        saveButton.setOnClickListener {
            val identifier = identifierEdit.text.toString().trim()
            val newPassword = passwordEdit.text.toString()
            val confirmPassword = confirmPasswordEdit.text.toString()

            val complexityError = when {
                newPassword.length >= 8 && !newPassword.any { it.isUpperCase() }      -> getString(R.string.password_needs_uppercase)
                newPassword.length >= 8 && !newPassword.any { it.isLowerCase() }      -> getString(R.string.password_needs_lowercase)
                newPassword.length >= 8 && !newPassword.any { it.isDigit() }          -> getString(R.string.password_needs_number)
                newPassword.length >= 8 && !newPassword.any { !it.isLetterOrDigit() } -> getString(R.string.password_needs_special)
                else -> null
            }
            when {
                identifier.isEmpty() || newPassword.isEmpty() || confirmPassword.isEmpty() -> {
                    messageView.visibility = View.VISIBLE
                    messageView.setText(R.string.toast_fill_all_fields)
                }
                newPassword.length < 8 -> {
                    messageView.visibility = View.VISIBLE
                    messageView.setText(R.string.toast_password_min_length)
                }
                complexityError != null -> {
                    messageView.visibility = View.VISIBLE
                    messageView.text = complexityError
                }
                confirmPassword.isBlank() -> {
                    messageView.visibility = View.VISIBLE
                    messageView.setText(R.string.confirm_password_required)
                }
                newPassword != confirmPassword -> {
                    messageView.visibility = View.VISIBLE
                    messageView.setText(R.string.toast_passwords_no_match)
                }
                !canResetForIdentifier(identifier) -> {
                    messageView.visibility = View.VISIBLE
                    messageView.text = getString(R.string.toast_forgot_password_account_not_found)
                }
                else -> {
                    val storedPassword = PasswordSecurity.createStoredPassword(newPassword)
                    getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit()
                        .putString("password", storedPassword.hash)
                        .putString("password_salt", storedPassword.salt)
                        .apply()

                    Toast.makeText(this, getString(R.string.toast_forgot_password_updated), Toast.LENGTH_SHORT).show()
                    startActivity(Intent(this, LoginActivity::class.java))
                    finish()
                }
            }
        }
    }

    private fun canResetForIdentifier(identifier: String): Boolean {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val authProvider = prefs.getString(KEY_AUTH_PROVIDER, "local")

        if (authProvider == PROVIDER_GOOGLE) {
            return false
        }

        val savedUsername = prefs.getString("username", "")?.trim().orEmpty()
        val savedEmail = prefs.getString(KEY_GOOGLE_EMAIL, "")?.trim().orEmpty()

        return identifier.equals(savedUsername, ignoreCase = true) ||
            (savedEmail.isNotEmpty() && identifier.equals(savedEmail, ignoreCase = true))
    }

    private fun setupPasswordToggle(editText: EditText) {
        var isVisible = false
        editText.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                val drawableEnd = 2
                val endDrawable = editText.compoundDrawablesRelative[drawableEnd] ?: return@setOnTouchListener false
                val iconWidth = endDrawable.bounds.width()
                val isRtl = editText.layoutDirection == View.LAYOUT_DIRECTION_RTL
                val touchedIcon = if (isRtl) {
                    event.x <= (editText.paddingStart + iconWidth)
                } else {
                    event.x >= (editText.width - editText.paddingEnd - iconWidth)
                }
                if (touchedIcon) {
                    isVisible = !isVisible
                    editText.inputType = if (isVisible) {
                        editText.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_eye_open, 0)
                        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    } else {
                        editText.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_eye_closed, 0)
                        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    }
                    editText.setSelection(editText.text.length)
                    return@setOnTouchListener true
                }
            }
            false
        }
    }
}