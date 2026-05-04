package com.example.ble_viewer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Live PHBLE pressure heatmap pipeline + optional steps/motion widgets.
 * Shared by [PressureMatrixActivity] (matrix screen) and [PlantarFootAnalyticsActivity].
 */
class PlantarPressureLiveController(
    private val activity: AppCompatActivity,
    private val heatmapImageView: ImageView,
    private val calibrateButton: Button,
    private val calibrationProgress: ProgressBar,
    private val zoneAtaxiaStatus: TextView?,
    private val zoneMotionStatus: TextView?,
    private val zoneStepDonut: StepDonutView?,
    private val trackStepsMotion: Boolean,
    private val onDeviceDisconnected: () -> Unit,
    /**
     * When true (Plantar analytics), the heatmap bitmap is rotated 90° clockwise for display only.
     * Packet / matrix indexing and saved snapshots stay in the canonical (unrotated) orientation.
     */
    private val rotateHeatmapClockwise90ForDisplay: Boolean = false,
) {

    companion object {
        private const val TAG = "PlantarPressureLive"
        private const val ROWS = 43
        private const val COLS = 14
        private const val NUM_VALUES = ROWS * COLS
        private const val SAMPLES_PER_PACKET = 8
        private const val BASELINE_FRAMES = 10
        private const val ADC_MAX = 4095.0f

        private const val THRESHOLD_RAW = 1000.0f
        private const val LOW_RES_SMOOTH_SIGMA = 0.85f
        private const val DISPLAY_SMOOTH_SIGMA = 2.2f
        private const val DISPLAY_UPSCALE = 36
        private const val GAMMA = 0.65f
        private const val PERCENTILE_MAX = 97.5f
        private const val DISPLAY_CUTOFF = 0.035f
        private const val MASK_CUTOFF = 0.025f

        private const val LOG_STATS_EVERY = 60
        private const val CALIBRATION_FRAME_INTERVAL_MS = 200L
        private const val ROT90_K = 0
        private const val FLIP_LR = true
        private const val FLIP_UD = false

        private const val PREFS_NAME = "SolematePrefs"
        const val HEATMAP_SNAPSHOT_FILE = "latest_pressure_heatmap.png"
        const val KEY_HEATMAP_LAST_UPDATED_MS = "analytics_heatmap_last_updated_ms"
        private const val HEATMAP_CAPTURE_INTERVAL_MS = 10 * 60 * 1000L

        private const val STEPS_CHAR_UUID = "9a8b0002-6d5e-4c10-b6d9-1f25c09d9e00"
        private const val MOTION_CHAR_UUID = "9a8b0003-6d5e-4c10-b6d9-1f25c09d9e00"

        fun colorWithAlpha(color: Int, alpha: Int): Int {
            return (alpha.coerceIn(0, 255) shl 24) or (color and 0x00FFFFFF)
        }

        /** Display-only rotation; does not change underlying pressure readings. */
        fun rotateHeatmapClockwise90(source: Bitmap): Bitmap {
            val matrix = Matrix().apply { postRotate(90f) }
            return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        }
    }

    private data class MatrixData(
        val values: FloatArray,
        val rows: Int,
        val cols: Int,
    )

    private val workingFrameBuffer = FloatArray(NUM_VALUES)
    private val frameBuffer = FloatArray(NUM_VALUES)
    private var frameVersion = 0L
    private var fullFrameVersion = 0L
    private var renderedFrameVersion = -1L

    private var baseline: FloatArray? = null
    private val baselineFile by lazy { File(activity.filesDir, "pressure_baseline.dat") }

    private var isCalibrating = false
    private val calibrationFrames = mutableListOf<FloatArray>()
    private var lastCalibrationSampleTimeMs = 0L
    private var calibrationStartFullFrameVersion = 0L
    private var lastCalibrationFullFrameVersion = 0L

    private var lastDisplayMode: String? = null
    private var updateCount = 0
    private var loggedBaselineTooHigh = false

    private var updateHandler: android.os.Handler? = null
    private var updateRunnable: Runnable? = null
    private var heatmapTickerRunning = false

    private val sensorDataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                MainActivity.ACTION_SENSOR_DATA -> {
                    val uuidString = intent.getStringExtra(MainActivity.EXTRA_UUID_STRING)
                    if (uuidString == MainActivity.pressureCharUuid.toString()) {
                        val packet = intent.getByteArrayExtra(MainActivity.EXTRA_DECRYPTED_DATA)
                        if (packet != null && (packet.size == 20 || packet.size == 24)) {
                            processPressurePacket(packet)
                        }
                    } else if (trackStepsMotion && uuidString == STEPS_CHAR_UUID) {
                        val encryptedBytes = intent.getByteArrayExtra(MainActivity.EXTRA_DECRYPTED_DATA) ?: return
                        val raw = AESCrypto.decryptSteps(encryptedBytes).trim()
                        raw.toIntOrNull()?.let { zoneStepDonut?.setSteps(it) }
                    } else if (trackStepsMotion && uuidString == MOTION_CHAR_UUID) {
                        val encryptedBytes = intent.getByteArrayExtra(MainActivity.EXTRA_DECRYPTED_DATA) ?: return
                        val raw = AESCrypto.decryptMotion(encryptedBytes).trim()
                        if (raw.isNotEmpty() && !raw.startsWith("DECRYPT_ERROR")) {
                            zoneMotionStatus?.text = if (raw == "1") {
                                activity.getString(R.string.motion_in_motion)
                            } else {
                                activity.getString(R.string.motion_static)
                            }
                        }
                    }
                }
                MainActivity.ACTION_DEVICE_DISCONNECTED -> onDeviceDisconnected()
            }
        }
    }

    fun initialize(startCalibrationImmediately: Boolean) {
        loadBaseline()

        calibrationProgress.max = BASELINE_FRAMES
        calibrationProgress.progress = 0
        calibrationProgress.visibility = View.GONE
        calibrateButton.text = activity.getString(R.string.calibrate)
        calibrateButton.setOnClickListener { startCalibration() }
        calibrateButton.backgroundTintList = null

        zoneStepDonut?.setSteps(0)
        zoneAtaxiaStatus?.apply {
            text = activity.getString(R.string.status_normal)
            setTextColor(Color.parseColor("#F58433"))
            backgroundTintList = ColorStateList.valueOf(colorWithAlpha(Color.parseColor("#F58433"), 36))
        }
        zoneMotionStatus?.backgroundTintList =
            ColorStateList.valueOf(colorWithAlpha(Color.parseColor("#7E3EEA"), 36))

        updateHandler = android.os.Handler(android.os.Looper.getMainLooper())
        updateRunnable = object : Runnable {
            override fun run() {
                if (activity.isDestroyed || activity.isFinishing) return
                updateHeatmap()
                updateHandler?.postDelayed(this, 20)
            }
        }

        if (startCalibrationImmediately) {
            startCalibration()
        }
        startHeatmapTicker()
    }

    fun onResume() {
        if (!isCalibrating) {
            calibrateButton.visibility = View.VISIBLE
            calibrateButton.alpha = 1f
            calibrateButton.text = activity.getString(R.string.calibrate)
        }
        startHeatmapTicker()
        val filter = IntentFilter().apply {
            addAction(MainActivity.ACTION_SENSOR_DATA)
            addAction(MainActivity.ACTION_DEVICE_DISCONNECTED)
        }
        LocalBroadcastManager.getInstance(activity).registerReceiver(sensorDataReceiver, filter)
    }

    fun onPause() {
        stopHeatmapTicker()
        LocalBroadcastManager.getInstance(activity).unregisterReceiver(sensorDataReceiver)
    }

    fun onDestroy() {
        stopHeatmapTicker()
        updateRunnable = null
        updateHandler = null
    }

    private fun startHeatmapTicker() {
        if (heatmapTickerRunning) return
        val runnable = updateRunnable ?: return
        heatmapTickerRunning = true
        updateHandler?.post(runnable)
    }

    private fun stopHeatmapTicker() {
        val runnable = updateRunnable ?: return
        updateHandler?.removeCallbacks(runnable)
        heatmapTickerRunning = false
    }

    private fun processPressurePacket(packet: ByteArray) {
        if (packet.size != 20 && packet.size != 24) return
        if ((packet[0].toInt() and 0xFF) != 0xA5 || (packet[1].toInt() and 0xFF) != 0x5A) return

        val startIndex = (packet[4].toInt() and 0xFF) or ((packet[5].toInt() and 0xFF) shl 8)
        val sampleCount = packet[6].toInt() and 0xFF
        val flags = packet[7].toInt() and 0xFF
        if (sampleCount !in 1..SAMPLES_PER_PACKET || startIndex !in 0 until NUM_VALUES) return
        val payload: ByteArray = if (packet.size == 24) {
            val cipher = packet.copyOfRange(8, 24)
            val plain = AESCrypto.decryptPressurePayload(cipher)
            if (plain == null || plain.size != 12) return
            plain
        } else {
            packet.copyOfRange(8, 20)
        }

        val samples = unpack12BitSamples(payload, sampleCount)
        val end = minOf(startIndex + samples.size, NUM_VALUES)
        synchronized(workingFrameBuffer) {
            for (i in 0 until (end - startIndex)) {
                val value = samples[i].toFloat()
                workingFrameBuffer[startIndex + i] = value
                frameBuffer[startIndex + i] = value
            }
            frameVersion++
            if ((flags and 0x02) != 0) {
                fullFrameVersion++
            }
        }
    }

    private fun unpack12BitSamples(payload: ByteArray, count: Int): IntArray {
        val samples = mutableListOf<Int>()
        var i = 0
        var p = 0

        while (i + 1 < count && p + 2 < payload.size) {
            val b0 = payload[p].toInt() and 0xFF
            val b1 = payload[p + 1].toInt() and 0xFF
            val b2 = payload[p + 2].toInt() and 0xFF

            val a = b0 or ((b1 and 0x0F) shl 8)
            val b = ((b1 shr 4) and 0x0F) or (b2 shl 4)

            samples.add(a)
            samples.add(b)
            p += 3
            i += 2
        }

        if (i < count && p + 1 < payload.size) {
            val b0 = payload[p].toInt() and 0xFF
            val b1 = payload[p + 1].toInt() and 0xFF
            val a = b0 or ((b1 and 0x0F) shl 8)
            samples.add(a)
        }

        return samples.toIntArray()
    }

    private fun startCalibration() {
        if (isCalibrating) return
        isCalibrating = true
        calibrationFrames.clear()
        lastCalibrationSampleTimeMs = android.os.SystemClock.elapsedRealtime()
        synchronized(workingFrameBuffer) {
            calibrationStartFullFrameVersion = fullFrameVersion
            lastCalibrationFullFrameVersion = fullFrameVersion
        }
        calibrationProgress.progress = 0
        calibrationProgress.visibility = View.VISIBLE
        calibrateButton.isEnabled = false
        calibrateButton.text =
            activity.getString(R.string.pressure_calibrating_progress_fmt, 0, BASELINE_FRAMES)
        Log.i(TAG, "Calibration started: collecting $BASELINE_FRAMES frames")
    }

    private fun stopCalibrationAndApply(newBaseline: FloatArray) {
        isCalibrating = false
        calibrationFrames.clear()
        baseline = newBaseline
        calibrationProgress.visibility = View.GONE
        calibrateButton.isEnabled = true
        calibrateButton.text = activity.getString(R.string.pressure_calibration_complete)
        Log.i(TAG, "Calibration complete: baseline updated")
        calibrateButton.animate()
            .alpha(0f)
            .setDuration(800)
            .withEndAction {
                calibrateButton.alpha = 1f
                calibrateButton.text = activity.getString(R.string.calibrate)
            }
    }

    private fun saveBaseline(data: FloatArray) {
        try {
            FileOutputStream(baselineFile).use { fos ->
                val buf = ByteArray(4)
                for (i in 0 until NUM_VALUES) {
                    val bits = java.lang.Float.floatToRawIntBits(data[i])
                    buf[0] = (bits and 0xFF).toByte()
                    buf[1] = ((bits shr 8) and 0xFF).toByte()
                    buf[2] = ((bits shr 16) and 0xFF).toByte()
                    buf[3] = ((bits shr 24) and 0xFF).toByte()
                    fos.write(buf)
                }
            }
            Log.d(TAG, "Baseline saved: ${baselineFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save baseline: ${e.message}")
        }
    }

    private fun loadBaseline() {
        try {
            if (!baselineFile.exists()) {
                Log.d(TAG, "Baseline: no file (${baselineFile.absolutePath}) — display will use raw values")
                return
            }
            val expectedBytes = NUM_VALUES * 4L
            if (baselineFile.length() != expectedBytes) {
                Log.w(TAG, "Baseline ignored: ${baselineFile.length()} bytes does not match PHBLE ${ROWS}x${COLS} baseline size $expectedBytes")
                baseline = null
                return
            }
            FileInputStream(baselineFile).use { fis ->
                baseline = FloatArray(NUM_VALUES)
                val buffer = ByteArray(4)
                for (i in 0 until NUM_VALUES) {
                    if (fis.read(buffer) != 4) {
                        baseline = null
                        Log.e(TAG, "Baseline: read truncated at index $i")
                        return
                    }
                    val bits = (buffer[0].toInt() and 0xFF) or
                        ((buffer[1].toInt() and 0xFF) shl 8) or
                        ((buffer[2].toInt() and 0xFF) shl 16) or
                        ((buffer[3].toInt() and 0xFF) shl 24)
                    baseline!![i] = java.lang.Float.intBitsToFloat(bits)
                }
            }
            val b = baseline!!
            Log.d(TAG, "Baseline loaded: ${baselineFile.name} | min=${b.minOrNull()} max=${b.maxOrNull()} mean=${b.average()}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load baseline: ${e.message}")
            baseline = null
        }
    }

    private fun logBaselineTooHighOnce(frame: FloatArray, base: FloatArray) {
        if (loggedBaselineTooHigh) return
        loggedBaselineTooHigh = true
        val fMin = frame.minOrNull() ?: 0f
        val fMax = frame.maxOrNull() ?: 0f
        val bMin = base.minOrNull() ?: 0f
        val bMax = base.maxOrNull() ?: 0f
        Log.w(TAG, "Baseline >= frame everywhere (all deltas zero). Re-record baseline with NO pressure. Expect baseline in ADC 0..4095; current baseline min=$bMin max=$bMax, frame min=$fMin max=$fMax")
    }

    private fun updateHeatmap() {
        val frame: FloatArray
        val version: Long
        val completedVersion: Long
        synchronized(workingFrameBuffer) {
            frame = frameBuffer.copyOf()
            version = frameVersion
            completedVersion = fullFrameVersion
        }
        if (version == renderedFrameVersion && !isCalibrating) return

        if (isCalibrating && calibrationFrames.size < BASELINE_FRAMES) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (completedVersion > calibrationStartFullFrameVersion &&
                completedVersion > lastCalibrationFullFrameVersion &&
                now - lastCalibrationSampleTimeMs >= CALIBRATION_FRAME_INTERVAL_MS) {
                lastCalibrationSampleTimeMs = now
                lastCalibrationFullFrameVersion = completedVersion
                calibrationFrames.add(frame.copyOf())
                val n = calibrationFrames.size
                calibrationProgress.progress = n
                calibrateButton.text =
                    activity.getString(R.string.pressure_calibrating_progress_fmt, n, BASELINE_FRAMES)
                if (n >= BASELINE_FRAMES) {
                    val newBaseline = FloatArray(NUM_VALUES)
                    for (i in 0 until NUM_VALUES) {
                        var sum = 0.0
                        for (f in calibrationFrames) sum += f[i]
                        newBaseline[i] = (sum / BASELINE_FRAMES).toFloat()
                    }
                    saveBaseline(newBaseline)
                    stopCalibrationAndApply(newBaseline)
                }
            }
        }

        val phbleBase = baseline
        val phbleProcessed = preprocessPhbleFrame(frame, phbleBase)
        val phbleDisplayMode = if (phbleBase != null) {
            if ((phbleProcessed.values.maxOrNull() ?: 0f) <= 0f) {
                logBaselineTooHighOnce(frame, phbleBase)
                "baseline-subtracted (all zeros - re-record baseline with NO pressure)"
            } else {
                "baseline-subtracted"
            }
        } else {
            "raw (no baseline)"
        }
        if (phbleDisplayMode != lastDisplayMode) {
            lastDisplayMode = phbleDisplayMode
            Log.i(TAG, "Display mode: $phbleDisplayMode")
        }
        updateCount++
        if (updateCount % LOG_STATS_EVERY == 0) {
            val fMin = frame.minOrNull() ?: 0f
            val fMax = frame.maxOrNull() ?: 0f
            val pMin = phbleProcessed.values.minOrNull() ?: 0f
            val pMax = phbleProcessed.values.maxOrNull() ?: 0f
            Log.d(TAG, "Stats | mode=$phbleDisplayMode | frame=$version | raw min=$fMin max=$fMax | processed min=$pMin max=$pMax")
        }
        val canonicalBitmap = createHeatmapBitmap(phbleProcessed)
        renderedFrameVersion = version
        maybeSaveHeatmapSnapshot(canonicalBitmap)
        val displayBitmap = if (rotateHeatmapClockwise90ForDisplay) {
            rotateHeatmapClockwise90(canonicalBitmap).also { canonicalBitmap.recycle() }
        } else {
            canonicalBitmap
        }
        activity.runOnUiThread {
            if (!activity.isDestroyed && !activity.isFinishing) {
                heatmapImageView.setImageBitmap(displayBitmap)
            }
        }
    }

    private fun preprocessPhbleFrame(rawFrame: FloatArray, base: FloatArray?): MatrixData {
        val mat = FloatArray(NUM_VALUES)
        for (i in mat.indices) {
            var v = rawFrame[i] - (base?.get(i) ?: 0f)
            if (v < 0f) v = 0f
            if (v < THRESHOLD_RAW) v = 0f
            mat[i] = v
        }

        gaussianFilter2D(mat, ROWS, COLS, LOW_RES_SMOOTH_SIGMA)
        val secondaryThreshold = THRESHOLD_RAW * 0.22f
        for (i in mat.indices) {
            if (mat[i] < secondaryThreshold) mat[i] = 0f
        }

        val vmax = percentile(mat, PERCENTILE_MAX).let { if (it > 1e-6f) it else ADC_MAX }
        for (i in mat.indices) {
            val normalized = (mat[i] / vmax).coerceIn(0f, 1f)
            mat[i] = if (normalized > 0f) normalized.pow(GAMMA) else 0f
        }

        return applyOrientation(MatrixData(mat, ROWS, COLS))
    }

    private fun applyOrientation(input: MatrixData): MatrixData {
        var out = input
        repeat(((ROT90_K % 4) + 4) % 4) {
            val rotated = FloatArray(out.values.size)
            for (r in 0 until out.rows) {
                for (c in 0 until out.cols) {
                    val nr = c
                    val nc = out.rows - 1 - r
                    rotated[nr * out.rows + nc] = out.values[r * out.cols + c]
                }
            }
            out = MatrixData(rotated, out.cols, out.rows)
        }
        if (FLIP_LR) {
            val flipped = FloatArray(out.values.size)
            for (r in 0 until out.rows) {
                for (c in 0 until out.cols) {
                    flipped[r * out.cols + (out.cols - 1 - c)] = out.values[r * out.cols + c]
                }
            }
            out = MatrixData(flipped, out.rows, out.cols)
        }
        if (FLIP_UD) {
            val flipped = FloatArray(out.values.size)
            for (r in 0 until out.rows) {
                for (c in 0 until out.cols) {
                    flipped[(out.rows - 1 - r) * out.cols + c] = out.values[r * out.cols + c]
                }
            }
            out = MatrixData(flipped, out.rows, out.cols)
        }
        return out
    }

    private fun gaussianFilter2D(data: FloatArray, rows: Int, cols: Int, sigma: Float) {
        if (sigma <= 0f || data.isEmpty()) return
        val radius = ceil(3f * sigma).toInt().coerceAtLeast(1)
        val kernel = FloatArray(radius * 2 + 1)
        var sum = 0f
        for (i in -radius..radius) {
            val g = exp(-(i * i) / (2f * sigma * sigma))
            kernel[i + radius] = g
            sum += g
        }
        for (i in kernel.indices) kernel[i] /= sum

        val horizontal = FloatArray(data.size)
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                var v = 0f
                for (dx in -radius..radius) {
                    val cc = (c + dx).coerceIn(0, cols - 1)
                    v += data[r * cols + cc] * kernel[dx + radius]
                }
                horizontal[r * cols + c] = v
            }
        }

        val vertical = FloatArray(data.size)
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                var v = 0f
                for (dy in -radius..radius) {
                    val rr = (r + dy).coerceIn(0, rows - 1)
                    v += horizontal[rr * cols + c] * kernel[dy + radius]
                }
                vertical[r * cols + c] = v
            }
        }
        vertical.copyInto(data)
    }

    private fun percentile(data: FloatArray, percentile: Float): Float {
        val nonzero = data.filter { it > 0f }
        if (nonzero.isEmpty()) return 0f
        val sorted = nonzero.sorted()
        val rank = ((percentile / 100f) * (sorted.size - 1)).roundToInt()
            .coerceIn(0, sorted.size - 1)
        return sorted[rank]
    }

    private fun sampleBilinear(data: FloatArray, rows: Int, cols: Int, row: Float, col: Float): Float {
        val r0 = row.toInt().coerceIn(0, rows - 1)
        val c0 = col.toInt().coerceIn(0, cols - 1)
        val r1 = (r0 + 1).coerceAtMost(rows - 1)
        val c1 = (c0 + 1).coerceAtMost(cols - 1)
        val tr = (row - r0).coerceIn(0f, 1f)
        val tc = (col - c0).coerceIn(0f, 1f)
        val v00 = data[r0 * cols + c0]
        val v01 = data[r0 * cols + c1]
        val v10 = data[r1 * cols + c0]
        val v11 = data[r1 * cols + c1]
        return (1 - tr) * (1 - tc) * v00 + (1 - tr) * tc * v01 + tr * (1 - tc) * v10 + tr * tc * v11
    }

    private fun createHeatmapBitmap(data: MatrixData): Bitmap {
        val upscale = DISPLAY_UPSCALE.coerceAtLeast(1)
        val displayWidth = data.cols * upscale
        val displayHeight = data.rows * upscale
        val display = FloatArray(displayWidth * displayHeight)

        for (py in 0 until displayHeight) {
            val rowF = (py.toFloat() / upscale.toFloat()).coerceIn(0f, (data.rows - 1).toFloat())
            for (px in 0 until displayWidth) {
                val colF = (px.toFloat() / upscale.toFloat()).coerceIn(0f, (data.cols - 1).toFloat())
                display[py * displayWidth + px] = sampleBilinear(data.values, data.rows, data.cols, rowF, colF)
            }
        }

        gaussianFilter2D(display, displayHeight, displayWidth, DISPLAY_SMOOTH_SIGMA)

        val bitmap = Bitmap.createBitmap(displayWidth, displayHeight, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(displayWidth * displayHeight)
        for (i in display.indices) {
            val value = if (display[i] < MASK_CUTOFF || display[i] < DISPLAY_CUTOFF) 0f else display[i].coerceIn(0f, 1f)
            pixels[i] = if (value <= 0f) Color.WHITE else mapPhbleColor(value)
        }
        bitmap.setPixels(pixels, 0, displayWidth, 0, 0, displayWidth, displayHeight)
        return bitmap
    }

    private fun mapPhbleColor(value: Float): Int {
        val colors = intArrayOf(
            Color.rgb(255, 255, 255),
            Color.rgb(25, 70, 220),
            Color.rgb(0, 170, 255),
            Color.rgb(0, 220, 95),
            Color.rgb(255, 245, 0),
            Color.rgb(255, 150, 0),
            Color.rgb(225, 0, 0),
        )
        val positions = floatArrayOf(0.0f, 0.07f, 0.18f, 0.36f, 0.62f, 0.82f, 1.0f)
        val x = value.coerceIn(0f, 1f)
        for (i in 0 until positions.lastIndex) {
            if (x <= positions[i + 1]) {
                val range = (positions[i + 1] - positions[i]).coerceAtLeast(1e-6f)
                val t = ((x - positions[i]) / range).coerceIn(0f, 1f)
                return interpolateColor(colors[i], colors[i + 1], t)
            }
        }
        return colors.last()
    }

    private fun interpolateColor(color1: Int, color2: Int, t: Float): Int {
        val r1 = Color.red(color1)
        val g1 = Color.green(color1)
        val b1 = Color.blue(color1)
        val r2 = Color.red(color2)
        val g2 = Color.green(color2)
        val b2 = Color.blue(color2)

        val r = (r1 + (r2 - r1) * t).toInt().coerceIn(0, 255)
        val g = (g1 + (g2 - g1) * t).toInt().coerceIn(0, 255)
        val b = (b1 + (b2 - b1) * t).toInt().coerceIn(0, 255)

        return Color.rgb(r, g, b)
    }

    private fun maybeSaveHeatmapSnapshot(bitmap: Bitmap) {
        val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lastSaved = prefs.getLong(KEY_HEATMAP_LAST_UPDATED_MS, 0L)
        if (now - lastSaved < HEATMAP_CAPTURE_INTERVAL_MS) return

        try {
            val outputFile = File(activity.filesDir, HEATMAP_SNAPSHOT_FILE)
            FileOutputStream(outputFile).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
            }
            prefs.edit().putLong(KEY_HEATMAP_LAST_UPDATED_MS, now).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save heatmap snapshot: ${e.message}")
        }
    }
}
