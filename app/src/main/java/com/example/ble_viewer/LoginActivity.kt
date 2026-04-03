package com.example.ble_viewer

import android.content.Context
import android.content.Intent
import android.os.Bundle
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

class LoginActivity : AppCompatActivity() {

    private lateinit var googleSignInClient: GoogleSignInClient
    private var rememberMeSelected = false

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

        val sharedPref = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val savedUsername = sharedPref.getString("username", "")
        val savedPassword = sharedPref.getString("password", "")
        val authProvider = sharedPref.getString(KEY_AUTH_PROVIDER, PROVIDER_LOCAL)

        val loginButton: Button = findViewById(R.id.login_button)
        val usernameEdit: EditText = findViewById(R.id.login_email_edittext)
        val passwordEdit: EditText = findViewById(R.id.login_password_edittext)
        val rememberMeCheck: CheckBox = findViewById(R.id.remember_me_checkbox)
        val googleLoginButton: Button = findViewById(R.id.google_login_button)
        val signUpLink: TextView = findViewById(R.id.signup_link)
        val languageButton: ImageButton = findViewById(R.id.language_button)

        setupPasswordToggle(passwordEdit)

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
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
            if (authProvider == PROVIDER_GOOGLE) {
                Toast.makeText(this, getString(R.string.toast_google_linked_signin), Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            val enteredUsername = usernameEdit.text.toString().trim()
            val enteredPassword = passwordEdit.text.toString()
            if (enteredUsername == savedUsername && enteredPassword == savedPassword) {
                updateLoginState(rememberMeCheck.isChecked)
                proceedToMainApp()
            } else {
                Toast.makeText(this, getString(R.string.toast_incorrect_credentials), Toast.LENGTH_SHORT).show()
            }
        }

        googleLoginButton.setOnClickListener {
            googleSignInClient.signOut().addOnCompleteListener {
                val signInIntent = googleSignInClient.signInIntent
                googleSignInLauncher.launch(signInIntent)
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
            val username = account?.displayName ?: "Google User"
            
            val sharedPref = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            with(sharedPref.edit()) {
                putString("username", username)
                putBoolean("is_registered", true)
                putString(KEY_AUTH_PROVIDER, PROVIDER_GOOGLE)
                putString(KEY_GOOGLE_EMAIL, account?.email)
                putString(KEY_GOOGLE_ID, account?.id)
                putBoolean(KEY_IS_LOGGED_IN, true)
                putBoolean(KEY_REMEMBER_ME, true)
                apply()
            }
            proceedToMainApp()
        } catch (e: ApiException) {
            Log.e("LoginActivity", "Google Sign-In failed: ${e.statusCode}", e)
            Toast.makeText(this, getString(R.string.toast_google_signin_failed), Toast.LENGTH_LONG).show()
        }
    }

    private fun updateLoginState(rememberMe: Boolean) {
        val sharedPref = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        with(sharedPref.edit()) {
            putBoolean(KEY_IS_LOGGED_IN, true)
            putBoolean(KEY_REMEMBER_ME, rememberMe)
            apply()
        }
    }

    private fun proceedToMainApp() {
        val intent = Intent(this, ScanActivity::class.java)
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
                val iconStart = editText.width - editText.paddingEnd - endDrawable.bounds.width()
                if (event.x >= iconStart) {
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
