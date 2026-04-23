package com.example.ble_viewer

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class WelcomeActivity : AppCompatActivity() {

    companion object {
        private const val PREFS = "SolematePrefs"
        private const val KEY_AUTH_PROVIDER = "auth_provider"
        private const val KEY_IS_LOGGED_IN = "is_logged_in"
        private const val KEY_REMEMBER_ME = "remember_me"
        private const val PROVIDER_GOOGLE = "google"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (isFreshInstall()) {
            prefs.edit()
                .putBoolean("is_first_launch", true)
                .putBoolean(KEY_IS_LOGGED_IN, false)
                .putBoolean(KEY_REMEMBER_ME, false)
                .apply()
        }

        val isFirstLaunch = prefs.getBoolean("is_first_launch", true)
        val rememberMe = prefs.getBoolean(KEY_REMEMBER_ME, false)
        val isLoggedIn = prefs.getBoolean(KEY_IS_LOGGED_IN, false)
        val authProvider = prefs.getString(KEY_AUTH_PROVIDER, "local")

        if (isFirstLaunch) {
            setContentView(R.layout.activity_welcome)

            if (LanguagePickerHelper.consumeTransitionFlag(this)) {
                val content = findViewById<View>(android.R.id.content)
                content.alpha = 0f
                content.animate().alpha(1f).setDuration(280L).start()
            }

            val languageButton = findViewById<ImageButton>(R.id.language_button)
            languageButton.setOnClickListener {
                LanguagePickerHelper.show(this)
            }

            findViewById<android.widget.Button>(R.id.get_started_button).setOnClickListener {
                with(prefs.edit()) {
                    putBoolean("is_first_launch", false)
                    apply()
                }
                startActivity(Intent(this, RegistrationActivity::class.java))
                finish()
            }
            return
        }

        val shouldKeepSignedIn = isLoggedIn && (rememberMe || authProvider == PROVIDER_GOOGLE)

        val nextActivity = if (shouldKeepSignedIn) {
            ScanActivity::class.java
        } else {
            RegistrationActivity::class.java
        }

        startActivity(Intent(this, nextActivity))
        finish()
    }

    private fun isFreshInstall(): Boolean {
        val marker = File(noBackupFilesDir, "install_marker")
        if (marker.exists()) return false
        marker.writeText("1")
        return true
    }
}
