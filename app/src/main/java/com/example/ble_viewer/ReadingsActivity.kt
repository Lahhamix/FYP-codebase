package com.example.ble_viewer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet

class ReadingsActivity : AppCompatActivity() {

    private lateinit var heartRateBpm: TextView
    private lateinit var heartRateStatus: TextView
    private lateinit var heartRateChart: LineChart
    private lateinit var spo2Percentage: TextView
    private lateinit var spo2Status: TextView
    private lateinit var spo2Chart: LineChart

    private var disconnectDialog: AlertDialog? = null

    private val bpmHistory = mutableListOf<Float>()
    private val spo2History = mutableListOf<Float>()

    private val sensorDataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                MainActivity.ACTION_SENSOR_DATA -> {
                    val uuidString = intent.getStringExtra(MainActivity.EXTRA_UUID_STRING)
                    val encryptedBytes = intent.getByteArrayExtra(MainActivity.EXTRA_DECRYPTED_DATA) ?: return

                    val decryptedData = when (uuidString) {
                        "9a8b0004-6d5e-4c10-b6d9-1f25c09d9e00" -> AESCrypto.decryptHeartRate(encryptedBytes)
                        "9a8b0005-6d5e-4c10-b6d9-1f25c09d9e00" -> AESCrypto.decryptSpO2(encryptedBytes)
                        else -> return
                    }

                    when (uuidString) {
                        "9a8b0004-6d5e-4c10-b6d9-1f25c09d9e00" -> {
                            val bpm = decryptedData.trim().toIntOrNull()
                            if (bpm != null) {
                                heartRateBpm.text = "$bpm BPM"
                                updateHeartRateStatus(bpm)
                                bpmHistory.add(bpm.toFloat())
                                if (bpmHistory.size > 30) bpmHistory.removeAt(0)
                                updateHeartRateChart()
                            }
                        }
                        "9a8b0005-6d5e-4c10-b6d9-1f25c09d9e00" -> {
                            val spo2 = decryptedData.trim().toDoubleOrNull()
                            if (spo2 != null) {
                                spo2Percentage.text = String.format("%.1f %%", spo2)
                                updateSpo2Status(spo2)
                                spo2History.add(spo2.toFloat())
                                if (spo2History.size > 30) spo2History.removeAt(0)
                                updateSpo2Chart()
                            }
                        }
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
        setContentView(R.layout.activity_readings)

        heartRateBpm = findViewById(R.id.heartRateBpm)
        heartRateStatus = findViewById(R.id.heartRateStatus)
        heartRateChart = findViewById(R.id.heartRateChart)
        spo2Percentage = findViewById(R.id.spo2Percentage)
        spo2Status = findViewById(R.id.spo2Status)
        spo2Chart = findViewById(R.id.spo2Chart)

        setupHeartRateChart()
        setupSpo2Chart()
    }

    private fun showDisconnectDialog() {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            if (disconnectDialog?.isShowing == true) return@runOnUiThread

            disconnectDialog = AlertDialog.Builder(this)
                .setTitle(getString(R.string.dialog_connection_lost))
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

    private fun setupHeartRateChart() {
        heartRateChart.apply {
            description.isEnabled = false
            legend.isEnabled = false
            xAxis.isEnabled = false
            axisLeft.apply {
                axisMinimum = 40f
                axisMaximum = 180f
                textColor = Color.WHITE
            }
            axisRight.isEnabled = false
            setBackgroundColor(Color.TRANSPARENT)
        }
    }

    private fun setupSpo2Chart() {
        spo2Chart.apply {
            description.isEnabled = false
            legend.isEnabled = false
            xAxis.isEnabled = false
            axisLeft.apply {
                axisMinimum = 80f
                axisMaximum = 100f
                textColor = Color.WHITE
            }
            axisRight.isEnabled = false
            setBackgroundColor(Color.TRANSPARENT)
        }
    }

    private fun updateHeartRateChart() {
        val entries = bpmHistory.mapIndexed { index, value -> Entry(index.toFloat(), value) }
        val dataSet = LineDataSet(entries, "BPM").apply {
            color = Color.parseColor("#FF6B6B")
            setCircleColor(Color.parseColor("#FF6B6B"))
            circleRadius = 4f
            lineWidth = 2f
            setDrawCircles(true)
            setDrawValues(false)
            mode = LineDataSet.Mode.CUBIC_BEZIER
        }
        heartRateChart.data = LineData(dataSet)
        heartRateChart.invalidate()
    }

    private fun updateSpo2Chart() {
        val entries = spo2History.mapIndexed { index, value -> Entry(index.toFloat(), value) }
        val dataSet = LineDataSet(entries, "SpO2").apply {
            color = Color.parseColor("#4ECDC4")
            setCircleColor(Color.parseColor("#4ECDC4"))
            circleRadius = 4f
            lineWidth = 2f
            setDrawCircles(true)
            setDrawValues(false)
            mode = LineDataSet.Mode.CUBIC_BEZIER
        }
        spo2Chart.data = LineData(dataSet)
        spo2Chart.invalidate()
    }

    private fun updateHeartRateStatus(bpm: Int) {
        heartRateStatus.text = when {
            bpm < 60 -> "🟢 Resting"
            bpm < 100 -> "🟡 Normal"
            bpm < 140 -> "🟠 Elevated"
            else -> "🔴 Critical"
        }
    }

    private fun updateSpo2Status(spo2: Double) {
        spo2Status.text = when {
            spo2 >= 95 -> "🟢 Excellent"
            spo2 >= 90 -> "🟡 Good"
            spo2 >= 85 -> "🟠 Fair"
            else -> "🔴 Low"
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