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
import com.github.mikephil.charting.charts.LineChart

class ReadingsActivity : AppCompatActivity() {

    private lateinit var accelText: TextView
    private lateinit var gyroText: TextView
    private lateinit var heartRateBpm: TextView
    private lateinit var heartRateStatus: TextView
    private lateinit var spo2Percentage: TextView
    private lateinit var heartRateChart: LineChart

    private val sensorDataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val uuidString = intent.getStringExtra(MainActivity.EXTRA_UUID_STRING)
            val encryptedBytes = intent.getByteArrayExtra(MainActivity.EXTRA_DECRYPTED_DATA) // Correctly get ByteArray

            if (encryptedBytes == null) {
                Log.e("BLE_PIPE", "[X] Received null byte array in broadcast.")
                return
            }
            Log.i("BLE_PIPE", "[7] ReadingsActivity received ${encryptedBytes.size} bytes.")

            // Decrypt the raw byte array
            val decryptedData = when (uuidString) {
                "9a8b0002-6d5e-4c10-b6d9-1f25c09d9e00" -> AESCrypto.decryptAccel(encryptedBytes)
                "9a8b0003-6d5e-4c10-b6d9-1f25c09d9e00" -> AESCrypto.decryptGyro(encryptedBytes)
                "9a8b0004-6d5e-4c10-b6d9-1f25c09d9e00" -> AESCrypto.decryptHeartRate(encryptedBytes)
                "9a8b0005-6d5e-4c10-b6d9-1f25c09d9e00" -> AESCrypto.decryptSpO2(encryptedBytes)
                else -> "N/A"
            }
            Log.i("BLE_PIPE", "[8] Decryption result: [$decryptedData]")

            // Update UI with the final decrypted data
            when (uuidString) {
                "9a8b0002-6d5e-4c10-b6d9-1f25c09d9e00" -> accelText.text = "Accel: $decryptedData"
                "9a8b0003-6d5e-4c10-b6d9-1f25c09d9e00" -> gyroText.text = "Gyro: $decryptedData"
                "9a8b0004-6d5e-4c10-b6d9-1f25c09d9e00" -> {
                    val parts = decryptedData.split(',')
                    if (parts.isNotEmpty()) {
                        heartRateBpm.text = "${parts[0].trim()} BPM"
                    }
                }
                "9a8b0005-6d5e-4c10-b6d9-1f25c09d9e00" -> {
                    val parts = decryptedData.split(',')
                    if (parts.size >= 2) {
                        spo2Percentage.text = "${parts[1].trim()}%"
                    } else {
                        spo2Percentage.text = "$decryptedData%"
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_readings)

        accelText = findViewById(R.id.accelText)
        gyroText = findViewById(R.id.gyroText)
        heartRateBpm = findViewById(R.id.heartRateBpm)
        heartRateStatus = findViewById(R.id.heartRateStatus)
        spo2Percentage = findViewById(R.id.spo2Percentage)
        heartRateChart = findViewById(R.id.heartRateChart)
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