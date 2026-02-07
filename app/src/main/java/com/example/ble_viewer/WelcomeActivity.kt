package com.example.ble_viewer

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class WelcomeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedPref = getSharedPreferences("SolematePrefs", Context.MODE_PRIVATE)
        val isFirstLaunch = sharedPref.getBoolean("is_first_launch", true)

        if (isFirstLaunch) {
            setContentView(R.layout.activity_welcome)

            val getStartedButton: Button = findViewById(R.id.get_started_button)

            getStartedButton.setOnClickListener {
                with(sharedPref.edit()) {
                    putBoolean("is_first_launch", false)
                    apply()
                }
                val intent = Intent(this, RegistrationActivity::class.java)
                startActivity(intent)
                finish()
            }
        } else {
            val intent = Intent(this, LoginActivity::class.java)
            startActivity(intent)
            finish()
        }
    }
}
