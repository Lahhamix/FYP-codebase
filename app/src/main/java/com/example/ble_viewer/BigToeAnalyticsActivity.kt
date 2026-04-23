package com.example.ble_viewer

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.google.android.material.button.MaterialButton
import java.util.Locale
import java.text.NumberFormat

class BigToeAnalyticsActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "SolematePrefs"
    }

    private lateinit var toolbarUsername: TextView
    private lateinit var toolbarProfileImage: ImageView

    private enum class HistoryWindow(val ms: Long, val labelRes: Int) {
        M30(30L * 60_000L, R.string.big_toe_last_30_minutes),
        H1(60L * 60_000L, R.string.big_toe_last_1_hour),
        H2(2L * 60L * 60_000L, R.string.big_toe_last_2_hours),
        H4(4L * 60L * 60_000L, R.string.big_toe_last_4_hours)
    }

    private var hrWindow: HistoryWindow = HistoryWindow.H4
    private var spo2Window: HistoryWindow = HistoryWindow.H4

    private lateinit var hrValue: TextView
    private lateinit var hrStatus: TextView
    private lateinit var hrChart: LineChart
    private lateinit var hrUpdated: TextView
    private lateinit var hrPeriod: TextView

    private lateinit var spo2Value: TextView
    private lateinit var spo2Status: TextView
    private lateinit var spo2Chart: LineChart
    private lateinit var spo2Updated: TextView
    private lateinit var spo2Period: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_big_toe_analytics)

        hrValue = findViewById(R.id.big_toe_hr_value)
        hrStatus = findViewById(R.id.big_toe_hr_status)
        hrChart = findViewById(R.id.big_toe_hr_chart)
        hrUpdated = findViewById(R.id.big_toe_hr_updated)
        hrPeriod = findViewById(R.id.big_toe_hr_period)

        spo2Value = findViewById(R.id.big_toe_spo2_value)
        spo2Status = findViewById(R.id.big_toe_spo2_status)
        spo2Chart = findViewById(R.id.big_toe_spo2_chart)
        spo2Updated = findViewById(R.id.big_toe_spo2_updated)
        spo2Period = findViewById(R.id.big_toe_spo2_period)

        toolbarUsername = findViewById(R.id.toolbar_username)
        toolbarProfileImage = findViewById(R.id.toolbar_profile_image)
        bindToolbar()

        setupCharts()
        bindBottomNavigation()
        bindActions()

        hrPeriod.setOnClickListener { v -> showHistoryPopup(v, forHr = true) }
        spo2Period.setOnClickListener { v -> showHistoryPopup(v, forHr = false) }

        refreshHeartRate()
        refreshSpo2()
    }

    override fun onResume() {
        super.onResume()
        updateToolbarUserFromPrefs()
    }

    private fun bindToolbar() {
        updateToolbarUserFromPrefs()

        findViewById<View>(R.id.profile_card).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }

        toolbarUsername.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }

    private fun updateToolbarUserFromPrefs() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val username = prefs.getString("username", getString(R.string.username_placeholder))
            .orEmpty()
            .ifBlank { getString(R.string.username_placeholder) }

        toolbarUsername.text = username

        val imagePath = prefs.getString("profile_image_path", null)
        if (!imagePath.isNullOrBlank()) {
            val bitmap = BitmapFactory.decodeFile(imagePath)
            if (bitmap != null) {
                toolbarProfileImage.setImageBitmap(bitmap)
            } else {
                toolbarProfileImage.setImageResource(R.drawable.profile)
            }
        } else {
            toolbarProfileImage.setImageResource(R.drawable.profile)
        }
    }

    private fun showHistoryPopup(anchor: android.view.View, forHr: Boolean) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.menu_big_toe_history_range, popup.menu)
        popup.setOnMenuItemClickListener { item: MenuItem ->
            val w = when (item.itemId) {
                R.id.history_30m -> HistoryWindow.M30
                R.id.history_1h -> HistoryWindow.H1
                R.id.history_2h -> HistoryWindow.H2
                R.id.history_4h -> HistoryWindow.H4
                else -> HistoryWindow.H4
            }
            if (forHr) {
                hrWindow = w
                hrPeriod.text = getString(w.labelRes)
                refreshHeartRate()
            } else {
                spo2Window = w
                spo2Period.text = getString(w.labelRes)
                refreshSpo2()
            }
            true
        }
        popup.show()
    }

    private fun bindActions() {
        findViewById<MaterialButton>(R.id.big_toe_share_button).setOnClickListener {
            Toast.makeText(this, getString(R.string.toast_share_report_coming_soon), Toast.LENGTH_SHORT).show()
        }
    }

    private fun bindBottomNavigation() {
        findViewById<View>(R.id.nav_home).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            })
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        }
        findViewById<View>(R.id.nav_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
        findViewById<View>(R.id.nav_foot_overview).setOnClickListener {
            startActivity(Intent(this, PressureMatrixActivity::class.java).apply {
                putExtra(PressureMatrixActivity.EXTRA_SHOW_FOOT_OVERVIEW, true)
            })
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        }
    }

    private fun refreshHeartRate() {
        val since = System.currentTimeMillis() - hrWindow.ms
        val samples = VitalsHistoryStore.readSince(this, since)
            .filter { it.type == VitalsHistoryStore.Type.HR }
        updateHeartRate(samples)
    }

    private fun refreshSpo2() {
        val since = System.currentTimeMillis() - spo2Window.ms
        val samples = VitalsHistoryStore.readSince(this, since)
            .filter { it.type == VitalsHistoryStore.Type.SPO2 }
        updateSpo2(samples)
    }

    private fun updateHeartRate(samples: List<VitalsHistoryStore.Sample>) {
        val latest = samples.lastOrNull()
        val bpm = latest?.value?.toInt()

        hrValue.text = if (bpm != null) formatValueWithUnit(bpm.toString(), "BPM") else "-- BPM"
        hrStatus.text = if (bpm != null) heartRateState(bpm) else getString(R.string.dashboard_status_normal)
        hrUpdated.text = formatUpdatedAgo(latest?.epochMs)

        val entries = samples.toEntries()
        hrChart.data = LineData(LineDataSet(entries, "HR").apply {
            color = Color.parseColor("#EC4D75")
            setDrawCircles(false)
            setDrawValues(false)
            lineWidth = 2.5f
            mode = LineDataSet.Mode.CUBIC_BEZIER
            setDrawFilled(true)
            fillColor = Color.parseColor("#33EC4D75")
            highLightColor = Color.TRANSPARENT
        })
        hrChart.invalidate()
    }

    private fun updateSpo2(samples: List<VitalsHistoryStore.Sample>) {
        val latest = samples.lastOrNull()
        val v = latest?.value

        spo2Value.text = if (v != null) {
            val nf = NumberFormat.getNumberInstance(Locale.getDefault()).apply {
                minimumFractionDigits = 1
                maximumFractionDigits = 1
            }
            formatValueWithUnit(nf.format(v), "%")
        } else {
            "-- %"
        }
        spo2Status.text = if (v != null) spo2State(v) else getString(R.string.dashboard_status_healthy)
        spo2Updated.text = formatUpdatedAgo(latest?.epochMs)

        val entries = samples.toEntries()
        spo2Chart.data = LineData(LineDataSet(entries, "SpO2").apply {
            color = Color.parseColor("#2D7EEA")
            setDrawCircles(false)
            setDrawValues(false)
            lineWidth = 2.5f
            mode = LineDataSet.Mode.CUBIC_BEZIER
            setDrawFilled(true)
            fillColor = Color.parseColor("#332D7EEA")
            highLightColor = Color.TRANSPARENT
        })
        spo2Chart.invalidate()
    }

    private fun List<VitalsHistoryStore.Sample>.toEntries(): List<Entry> {
        if (isEmpty()) return listOf(Entry(0f, 0f))
        val t0 = first().epochMs
        return mapIndexed { _, s ->
            val minutes = ((s.epochMs - t0) / 60_000.0).toFloat().coerceAtLeast(0f)
            Entry(minutes, s.value.toFloat())
        }
    }

    private fun setupCharts() {
        configureChart(hrChart)
        configureChart(spo2Chart)
    }

    private fun configureChart(chart: LineChart) {
        chart.apply {
            description.isEnabled = false
            legend.isEnabled = false
            setTouchEnabled(false)
            setPinchZoom(false)
            setScaleEnabled(false)
            setViewPortOffsets(0f, 8f, 0f, 0f)
            xAxis.isEnabled = false
            axisRight.isEnabled = false
            axisLeft.isEnabled = false
        }
    }

    private fun formatUpdatedAgo(updatedAt: Long?): String {
        if (updatedAt == null || updatedAt <= 0L) return getString(R.string.analytics_updated_waiting)
        val minutes = ((System.currentTimeMillis() - updatedAt) / 60_000L).coerceAtLeast(0L)
        return when {
            minutes < 1L -> getString(R.string.analytics_updated_just_now)
            minutes < 60L -> getString(R.string.analytics_updated_minutes_ago, minutes)
            minutes < 1440L -> getString(R.string.analytics_updated_hours_ago, minutes / 60L)
            else -> getString(R.string.analytics_updated_days_ago, minutes / 1440L)
        }
    }

    private fun heartRateState(bpm: Int): String {
        return when {
            bpm < 60 -> "Resting"
            bpm <= 100 -> "Normal"
            bpm <= 140 -> "Elevated"
            else -> "Critical"
        }
    }

    private fun spo2State(spo2: Double): String {
        return when {
            spo2 >= 95 -> "Healthy"
            spo2 >= 90 -> "Watch"
            spo2 >= 85 -> "Low"
            else -> "Critical"
        }
    }

    private fun formatValueWithUnit(value: String, unit: String): String {
        return "$value $unit"
    }
}
