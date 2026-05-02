package com.example.ble_viewer

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.util.Patterns
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.tasks.Task
import org.json.JSONObject

class RegistrationActivity : AppCompatActivity() {

    private lateinit var googleSignInClient: GoogleSignInClient

    companion object {
        private const val WEB_CLIENT_ID =
            "161106121356-3naf6jcooh0ipbgo985d1skv3qpv42ss.apps.googleusercontent.com"
    }

    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        handleSignInResult(task)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_registration)

        if (LanguagePickerHelper.consumeTransitionFlag(this)) {
            val content = findViewById<View>(android.R.id.content)
            content.alpha = 0f
            content.animate().alpha(1f).setDuration(280L).start()
        }

        val usernameEdit = findViewById<EditText>(R.id.username_edittext)
        val emailEdit = findViewById<EditText>(R.id.email_edittext)
        val passwordEdit = findViewById<EditText>(R.id.password_edittext)
        val confirmPasswordEdit = findViewById<EditText>(R.id.confirm_password_edittext)
        val registerButton = findViewById<Button>(R.id.register_button)
        val registerStatus = findViewById<TextView>(R.id.register_status_text)
        val googleSignUpButton = findViewById<Button>(R.id.google_signup_button)
        val signInLink = findViewById<TextView>(R.id.signin_link)
        val languageButton = findViewById<ImageButton>(R.id.language_button)
        val emailError = findViewById<TextView>(R.id.email_error_text)
        val usernameError = findViewById<TextView>(R.id.username_error_text)
        val passwordError = findViewById<TextView>(R.id.password_error_text)
        val confirmPasswordError = findViewById<TextView>(R.id.confirm_password_error_text)

        setupPasswordToggle(passwordEdit)
        setupPasswordToggle(confirmPasswordEdit)
        AuthFieldUtils.bindClearOnChange(emailEdit, emailError)
        AuthFieldUtils.bindClearOnChange(usernameEdit, usernameError)
        AuthFieldUtils.bindClearOnChange(passwordEdit, passwordError)
        AuthFieldUtils.bindClearOnChange(confirmPasswordEdit, confirmPasswordError)

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(WEB_CLIENT_ID)
            .requestEmail()
            .requestProfile()
            .build()

        googleSignInClient = GoogleSignIn.getClient(this, gso)

        registerButton.setOnClickListener {
            hideStatus(registerStatus)

            val email = emailEdit.text.toString().trim()
            val username = usernameEdit.text.toString().trim()
            val password = passwordEdit.text.toString()
            val confirmPassword = confirmPasswordEdit.text.toString()

            clearRegistrationErrors(emailEdit, emailError, usernameEdit, usernameError, passwordEdit, passwordError, confirmPasswordEdit, confirmPasswordError)

            var hasError = false

            if (email.isBlank()) {
                AuthFieldUtils.showError(emailEdit, emailError, getString(R.string.email_is_required))
                hasError = true
            } else if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                AuthFieldUtils.showError(emailEdit, emailError, getString(R.string.invalid_email))
                hasError = true
            }

            if (username.isBlank()) {
                AuthFieldUtils.showError(usernameEdit, usernameError, getString(R.string.username_is_required))
                hasError = true
            } else if (username.length < 3) {
                AuthFieldUtils.showError(usernameEdit, usernameError, getString(R.string.username_too_short))
                hasError = true
            } else if (!username.matches(Regex("[a-zA-Z0-9]+"))) {
                AuthFieldUtils.showError(usernameEdit, usernameError, getString(R.string.username_invalid_format))
                hasError = true
            }

            if (password.isBlank()) {
                AuthFieldUtils.showError(passwordEdit, passwordError, getString(R.string.password_is_required))
                hasError = true
            } else if (password.length < 8) {
                AuthFieldUtils.showError(passwordEdit, passwordError, getString(R.string.password_too_weak))
                hasError = true
            } else {
                val complexityMsg = when {
                    !password.any { it.isUpperCase() }      -> getString(R.string.password_needs_uppercase)
                    !password.any { it.isLowerCase() }      -> getString(R.string.password_needs_lowercase)
                    !password.any { it.isDigit() }          -> getString(R.string.password_needs_number)
                    !password.any { !it.isLetterOrDigit() } -> getString(R.string.password_needs_special)
                    else                                    -> null
                }
                if (complexityMsg != null) {
                    AuthFieldUtils.showError(passwordEdit, passwordError, complexityMsg)
                    hasError = true
                }
            }

            if (confirmPassword.isBlank()) {
                AuthFieldUtils.showError(confirmPasswordEdit, confirmPasswordError, getString(R.string.confirm_password_required))
                hasError = true
            } else if (password != confirmPassword) {
                AuthFieldUtils.showError(confirmPasswordEdit, confirmPasswordError, getString(R.string.passwords_do_not_match))
                hasError = true
            }

            if (hasError) {
                return@setOnClickListener
            }

            showStatus(registerStatus, getString(R.string.verify_email_sending))
            registerButton.isEnabled = false

            EmailVerificationManager.requestVerificationCode(
                context = this,
                registration = EmailVerificationManager.PendingRegistration(
                    email    = email,
                    username = username,
                    password = password
                )
            ) { result ->
                registerButton.isEnabled = true
                when (result) {
                    is EmailVerificationManager.RequestCodeResult.Success -> {
                        hideStatus(registerStatus)
                        startActivity(Intent(this, EmailVerificationActivity::class.java))
                    }
                    is EmailVerificationManager.RequestCodeResult.EmailAlreadyRegistered -> {
                        hideStatus(registerStatus)
                        AuthFieldUtils.showError(emailEdit, emailError, result.message)
                    }
                    is EmailVerificationManager.RequestCodeResult.UsernameTaken -> {
                        hideStatus(registerStatus)
                        AuthFieldUtils.showError(usernameEdit, usernameError, result.message)
                    }
                    is EmailVerificationManager.RequestCodeResult.InvalidEmail -> {
                        hideStatus(registerStatus)
                        AuthFieldUtils.showError(emailEdit, emailError, getString(R.string.invalid_email))
                    }
                    is EmailVerificationManager.RequestCodeResult.TooManyRequests -> {
                        showStatus(registerStatus, result.message)
                    }
                    is EmailVerificationManager.RequestCodeResult.NetworkError -> {
                        showStatus(registerStatus, result.message)
                    }
                }
            }
        }

        googleSignUpButton.setOnClickListener {
            googleSignInClient.signOut().addOnCompleteListener {
                val signInIntent = googleSignInClient.signInIntent
                googleSignInLauncher.launch(signInIntent)
            }
        }

        signInLink.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }

        languageButton.setOnClickListener {
            LanguagePickerHelper.show(this)
        }
    }

    private fun handleSignInResult(completedTask: Task<GoogleSignInAccount>) {
        try {
            val account = completedTask.getResult(ApiException::class.java)
            val idToken = account?.idToken
            if (idToken == null) {
                Toast.makeText(this, getString(R.string.toast_google_signup_failed), Toast.LENGTH_SHORT).show()
                return
            }
            Thread {
                val resp = ApiClient.post(
                    this, "/auth/google",
                    JSONObject().apply { put("idToken", idToken) }
                )
                Handler(Looper.getMainLooper()).post {
                    if (resp.code in 200..299) {
                        val body = resp.body ?: JSONObject()
                        val user = body.optJSONObject("user") ?: JSONObject()
                        SessionManager.saveSession(
                            this,
                            accessToken       = body.optString("accessToken"),
                            refreshToken      = body.optString("refreshToken"),
                            userId            = user.optString("id"),
                            username          = user.optString("username"),
                            email             = user.optString("email"),
                            displayName       = user.optString("displayName").ifBlank { null },
                            profilePictureUrl = user.optString("profilePictureUrl").ifBlank { null }
                        )
                        startActivity(Intent(this, ScanActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        })
                        finish()
                    } else {
                        val msg = resp.body?.optString("error") ?: getString(R.string.toast_google_signup_failed)
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        } catch (e: ApiException) {
            Log.e("RegistrationActivity", "Google Sign-Up failed: ${e.statusCode}", e)
            Toast.makeText(this, getString(R.string.toast_google_signup_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun clearRegistrationErrors(
        emailEdit: EditText,
        emailError: TextView,
        usernameEdit: EditText,
        usernameError: TextView,
        passwordEdit: EditText,
        passwordError: TextView,
        confirmPasswordEdit: EditText,
        confirmPasswordError: TextView
    ) {
        AuthFieldUtils.clearError(emailEdit, emailError)
        AuthFieldUtils.clearError(usernameEdit, usernameError)
        AuthFieldUtils.clearError(passwordEdit, passwordError)
        AuthFieldUtils.clearError(confirmPasswordEdit, confirmPasswordError)
    }

    private fun showStatus(statusView: TextView, message: String) {
        statusView.text = message
        statusView.visibility = View.VISIBLE
    }

    private fun hideStatus(statusView: TextView) {
        statusView.text = ""
        statusView.visibility = View.GONE
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
