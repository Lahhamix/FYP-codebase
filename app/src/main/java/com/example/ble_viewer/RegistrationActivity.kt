package com.example.ble_viewer

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class RegistrationActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_registration)

        val usernameEdit = findViewById<EditText>(R.id.username_edittext)
        val ageEdit = findViewById<EditText>(R.id.age_edittext)
        val passwordEdit = findViewById<EditText>(R.id.password_edittext)
        val registerButton = findViewById<Button>(R.id.register_button)

        registerButton.setOnClickListener {
            val username = usernameEdit.text.toString()
            val age = ageEdit.text.toString()
            val password = passwordEdit.text.toString()

            if (username.isNotEmpty() && age.isNotEmpty() && password.isNotEmpty()) {
                val sharedPref = getSharedPreferences("SolematePrefs", Context.MODE_PRIVATE)
                with(sharedPref.edit()) {
                    putString("username", username)
                    putString("age", age)
                    putString("password", password)
                    putBoolean("is_registered", true)
                    apply()
                }

                Toast.makeText(this, "Registration Successful", Toast.LENGTH_SHORT).show()
                val intent = Intent(this, ScanActivity::class.java)
                startActivity(intent)
                finish()
            } else {
                Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
