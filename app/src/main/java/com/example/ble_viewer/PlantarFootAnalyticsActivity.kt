package com.example.ble_viewer

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class PlantarFootAnalyticsActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "SolematePrefs"
    }

    private lateinit var toolbarUsername: TextView
    private lateinit var toolbarProfileImage: ImageView
    private lateinit var heatmapImage: ImageView
    private lateinit var stepsChart: BarChart
    private lateinit var analyticsScroll: ScrollView
    private lateinit var shareHealthReportButton: com.google.android.material.button.MaterialButton
    private lateinit var toolbarBack: ImageView

    private var pressureLive: PlantarPressureLiveController? = null
    private var disconnectDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_plantar_foot_analytics)

        toolbarUsername = findViewById(R.id.toolbar_username)
        toolbarProfileImage = findViewById(R.id.toolbar_profile_image)
        heatmapImage = findViewById(R.id.analytics_heatmap_image)
        stepsChart = findViewById(R.id.stepsConsistencyChart)
        analyticsScroll = findViewById(R.id.analytics_scroll)
        shareHealthReportButton = findViewById(R.id.share_health_report_button)
        toolbarBack = findViewById(R.id.toolbar_back)

        toolbarBack.visibility = View.VISIBLE

        loadHeatmapPlaceholderFromSnapshot()

        pressureLive = PlantarPressureLiveController(
            activity = this,
            heatmapImageView = heatmapImage,
            calibrateButton = findViewById(R.id.plantar_live_calibrate_button),
            calibrationProgress = findViewById(R.id.plantar_live_calibration_progress),
            zoneAtaxiaStatus = findViewById(R.id.plantar_live_ataxia_status),
            zoneMotionStatus = findViewById(R.id.plantar_live_motion_status),
            zoneStepDonut = findViewById(R.id.plantar_live_step_donut),
            trackStepsMotion = true,
            onDeviceDisconnected = { showDisconnectDialog() },
            rotateHeatmapClockwise90ForDisplay = false,
        ).also {
            it.initialize(startCalibrationImmediately = false)
        }

        bindToolbar()
        bindBottomNavigation()
        bindActions()
        setupStepsConsistencyChart()
    }

    private fun loadHeatmapPlaceholderFromSnapshot() {
        val snapshotFile = File(filesDir, PressureMatrixActivity.HEATMAP_SNAPSHOT_FILE)
        if (!snapshotFile.exists()) return
        BitmapFactory.decodeFile(snapshotFile.absolutePath)?.let { heatmapImage.setImageBitmap(it) }
    }

    private fun bindActions() {
        toolbarBack.setOnClickListener {
            startActivity(Intent(this, PressureMatrixActivity::class.java).apply {
                putExtra(PressureMatrixActivity.EXTRA_SHOW_FOOT_OVERVIEW, true)
            })
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        }

        shareHealthReportButton.setOnClickListener {
            Toast.makeText(this, getString(R.string.toast_share_report_coming_soon), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        pressureLive?.onResume()
    }

    override fun onPause() {
        pressureLive?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        pressureLive?.onDestroy()
        pressureLive = null
        super.onDestroy()
    }

    private fun showDisconnectDialog() {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            if (disconnectDialog?.isShowing == true) return@runOnUiThread

            disconnectDialog = AlertDialog.Builder(this)
                .setTitle(getString(R.string.dialog_connection_lost))
                .setMessage(getString(R.string.dialog_wearable_disconnected))
                .setCancelable(false)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    finish()
                }
                .create()

            disconnectDialog?.show()
        }
    }

    private fun bindToolbar() {
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

        findViewById<View>(R.id.profile_card)?.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }

        toolbarUsername.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }

    private fun bindBottomNavigation() {
        findViewById<LinearLayout>(R.id.nav_home).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            })
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        }
        findViewById<LinearLayout>(R.id.nav_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
        findViewById<LinearLayout>(R.id.nav_foot_overview).setOnClickListener {
            startActivity(Intent(this, PressureMatrixActivity::class.java).apply {
                putExtra(PressureMatrixActivity.EXTRA_SHOW_FOOT_OVERVIEW, true)
            })
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        }
    }

    private fun setupStepsConsistencyChart() {
        val (labels, values) = loadLastSevenDaysSteps()
        val entries = values.mapIndexed { index, value -> BarEntry(index.toFloat(), value) }

        val dataSet = BarDataSet(entries, "").apply {
            color = Color.parseColor("#0D3B66")
            setDrawValues(false)
            highLightAlpha = 0
        }

        val barData = BarData(dataSet).apply {
            barWidth = 0.58f
        }

        stepsChart.apply {
            description.isEnabled = false
            legend.isEnabled = false
            setPinchZoom(false)
            setScaleEnabled(false)
            setDrawGridBackground(false)
            setDrawBarShadow(false)
            axisRight.isEnabled = false
            axisLeft.axisMinimum = 0f
            axisLeft.textColor = getColor(R.color.Blue_gray)
            axisLeft.gridColor = getColor(android.R.color.darker_gray)
            axisLeft.gridLineWidth = 0.3f
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.granularity = 1f
            xAxis.setDrawGridLines(false)
            xAxis.textColor = getColor(R.color.Blue_gray)
            xAxis.valueFormatter = IndexAxisValueFormatter(labels)
            data = barData
            setFitBars(true)
            invalidate()
        }
    }

    private fun loadLastSevenDaysSteps(): Pair<List<String>, List<Float>> {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val labels = mutableListOf<String>()
        val values = mutableListOf<Float>()
        val keyFormat = SimpleDateFormat("yyyyMMdd", Locale.US)
        val dayLabelFormat = SimpleDateFormat("EEE", Locale.US)

        for (offset in 6 downTo 0) {
            val cal = Calendar.getInstance()
            cal.add(Calendar.DAY_OF_YEAR, -offset)
            val dayKey = "steps_day_${keyFormat.format(Date(cal.timeInMillis))}"
            val value = prefs.getInt(dayKey, 0)
            labels.add(dayLabelFormat.format(Date(cal.timeInMillis)))
            values.add(value.toFloat())
        }

        return labels to values
    }
}
