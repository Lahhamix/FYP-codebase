package com.example.ble_viewer

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.util.Log
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

class RegistrationActivity : AppCompatActivity() {

    private lateinit var googleSignInClient: GoogleSignInClient

    companion object {
        private const val PREFS = "SolematePrefs"
        private const val KEY_AUTH_PROVIDER = "auth_provider"
        private const val KEY_GOOGLE_EMAIL = "google_email"
        private const val KEY_GOOGLE_ID = "google_id"
        private const val KEY_IS_LOGGED_IN = "is_logged_in"
        private const val KEY_REMEMBER_ME = "remember_me"
        private const val PROVIDER_LOCAL = "local"
        private const val PROVIDER_GOOGLE = "google"
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
        val passwordEdit = findViewById<EditText>(R.id.password_edittext)
        val confirmPasswordEdit = findViewById<EditText>(R.id.confirm_password_edittext)
        val registerButton = findViewById<Button>(R.id.register_button)
        val googleSignUpButton = findViewById<Button>(R.id.google_signup_button)
        val signInLink = findViewById<TextView>(R.id.signin_link)
        val languageButton = findViewById<ImageButton>(R.id.language_button)

        setupPasswordToggle(passwordEdit)
        setupPasswordToggle(confirmPasswordEdit)

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestProfile()
            .build()

        googleSignInClient = GoogleSignIn.getClient(this, gso)

        registerButton.setOnClickListener {
            val username = usernameEdit.text.toString().trim()
            val password = passwordEdit.text.toString()
            val confirmPassword = confirmPasswordEdit.text.toString()

            if (username.isEmpty() || password.isEmpty() || confirmPassword.isEmpty()) {
                Toast.makeText(this, getString(R.string.toast_fill_all_fields), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (password.length < 6) {
                Toast.makeText(this, getString(R.string.toast_password_min_length), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (password != confirmPassword) {
                Toast.makeText(this, getString(R.string.toast_passwords_no_match), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            saveUserDataAndProceed(
                username = username,
                password = password,
                authProvider = PROVIDER_LOCAL,
                proceedToScan = false
            )
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
            val username = account?.displayName ?: getString(R.string.google_user)
            saveUserDataAndProceed(
                username = username,
                password = "",
                authProvider = PROVIDER_GOOGLE,
                googleEmail = account?.email,
                googleId = account?.id
            )
        } catch (e: ApiException) {
            Log.e("RegistrationActivity", "Google Sign-Up failed: ${e.statusCode}", e)
            Toast.makeText(this, getString(R.string.toast_google_signup_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveUserDataAndProceed(
        username: String,
        password: String,
        authProvider: String,
        googleEmail: String? = null,
        googleId: String? = null,
        proceedToScan: Boolean = true
    ) {
        val sharedPref = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        with(sharedPref.edit()) {
            putString("username", username)
            putString("age", "")
            putString("password", password)
            putBoolean("is_registered", true)
            putString(KEY_AUTH_PROVIDER, authProvider)
            putString(KEY_GOOGLE_EMAIL, googleEmail)
            putString(KEY_GOOGLE_ID, googleId)
            putBoolean(KEY_IS_LOGGED_IN, proceedToScan)
            putBoolean(KEY_REMEMBER_ME, authProvider == PROVIDER_GOOGLE && proceedToScan)
            apply()
        }

        val destination = if (proceedToScan) ScanActivity::class.java else LoginActivity::class.java
        val intent = Intent(this, destination)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
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
