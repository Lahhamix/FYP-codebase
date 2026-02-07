package com.example.ble_viewer

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import java.util.concurrent.Executor

class LoginActivity : AppCompatActivity() {

    private lateinit var executor: Executor
    private lateinit var biometricPrompt: BiometricPrompt
    private lateinit var promptInfo: BiometricPrompt.PromptInfo

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        val sharedPref = getSharedPreferences("SolematePrefs", Context.MODE_PRIVATE)
        val savedUsername = sharedPref.getString("username", "")
        val savedPassword = sharedPref.getString("password", "")

        val loginButton: Button = findViewById(R.id.login_button)
        val emailEdit: EditText = findViewById(R.id.login_email_edittext)
        val passwordEdit: EditText = findViewById(R.id.login_password_edittext)
        val biometricSignIn: TextView = findViewById(R.id.biometric_signin)

        val biometricManager = BiometricManager.from(this)
        if (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS) {
            biometricSignIn.visibility = View.VISIBLE
        } else {
            biometricSignIn.visibility = View.GONE
        }

        executor = ContextCompat.getMainExecutor(this)
        biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    Toast.makeText(applicationContext, "Authentication error: $errString", Toast.LENGTH_SHORT).show()
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    proceedToMainApp()
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    Toast.makeText(applicationContext, "Authentication failed", Toast.LENGTH_SHORT).show()
                }
            })

        promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Biometric Login")
            .setSubtitle("Use your fingerprint or face to login")
            .setNegativeButtonText("Use Password")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()

        loginButton.setOnClickListener {
            val enteredEmail = emailEdit.text.toString()
            val enteredPassword = passwordEdit.text.toString()
            if (enteredEmail == savedUsername && enteredPassword == savedPassword) {
                proceedToMainApp()
            } else {
                Toast.makeText(this, "Incorrect Email/Number or Password", Toast.LENGTH_SHORT).show()
            }
        }

        biometricSignIn.setOnClickListener {
            biometricPrompt.authenticate(promptInfo)
        }
    }

    private fun proceedToMainApp() {
        val intent = Intent(this, ScanActivity::class.java)
        startActivity(intent)
        finish()
    }
}
