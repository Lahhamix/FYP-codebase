package com.example.ble_viewer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.google.android.material.button.MaterialButton
import java.text.NumberFormat
import java.util.Locale

class BigToeAnalyticsActivity : AppCompatActivity() {
    private lateinit var toolbarBack: ImageView

    companion object {
        private const val PREFS_NAME = "SolematePrefs"
        private const val HR_CHAR_UUID = "9a8b0004-6d5e-4c10-b6d9-1f25c09d9e00"
        private const val SPO2_CHAR_UUID = "9a8b0005-6d5e-4c10-b6d9-1f25c09d9e00"
        private const val PPG_WAVE_CHAR_UUID = "9a8b0008-6d5e-4c10-b6d9-1f25c09d9e00"
        private const val PPG_WAVE_WINDOW_BYTES = 625 * 4
        private const val TAG = "BIG_TOE_ANALYTICS"
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

    /**
     * While MainActivity is stopped (e.g. user is on this screen), its receiver is unregistered —
     * we must append HR/SpO2 to [VitalsHistoryStore] here so history matches the dashboard.
     */
    private val sensorDataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                MainActivity.ACTION_SENSOR_DATA -> {
                    val uuidString = intent.getStringExtra(MainActivity.EXTRA_UUID_STRING) ?: return
                    val encryptedBytes = intent.getByteArrayExtra(MainActivity.EXTRA_DECRYPTED_DATA) ?: return
                    runOnUiThread {
                        try {
                            when (uuidString) {
                                HR_CHAR_UUID -> {
                                    val bpm = AESCrypto.decryptHeartRate(encryptedBytes).trim().toIntOrNull() ?: return@runOnUiThread
                                    VitalsHistoryStore.appendHeartRate(this@BigToeAnalyticsActivity, bpm)
                                    refreshHeartRate()
                                }
                                SPO2_CHAR_UUID -> {
                                    val spo2 = AESCrypto.decryptSpO2(encryptedBytes).trim().toDoubleOrNull() ?: return@runOnUiThread
                                    VitalsHistoryStore.appendSpo2(this@BigToeAnalyticsActivity, spo2)
                                    refreshSpo2()
                                }
                                PPG_WAVE_CHAR_UUID -> {
                                    processPpgWaveChunk(encryptedBytes)
                                }
                            }
                        } catch (_: Exception) { }
                    }
                }
                MainActivity.ACTION_BP_PREDICTION -> {
                    val sbp = intent.getFloatExtra(MainActivity.EXTRA_SBP, Float.NaN)
                    val dbp = intent.getFloatExtra(MainActivity.EXTRA_DBP, Float.NaN)
                    if (!sbp.isFinite() || !dbp.isFinite()) return
                    runOnUiThread { updateBpUi(sbp, dbp) }
                }
            }
        }
    }

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

    private lateinit var bpValue: TextView
    private lateinit var bpCaret: ImageView
    private lateinit var bpBar: View
    private val bpRunner by lazy { BpModelRunner(this) }
    private var ppgWaveRxFrameId: Int = -1
    private var ppgWaveRxTotalChunks: Int = 0
    private var ppgWaveRxReceived: BooleanArray = BooleanArray(0)
    private var ppgWaveRxChunks: Array<ByteArray> = emptyArray()
    private var ppgWaveLastCompletedFrameId: Int = -1

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

        bpValue = findViewById(R.id.big_toe_bp_value)
        bpCaret = findViewById(R.id.big_toe_bp_caret)
        bpBar = findViewById(R.id.big_toe_bp_bar)

        toolbarUsername = findViewById(R.id.toolbar_username)
        toolbarProfileImage = findViewById(R.id.toolbar_profile_image)
        toolbarBack = findViewById(R.id.toolbar_back)
        toolbarBack.visibility = View.VISIBLE
        toolbarBack.setOnClickListener {
            finish()
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
        bindToolbar()

        setupCharts()
        bindBottomNavigation()
        bindActions()

        hrPeriod.setOnClickListener { v -> showHistoryPopup(v, forHr = true) }
        spo2Period.setOnClickListener { v -> showHistoryPopup(v, forHr = false) }

        hrPeriod.text = getString(hrWindow.labelRes)
        spo2Period.text = getString(spo2Window.labelRes)

        refreshHeartRate()
        refreshSpo2()
    }

    override fun onResume() {
        super.onResume()
        LocalBroadcastManager.getInstance(this).registerReceiver(
            sensorDataReceiver,
            IntentFilter().apply {
                addAction(MainActivity.ACTION_SENSOR_DATA)
                addAction(MainActivity.ACTION_BP_PREDICTION)
            }
        )
        updateToolbarUserFromPrefs()
        BpPredictionStore.latest(this)?.let { updateBpUi(it.sbp, it.dbp) }
        refreshHeartRate()
        refreshSpo2()
    }

    override fun onPause() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(sensorDataReceiver)
        super.onPause()
    }

    private fun updateBpUi(sbp: Float, dbp: Float) {
        bpValue.text = "${sbp.toInt()}/${dbp.toInt()} mmHg"

        bpBar.post {
            val cls = BpUi.classify(sbp, dbp)
            val pos = BpUi.classToNormalizedPos(cls)
            val barW = bpBar.width.toFloat().coerceAtLeast(1f)
            val bubbleW = bpValue.width.toFloat().coerceAtLeast(1f)
            val desiredCenter = (barW * pos).coerceIn(bubbleW / 2f, barW - (bubbleW / 2f))
            val offsetFromCenter = desiredCenter - (barW / 2f)
            bpValue.translationX = offsetFromCenter
            bpCaret.translationX = offsetFromCenter
        }
    }

    private fun processPpgWaveChunk(encryptedBytes: ByteArray) {
        val plain = AESCrypto.decryptPpgWaveChunk(encryptedBytes) ?: return
        if (plain.size < 8) return
        if (plain[0] != 'P'.code.toByte() || plain[1] != 'W'.code.toByte()) return

        val frameId = plain[2].toInt() and 0xFF
        val chunkId = plain[3].toInt() and 0xFF
        val totalChunks = plain[4].toInt() and 0xFF
        val dataLen = plain[5].toInt() and 0xFF
        if (totalChunks <= 0 || totalChunks > 200) return
        if (dataLen > 56) return
        if (plain.size < 8 + dataLen) return
        if (frameId == ppgWaveLastCompletedFrameId) return

        if (ppgWaveRxFrameId != frameId || ppgWaveRxTotalChunks != totalChunks) {
            ppgWaveRxFrameId = frameId
            ppgWaveRxTotalChunks = totalChunks
            ppgWaveRxReceived = BooleanArray(totalChunks)
            ppgWaveRxChunks = Array(totalChunks) { ByteArray(0) }
            Log.i(TAG, "PPG_WAVE frame start frame=$frameId chunks=$totalChunks chunk=$chunkId dataLen=$dataLen")
        }
        if (chunkId >= totalChunks) return
        if (!ppgWaveRxReceived[chunkId]) {
            ppgWaveRxReceived[chunkId] = true
            ppgWaveRxChunks[chunkId] = plain.copyOfRange(8, 8 + dataLen)
        }

        if (ppgWaveRxReceived.count { it } != totalChunks) return

        val assembled = ByteArray(ppgWaveRxChunks.sumOf { it.size })
        var writeOffset = 0
        for (i in 0 until totalChunks) {
            val chunk = ppgWaveRxChunks[i]
            System.arraycopy(chunk, 0, assembled, writeOffset, chunk.size)
            writeOffset += chunk.size
        }

        val expectedBytes = PPG_WAVE_WINDOW_BYTES
        if (assembled.size != expectedBytes) {
            Log.w(TAG, "PPG_WAVE assembled ${assembled.size} bytes (expected $expectedBytes); dropping frame $frameId")
            ppgWaveRxFrameId = -1
            ppgWaveRxTotalChunks = 0
            ppgWaveRxReceived = BooleanArray(0)
            ppgWaveRxChunks = emptyArray()
            return
        }

        Log.i(
            TAG,
            "PPG_WAVE window ok frame=$frameId bytes=${assembled.size} " +
                "s0=${readInt32LE(assembled, 0)} s1=${readInt32LE(assembled, 4)} s2=${readInt32LE(assembled, 8)}",
        )

        val window = IntArray(expectedBytes / 4)
        var j = 0
        var off = 0
        while (off + 4 <= assembled.size && j < window.size) {
            window[j++] = readInt32LE(assembled, off)
            off += 4
        }
        runBpInferenceFromWaveform(window)

        ppgWaveLastCompletedFrameId = frameId
        ppgWaveRxFrameId = -1
        ppgWaveRxTotalChunks = 0
        ppgWaveRxReceived = BooleanArray(0)
        ppgWaveRxChunks = emptyArray()
    }

    private fun runBpInferenceFromWaveform(window: IntArray) {
        Thread {
            val pred = bpRunner.predictFromWaveform(window) ?: return@Thread
            BpPredictionStore.save(this, pred.sbp, pred.dbp)
            runOnUiThread { updateBpUi(pred.sbp, pred.dbp) }
        }.start()
    }

    private fun readInt32LE(buf: ByteArray, off: Int): Int {
        if (off + 4 > buf.size) return 0
        return (buf[off].toInt() and 0xFF) or
            ((buf[off + 1].toInt() and 0xFF) shl 8) or
            ((buf[off + 2].toInt() and 0xFF) shl 16) or
            ((buf[off + 3].toInt() and 0xFF) shl 24)
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
        updateHeartRate(samples, windowStartMs = since, windowEndMs = System.currentTimeMillis())
    }

    private fun refreshSpo2() {
        val since = System.currentTimeMillis() - spo2Window.ms
        val samples = VitalsHistoryStore.readSince(this, since)
            .filter { it.type == VitalsHistoryStore.Type.SPO2 }
        updateSpo2(samples, windowStartMs = since, windowEndMs = System.currentTimeMillis())
    }

    private fun updateHeartRate(
        samples: List<VitalsHistoryStore.Sample>,
        windowStartMs: Long,
        windowEndMs: Long
    ) {
        val latest = samples.lastOrNull()
        val bpm = latest?.value?.toInt()

        hrValue.text = if (bpm != null) formatValueWithUnit(bpm.toString(), "BPM") else "-- BPM"
        val status = if (bpm != null) heartRateState(bpm) else getString(R.string.dashboard_status_normal)
        hrStatus.text = status
        applyNeonStatus(hrStatus, status)
        hrUpdated.text = formatUpdatedAgo(latest?.epochMs)

        val windowSeconds = ((windowEndMs - windowStartMs) / 1_000.0).toFloat().coerceAtLeast(1f)
        val entries = samples
            .filterPlausibleVitals(VitalsHistoryStore.Type.HR)
            .toSmoothedEntries(windowStartMs = windowStartMs, windowEndMs = windowEndMs, maxPoints = 120)
        if (entries.isEmpty()) {
            hrChart.clear()
        } else {
            hrChart.xAxis.axisMinimum = 0f
            hrChart.xAxis.axisMaximum = windowSeconds
            hrChart.data = LineData(LineDataSet(entries, "HR").apply {
                color = Color.parseColor("#EC4D75")
                setDrawCircles(false)
                setDrawValues(false)
                lineWidth = 2.5f
                mode = LineDataSet.Mode.CUBIC_BEZIER
                cubicIntensity = 0.15f
                // Filled path "closes" back to baseline and can look like the line goes backwards.
                // Use line-only to keep the chart visually monotonic.
                setDrawFilled(false)
                highLightColor = Color.TRANSPARENT
            })
        }
        hrChart.invalidate()
    }

    private fun updateSpo2(
        samples: List<VitalsHistoryStore.Sample>,
        windowStartMs: Long,
        windowEndMs: Long
    ) {
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
        val status = if (v != null) spo2State(v) else getString(R.string.dashboard_status_healthy)
        spo2Status.text = status
        applyNeonStatus(spo2Status, status)
        spo2Updated.text = formatUpdatedAgo(latest?.epochMs)

        val windowSeconds = ((windowEndMs - windowStartMs) / 1_000.0).toFloat().coerceAtLeast(1f)
        val entries = samples
            .filterPlausibleVitals(VitalsHistoryStore.Type.SPO2)
            .toSmoothedEntries(windowStartMs = windowStartMs, windowEndMs = windowEndMs, maxPoints = 120)
        if (entries.isEmpty()) {
            spo2Chart.clear()
        } else {
            spo2Chart.xAxis.axisMinimum = 0f
            spo2Chart.xAxis.axisMaximum = windowSeconds
            spo2Chart.data = LineData(LineDataSet(entries, "SpO2").apply {
                color = Color.parseColor("#2D7EEA")
                setDrawCircles(false)
                setDrawValues(false)
                lineWidth = 2.5f
                mode = LineDataSet.Mode.CUBIC_BEZIER
                cubicIntensity = 0.15f
                // Filled path "closes" back to baseline and can look like the line goes backwards.
                setDrawFilled(false)
                highLightColor = Color.TRANSPARENT
            })
        }
        spo2Chart.invalidate()
    }

    private fun List<VitalsHistoryStore.Sample>.filterPlausibleVitals(type: VitalsHistoryStore.Type): List<VitalsHistoryStore.Sample> {
        return when (type) {
            VitalsHistoryStore.Type.HR ->
                filter { it.value.isFinite() && it.value >= 35.0 && it.value <= 220.0 }
            VitalsHistoryStore.Type.SPO2 ->
                filter { it.value.isFinite() && it.value >= 70.0 && it.value <= 100.5 }
        }
    }

    /**
     * Make the chart look stable:
     * - time-bucket to reduce jaggedness from rapid sampling
     * - apply a small moving-average on the buckets
     * - cap the number of plotted points to avoid over-dense zig-zag lines
     */
    private fun List<VitalsHistoryStore.Sample>.toSmoothedEntries(
        windowStartMs: Long,
        windowEndMs: Long,
        maxPoints: Int
    ): List<Entry> {
        if (isEmpty()) return emptyList()
        val sorted = this.sortedBy { it.epochMs }
        val t0 = windowStartMs
        val tN = windowEndMs
        val windowMs = (tN - t0).coerceAtLeast(1L)

        // Choose a bucket size so that we plot <= maxPoints points.
        val bucketMs = ((windowMs / maxPoints.toLong()).coerceAtLeast(60_000L)) // at least 1 minute bins

        // Average samples per bucket
        val buckets = LinkedHashMap<Long, MutableList<Double>>()
        for (s in sorted) {
            val clamped = s.epochMs.coerceIn(t0, tN)
            val b = ((clamped - t0) / bucketMs) * bucketMs
            buckets.getOrPut(b) { mutableListOf() }.add(s.value)
        }
        val bucketAverages = buckets.entries.map { (bucketOffsetMs, values) ->
            bucketOffsetMs to (values.sum() / values.size.toDouble())
        }

        // 3-point moving average to remove pointy spikes
        val smoothed = bucketAverages.mapIndexed { i, (xMs, y) ->
            val y0 = bucketAverages.getOrNull(i - 1)?.second ?: y
            val y1 = y
            val y2 = bucketAverages.getOrNull(i + 1)?.second ?: y
            xMs to ((y0 + y1 + y2) / 3.0)
        }

        // Ensure strictly-increasing X (MPAndroidChart can render strangely if X goes backwards/equal)
        val out = ArrayList<Entry>(smoothed.size)
        var lastX = -1f
        for ((offsetMs, y) in smoothed) {
            var x = (offsetMs / 1_000.0).toFloat().coerceAtLeast(0f)
            if (x <= lastX) {
                x = lastX + 0.001f
            }
            out.add(Entry(x, y.toFloat()))
            lastX = x
        }
        return out
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

    private fun applyNeonStatus(view: TextView, status: String) {
        val color = when (status.lowercase(Locale.US)) {
            "normal", "healthy" -> Color.parseColor("#2D7EEA")
            "resting", "watch" -> Color.parseColor("#FFC107")
            "elevated", "low" -> Color.parseColor("#F58433")
            "critical" -> Color.parseColor("#E53935")
            else -> Color.parseColor("#6B7480")
        }
        view.setTextColor(color)
        view.backgroundTintList = ColorStateList.valueOf(color.withAlpha(28))
    }

    private fun Int.withAlpha(alpha: Int): Int {
        return (alpha.coerceIn(0, 255) shl 24) or (this and 0x00FFFFFF)
    }

    private fun formatValueWithUnit(value: String, unit: String): String {
        return "$value $unit"
    }
}
