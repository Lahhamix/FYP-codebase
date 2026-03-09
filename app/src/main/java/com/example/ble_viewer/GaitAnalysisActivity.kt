package com.example.ble_viewer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class GaitAnalysisActivity : AppCompatActivity() {

    private lateinit var accelText: TextView
    private lateinit var gyroText: TextView
    private lateinit var edemaStatusText: TextView
    private lateinit var edemaDeviationText: TextView
    private lateinit var edemaIndicator: View
    private lateinit var edemaGradientBar: View
    private var disconnectDialog: AlertDialog? = null

    private val sensorDataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                MainActivity.ACTION_SENSOR_DATA -> {
                    val uuidString = intent.getStringExtra(MainActivity.EXTRA_UUID_STRING)
                    val encryptedBytes = intent.getByteArrayExtra(MainActivity.EXTRA_DECRYPTED_DATA)

                    if (encryptedBytes == null) {
                        Log.e("BLE_PIPE", "[X] Received null byte array in broadcast.")
                        return
                    }

                    val decryptedData = when (uuidString) {
                        "9a8b0002-6d5e-4c10-b6d9-1f25c09d9e00" -> AESCrypto.decryptAccel(encryptedBytes)
                        "9a8b0003-6d5e-4c10-b6d9-1f25c09d9e00" -> AESCrypto.decryptGyro(encryptedBytes)
                        "9a8b0006-6d5e-4c10-b6d9-1f25c09d9e00" -> AESCrypto.decryptFlex(encryptedBytes)
                        else -> return
                    }

                    when (uuidString) {
                        "9a8b0002-6d5e-4c10-b6d9-1f25c09d9e00" -> accelText.text = "📍 Accel: $decryptedData"
                        "9a8b0003-6d5e-4c10-b6d9-1f25c09d9e00" -> gyroText.text = "🔄 Gyro: $decryptedData"
                        "9a8b0006-6d5e-4c10-b6d9-1f25c09d9e00" -> updateEdemaDisplay(decryptedData)
                    }
                }
                MainActivity.ACTION_DEVICE_DISCONNECTED -> {
                    showDisconnectDialog()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gait_analysis)

        accelText = findViewById(R.id.accelText)
        gyroText = findViewById(R.id.gyroText)
        edemaStatusText = findViewById(R.id.edemaStatusText)
        edemaDeviationText = findViewById(R.id.edemaDeviationText)
        edemaIndicator = findViewById(R.id.edemaIndicator)
        edemaGradientBar = findViewById(R.id.edemaGradientBar)
    }

    private fun updateEdemaDisplay(data: String) {
        // Data format: "edemaLabel,totalDeviation,deviation1,deviation2"
        // Example: "none,2,1,1" or "moderate,15,7,8"
        val parts = data.split(",")
        if (parts.size < 2) return

        val edemaLevel = parts[0].trim()
        val totalDeviation = parts[1].trim().toIntOrNull() ?: 0

        // Update status text
        edemaStatusText.text = edemaLevel.replaceFirstChar { it.uppercase() }
        edemaDeviationText.text = "Deviation: $totalDeviation"

        // Position indicator on gradient bar based on edema level
        // Gradient goes from left (severe/red) to right (none/green)
        val position = when (edemaLevel.lowercase()) {
            "severe" -> 0.05f      // Far left
            "moderate" -> 0.3f     // Left-center
            "mild" -> 0.5f         // Center
            "subclinical" -> 0.75f // Right-center
            "none" -> 0.95f        // Far right
            "calibrating" -> 0.5f  // Center during calibration
            else -> 0.5f
        }

        // Update indicator position
        edemaIndicator.post {
            val barWidth = edemaGradientBar.width
            if (barWidth > 0) {
                edemaIndicator.visibility = View.VISIBLE
                edemaIndicator.x = edemaGradientBar.x + (barWidth * position) - (edemaIndicator.width / 2)
            }
        }
    }

    private fun showDisconnectDialog() {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            if (disconnectDialog?.isShowing == true) return@runOnUiThread

            disconnectDialog = AlertDialog.Builder(this)
                .setTitle("Connection Lost")
                .setMessage(
                    "The wearable device has been disconnected.\n\n" +
                            "You can reconnect to the last device or scan for another available device."
                )
                .setCancelable(false)
                .setPositiveButton("Reconnect") { _, _ ->
                    val intent = Intent(this, MainActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    intent.putExtra("RECONNECT_LAST", true)
                    startActivity(intent)
                    finish()
                }
                .setNegativeButton("Scan Devices") { _, _ ->
                    val intent = Intent(this, ScanActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                    startActivity(intent)
                    finish()
                }
                .create()

            disconnectDialog?.show()

            disconnectDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                setTextColor(getColor(R.color.dark_blue))
                textSize = 15f
                isAllCaps = false
            }

            disconnectDialog?.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply {
                setTextColor(getColor(R.color.dark_blue))
                textSize = 15f
                isAllCaps = false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(MainActivity.ACTION_SENSOR_DATA)
            addAction(MainActivity.ACTION_DEVICE_DISCONNECTED)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(sensorDataReceiver, filter)
    }

    override fun onPause() {
        super.onPause()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(sensorDataReceiver)
    }
}