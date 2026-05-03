package com.example.ble_viewer

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.CheckBox
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

class LoginActivity : AppCompatActivity() {

    private lateinit var googleSignInClient: GoogleSignInClient
    private var rememberMeSelected = false

    companion object {
        private const val PREFS        = "SolematePrefs"
        private const val KEY_REMEMBER_ME = "remember_me"
        private const val WEB_CLIENT_ID =
            "161106121356-3naf6jcooh0ipbgo985d1skv3qpv42ss.apps.googleusercontent.com"
    }

    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        handleGoogleSignInResult(task)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        if (LanguagePickerHelper.consumeTransitionFlag(this)) {
            val content = findViewById<View>(android.R.id.content)
            content.alpha = 0f
            content.animate().alpha(1f).setDuration(280L).start()
        }

        val sharedPref    = getSharedPreferences(PREFS, MODE_PRIVATE)
        val loginButton   = findViewById<Button>(R.id.login_button)
        val identifierEdit = findViewById<EditText>(R.id.login_email_edittext)
        val passwordEdit  = findViewById<EditText>(R.id.login_password_edittext)
        val forgotPasswordLink = findViewById<TextView>(R.id.forgot_password_link)
        val rememberMeCheck   = findViewById<CheckBox>(R.id.remember_me_checkbox)
        val googleLoginButton = findViewById<Button>(R.id.google_login_button)
        val signUpLink        = findViewById<TextView>(R.id.signup_link)
        val languageButton    = findViewById<ImageButton>(R.id.language_button)

        setupPasswordToggle(passwordEdit)

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(WEB_CLIENT_ID)
            .requestEmail()
            .requestProfile()
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)

        rememberMeCheck.isChecked = sharedPref.getBoolean(KEY_REMEMBER_ME, false)
        rememberMeSelected = rememberMeCheck.isChecked

        rememberMeCheck.setOnCheckedChangeListener { _, isChecked ->
            rememberMeSelected = isChecked
        }

        loginButton.setOnClickListener {
            val identifier = identifierEdit.text.toString().trim()
            val password   = passwordEdit.text.toString()

            if (identifier.isBlank() || password.isBlank()) {
                Toast.makeText(this, getString(R.string.login_fields_required), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            loginButton.isEnabled = false
            Thread {
                val resp = ApiClient.post(
                    this, "/auth/login",
                    JSONObject().apply {
                        put("identifier", identifier)
                        put("password",   password)
                    }
                )
                Handler(Looper.getMainLooper()).post {
                    loginButton.isEnabled = true
                    when {
                        resp.code in 200..299 -> {
                            val body = resp.body ?: JSONObject()
                            val user = body.optJSONObject("user") ?: JSONObject()
                            SessionManager.saveSession(
                                this,
                                accessToken       = body.optString("accessToken"),
                                refreshToken      = body.optString("refreshToken"),
                                userId            = user.optString("id"),
                                username          = user.optString("username"),
                                email             = user.optString("email"),
                                displayName       = user.optString("displayName").takeIf { it.isNotBlank() && it != "null" },
                                profilePictureUrl = user.optString("profilePictureUrl").takeIf { it.isNotBlank() && it != "null" },
                                authProvider      = "local"
                            )
                            sharedPref.edit().putBoolean(KEY_REMEMBER_ME, rememberMeSelected).apply()
                            proceedToMainApp()
                        }
                        resp.code == -1 -> {
                            Toast.makeText(this, getString(R.string.error_no_internet), Toast.LENGTH_LONG).show()
                        }
                        resp.code == 401 -> {
                            Toast.makeText(this, getString(R.string.toast_incorrect_credentials), Toast.LENGTH_SHORT).show()
                        }
                        resp.code == 403 -> {
                            val msg = resp.body?.optString("error") ?: getString(R.string.toast_incorrect_credentials)
                            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                        }
                        resp.code == 429 -> {
                            Toast.makeText(this, getString(R.string.error_too_many_requests), Toast.LENGTH_LONG).show()
                        }
                        else -> {
                            Toast.makeText(this, getString(R.string.error_server_generic), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }.start()
        }

        forgotPasswordLink.setOnClickListener {
            startActivity(Intent(this, ForgotPasswordActivity::class.java))
        }

        googleLoginButton.setOnClickListener {
            googleSignInClient.signOut().addOnCompleteListener {
                googleSignInLauncher.launch(googleSignInClient.signInIntent)
            }
        }

        signUpLink.setOnClickListener {
            startActivity(Intent(this, RegistrationActivity::class.java))
            finish()
        }

        languageButton.setOnClickListener {
            LanguagePickerHelper.show(this)
        }
    }

    private fun handleGoogleSignInResult(completedTask: Task<GoogleSignInAccount>) {
        try {
            val account = completedTask.getResult(ApiException::class.java)
            val idToken = account?.idToken
            if (idToken == null) {
                Toast.makeText(this, getString(R.string.toast_google_signin_failed), Toast.LENGTH_LONG).show()
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
                            displayName       = user.optString("displayName").takeIf { it.isNotBlank() && it != "null" },
                            profilePictureUrl = user.optString("profilePictureUrl").takeIf { it.isNotBlank() && it != "null" },
                            authProvider      = "google"
                        )
                        proceedToMainApp()
                    } else {
                        val msg = resp.body?.optString("error") ?: getString(R.string.toast_google_signin_failed)
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        } catch (e: ApiException) {
            Log.e("LoginActivity", "Google Sign-In failed: ${e.statusCode}", e)
            Toast.makeText(this, getString(R.string.toast_google_signin_failed), Toast.LENGTH_LONG).show()
        }
    }

    private fun proceedToMainApp() {
        startActivity(Intent(this, ScanActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    private fun setupPasswordToggle(editText: EditText) {
        var isVisible = false
        editText.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                val endDrawable = editText.compoundDrawablesRelative[2] ?: return@setOnTouchListener false
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
