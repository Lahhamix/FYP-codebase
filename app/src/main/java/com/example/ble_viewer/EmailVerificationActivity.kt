package com.example.ble_viewer

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class EmailVerificationActivity : AppCompatActivity() {

    private lateinit var currentEmailValue: TextView
    private lateinit var editEmailLink: TextView
    private lateinit var codeEditText: EditText
    private lateinit var codeErrorText: TextView
    private lateinit var verifyButton: View
    private lateinit var resendCodeLink: TextView
    private lateinit var resendCountdownText: TextView
    private lateinit var statusText: TextView
    private lateinit var loadingProgress: ProgressBar

    private val timerHandler = Handler(Looper.getMainLooper())
    private var countdownRunnable: Runnable? = null
    private var pendingRegistration: EmailVerificationManager.PendingRegistration? = null
    private var isBusy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_email_verification)

        val languageButton = findViewById<ImageButton>(R.id.language_button)
        currentEmailValue  = findViewById(R.id.current_email_value)
        editEmailLink      = findViewById(R.id.edit_email_link)
        codeEditText       = findViewById(R.id.verification_code_edittext)
        codeErrorText      = findViewById(R.id.verification_code_error_text)
        verifyButton       = findViewById(R.id.verify_button)
        resendCodeLink     = findViewById(R.id.resend_code_link)
        resendCountdownText = findViewById(R.id.resend_countdown_text)
        statusText         = findViewById(R.id.status_text)
        loadingProgress    = findViewById(R.id.verification_progress)

        languageButton.setOnClickListener { LanguagePickerHelper.show(this) }

        pendingRegistration = EmailVerificationManager.loadPendingRegistration(this)
        if (pendingRegistration == null) {
            statusText.text = getString(R.string.error_server_generic)
            statusText.visibility = View.VISIBLE
            goToLoginAfterDelay()
            return
        }

        currentEmailValue.text = pendingRegistration?.email.orEmpty()
        AuthFieldUtils.bindClearOnChange(codeEditText, codeErrorText)

        editEmailLink.setOnClickListener { if (!isBusy) showEditEmailDialog() }
        verifyButton.setOnClickListener  { submitVerificationCode() }
        resendCodeLink.setOnClickListener {
            if (!isBusy && EmailVerificationManager.canResend(this)) resendVerificationCode()
        }

        updateResendState()
    }

    override fun onResume()  { super.onResume();  updateResendState() }
    override fun onPause()   { super.onPause();   countdownRunnable?.let { timerHandler.removeCallbacks(it) } }
    override fun onDestroy() { super.onDestroy(); countdownRunnable?.let { timerHandler.removeCallbacks(it) } }

    private fun showEditEmailDialog() {
        val dialogView  = LayoutInflater.from(this).inflate(R.layout.dialog_edit_email, null)
        val emailInput  = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.dialog_email_input)
        val cancelBtn   = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.dialog_email_cancel_button)
        val saveBtn     = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.dialog_email_save_button)

        emailInput.setText(pendingRegistration?.email.orEmpty())

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .create()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))

        cancelBtn.setOnClickListener { dialog.dismiss() }

        saveBtn.setOnClickListener {
            val newEmail = emailInput.text?.toString().orEmpty().trim()
            if (!EmailVerificationManager.isValidEmail(newEmail)) {
                statusText.text = getString(R.string.invalid_email)
                statusText.visibility = View.VISIBLE
                return@setOnClickListener
            }
            dialog.dismiss()
            val current = pendingRegistration ?: return@setOnClickListener
            val updated = current.copy(email = newEmail)
            setBusy(true, getString(R.string.verify_email_sending))
            EmailVerificationManager.requestVerificationCode(this, updated) { result ->
                setBusy(false, null)
                when (result) {
                    is EmailVerificationManager.RequestCodeResult.Success -> {
                        pendingRegistration = updated
                        currentEmailValue.text = newEmail
                        codeEditText.text?.clear()
                        AuthFieldUtils.clearError(codeEditText, codeErrorText)
                        statusText.text = getString(R.string.verify_email_code_sent)
                        statusText.visibility = View.VISIBLE
                        updateResendState()
                    }
                    else -> {
                        statusText.text = when (result) {
                            is EmailVerificationManager.RequestCodeResult.EmailAlreadyRegistered -> result.message
                            is EmailVerificationManager.RequestCodeResult.UsernameTaken          -> result.message
                            is EmailVerificationManager.RequestCodeResult.TooManyRequests        -> result.message
                            is EmailVerificationManager.RequestCodeResult.NetworkError           -> result.message
                            is EmailVerificationManager.RequestCodeResult.InvalidEmail           -> getString(R.string.invalid_email)
                            else -> getString(R.string.error_server_generic)
                        }
                        statusText.visibility = View.VISIBLE
                    }
                }
            }
        }
        dialog.show()
    }

    private fun submitVerificationCode() {
        val code = codeEditText.text?.toString().orEmpty().trim()
        if (code.isBlank()) {
            AuthFieldUtils.showError(codeEditText, codeErrorText, getString(R.string.verification_code_empty))
            return
        }
        setBusy(true, getString(R.string.verify_email_verifying))
        EmailVerificationManager.verifyCode(this, code) { result ->
            setBusy(false, null)
            when (result) {
                is EmailVerificationManager.VerifyCodeResult.Success -> {
                    EmailVerificationManager.completeRegistration(this)
                    startActivity(Intent(this, ScanActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    })
                    finish()
                }
                is EmailVerificationManager.VerifyCodeResult.InvalidCode -> {
                    AuthFieldUtils.showError(codeEditText, codeErrorText, result.message)
                }
                is EmailVerificationManager.VerifyCodeResult.ExpiredCode -> {
                    statusText.text = result.message
                    statusText.visibility = View.VISIBLE
                }
                is EmailVerificationManager.VerifyCodeResult.TooManyAttempts -> {
                    statusText.text = result.message
                    statusText.visibility = View.VISIBLE
                    verifyButton.isEnabled = false
                }
                is EmailVerificationManager.VerifyCodeResult.NetworkError -> {
                    statusText.text = result.message
                    statusText.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun resendVerificationCode() {
        setBusy(true, getString(R.string.verify_email_sending))
        EmailVerificationManager.resendVerificationCode(this) { result ->
            setBusy(false, null)
            when (result) {
                is EmailVerificationManager.RequestCodeResult.Success -> {
                    codeEditText.text?.clear()
                    AuthFieldUtils.clearError(codeEditText, codeErrorText)
                    statusText.text = getString(R.string.verify_email_code_sent)
                    statusText.visibility = View.VISIBLE
                    updateResendState()
                }
                else -> {
                    statusText.text = when (result) {
                        is EmailVerificationManager.RequestCodeResult.TooManyRequests -> result.message
                        is EmailVerificationManager.RequestCodeResult.NetworkError    -> result.message
                        else -> getString(R.string.error_server_generic)
                    }
                    statusText.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun setBusy(busy: Boolean, message: String?) {
        isBusy = busy
        loadingProgress.visibility = if (busy) View.VISIBLE else View.GONE
        verifyButton.isEnabled     = !busy
        resendCodeLink.isEnabled   = !busy
        editEmailLink.isEnabled    = !busy
        codeEditText.isEnabled     = !busy
        if (busy && !message.isNullOrBlank()) {
            statusText.text = message
            statusText.visibility = View.VISIBLE
        }
    }

    private fun updateResendState() {
        countdownRunnable?.let { timerHandler.removeCallbacks(it) }
        countdownRunnable = object : Runnable {
            override fun run() {
                val remaining = EmailVerificationManager.resendRemainingSeconds(this@EmailVerificationActivity)
                if (remaining > 0) {
                    resendCodeLink.isEnabled   = false
                    resendCountdownText.text   = getString(R.string.verify_email_countdown, remaining)
                    timerHandler.postDelayed(this, 1000L)
                } else {
                    resendCodeLink.isEnabled = !isBusy
                    resendCountdownText.text = ""
                }
            }
        }
        timerHandler.post(countdownRunnable!!)
    }

    private fun goToLoginAfterDelay() {
        timerHandler.postDelayed({
            startActivity(Intent(this, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
            finish()
        }, 1200L)
    }
}
