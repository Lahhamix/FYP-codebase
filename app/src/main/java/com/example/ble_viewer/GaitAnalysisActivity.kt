package com.example.ble_viewer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class GaitAnalysisActivity : AppCompatActivity() {

    private lateinit var accelText: TextView
    private lateinit var gyroText: TextView
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
                        else -> return
                    }

                    when (uuidString) {
                        "9a8b0002-6d5e-4c10-b6d9-1f25c09d9e00" -> accelText.text = "📍 Accel: $decryptedData"
                        "9a8b0003-6d5e-4c10-b6d9-1f25c09d9e00" -> gyroText.text = "🔄 Gyro: $decryptedData"
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