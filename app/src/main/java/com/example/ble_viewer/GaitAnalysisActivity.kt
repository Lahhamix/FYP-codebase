package com.example.ble_viewer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class GaitAnalysisActivity : AppCompatActivity() {

    private lateinit var accelText: TextView
    private lateinit var gyroText: TextView

    private val sensorDataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
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
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gait_analysis)

        accelText = findViewById(R.id.accelText)
        gyroText = findViewById(R.id.gyroText)
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(MainActivity.ACTION_SENSOR_DATA)
        LocalBroadcastManager.getInstance(this).registerReceiver(sensorDataReceiver, filter)
    }

    override fun onPause() {
        super.onPause()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(sensorDataReceiver)
    }
}
