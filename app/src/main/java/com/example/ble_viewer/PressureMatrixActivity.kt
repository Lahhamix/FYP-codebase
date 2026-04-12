package com.example.ble_viewer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.math.*

enum class PressureDisplayMode { PIX, DYNAMIC, FIXED }

class PressureMatrixActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PressureMatrix"
        private const val ROWS = 16
        private const val COLS = 48
        private const val NUM_VALUES = ROWS * COLS
        private const val SAMPLES_PER_PACKET = 8
        private const val PACKETS_PER_FRAME = 96
        private const val BASELINE_FRAMES = 100
        private const val ADC_MAX = 4095.0f
        
        // Visualization parameters (match Python run_unified_pyqt_cont preprocess_dynamic + upscale)
        private const val THRESHOLD_RAW = 1000
        private const val SMOOTH_SIGMA = 0.2f
        private const val DISPLAY_SMOOTH = 0f
        private const val UPSCALE_DYNAMIC = 40
        private const val UPSCALE_FIXED = 40
        private const val GAMMA = 2.0f
        private const val VMIN_FIXED = 0.0f
        private const val VMAX_FIXED = 1.0f
        private const val DYNAMIC_LOW_FRACTION = 0.2f   // zero out < 0.2 * max after smooth
        private val THR_NORM_HALF = (THRESHOLD_RAW / ADC_MAX) * 0.5f  // Python thr_norm * 0.5
        private const val LOG_STATS_EVERY = 60
        private const val CALIBRATION_FRAME_INTERVAL_MS = 200L  // match Python BLE_FRAME_INTERVAL_S
        private const val ROT90_K = 1
        private const val FLIP_LR = false
        private const val FLIP_UD = true

        // === Developer-only config (change in code, rebuild). Matches Python CONFIG_MODE / params. ===
        private val CONFIG_MODE = PressureDisplayMode.DYNAMIC
        private val CONFIG_UPSCALE_DYNAMIC = UPSCALE_DYNAMIC
        private val CONFIG_UPSCALE_FIXED = UPSCALE_FIXED
        private val CONFIG_GAMMA = GAMMA
        private val CONFIG_SMOOTH_SIGMA = SMOOTH_SIGMA
        private val CONFIG_PIX_DYNAMIC_SCALING = true
        private val CONFIG_VMIN_FIXED = VMIN_FIXED
        private val CONFIG_VMAX_FIXED = VMAX_FIXED

        const val EXTRA_START_CALIBRATION = "start_calibration"
    }

    private data class PressureConfig(
        val mode: PressureDisplayMode,
        val upscaleDynamic: Int,
        val upscaleFixed: Int,
        val gamma: Float,
        val smoothSigma: Float,
        val pixDynamicScaling: Boolean,
        val vminFixed: Float,
        val vmaxFixed: Float
    )

    private lateinit var heatmapImageView: ImageView
    private lateinit var calibrateButton: Button
    private lateinit var calibrationProgress: ProgressBar
    private var disconnectDialog: AlertDialog? = null

    private val config get() = PressureConfig(
        mode = CONFIG_MODE,
        upscaleDynamic = CONFIG_UPSCALE_DYNAMIC,
        upscaleFixed = CONFIG_UPSCALE_FIXED,
        gamma = CONFIG_GAMMA,
        smoothSigma = CONFIG_SMOOTH_SIGMA,
        pixDynamicScaling = CONFIG_PIX_DYNAMIC_SCALING,
        vminFixed = CONFIG_VMIN_FIXED,
        vmaxFixed = CONFIG_VMAX_FIXED
    )

    // Python-style: single rolling buffer, each packet writes directly into it
    private val frameBuffer = FloatArray(NUM_VALUES)

    // Baseline: loaded from file if present; recalibrate with button rewrites it
    private var baseline: FloatArray? = null
    private val baselineFile by lazy { File(filesDir, "pressure_baseline.dat") }

    // Calibration: when user presses Calibrate, collect BASELINE_FRAMES and overwrite baseline
    private var isCalibrating = false
    private val calibrationFrames = mutableListOf<FloatArray>()
    private var lastCalibrationSampleTimeMs = 0L

    // Debug (Logcat): track display mode and throttle stats
    private var lastDisplayMode: String? = null
    private var updateCount = 0
    private var loggedBaselineTooHigh = false

    private var updateHandler: android.os.Handler? = null
    private var updateRunnable: Runnable? = null

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
        setContentView(R.layout.activity_pressure_matrix)

        heatmapImageView = findViewById(R.id.heatmapImageView)
        calibrateButton = findViewById(R.id.calibrateButton)
        calibrationProgress = findViewById(R.id.calibrationProgress)

        loadBaseline()

        calibrationProgress.max = BASELINE_FRAMES
        calibrationProgress.progress = 0
        calibrationProgress.visibility = View.GONE
        calibrateButton.text = "Calibrate"
        calibrateButton.setOnClickListener { startCalibration() }
        // Prevent theme from tinting the background so the white button and dark_blue text from layout show
        calibrateButton.backgroundTintList = null

        if (intent.getBooleanExtra(EXTRA_START_CALIBRATION, false)) {
            startCalibration()
        }

        // Update heatmap periodically
        updateHandler = android.os.Handler(android.os.Looper.getMainLooper())
        updateRunnable = object : Runnable {
            override fun run() {
                if (isDestroyed || isFinishing) return
                updateHeatmap()
                updateHandler?.postDelayed(this, 20) // ~50 FPS — sample rolling buffer often (Python uses 5 ms) so pressed region is visible
            }
        }
        updateRunnable?.let { updateHandler?.post(it) }
    }

    // Python-style: each packet updates buffer directly at startIndex (no frame assembly)
    private fun processPressurePacket(packet: ByteArray) {
        if (packet.size != 20 && packet.size != 24) return
        if ((packet[0].toInt() and 0xFF) != 0xA5 || (packet[1].toInt() and 0xFF) != 0x5A) return

        val startIndex = (packet[4].toInt() and 0xFF) or ((packet[5].toInt() and 0xFF) shl 8)
        val sampleCount = packet[6].toInt() and 0xFF
        val payload: ByteArray = if (packet.size == 24) {
            // 8 header + 16 ciphertext -> decrypt into 12-byte payload
            val cipher = packet.copyOfRange(8, 24)
            val plain = AESCrypto.decryptPressurePayload(cipher)
            if (plain == null || plain.size != 12) return
            plain
        } else {
            // Legacy/plain mode (kept for backwards compatibility)
            packet.copyOfRange(8, 20)  // 12 bytes
        }

        val samples = unpack12BitSamples(payload, sampleCount)
        val end = minOf(startIndex + samples.size, NUM_VALUES)
        synchronized(frameBuffer) {
            for (i in 0 until (end - startIndex)) {
                frameBuffer[startIndex + i] = samples[i].toFloat()
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
        calibrationProgress.progress = 0
        calibrationProgress.visibility = View.VISIBLE
        calibrateButton.isEnabled = false
        calibrateButton.text = "Calibrating... 0/$BASELINE_FRAMES.\nDo not step!"
        Log.i(TAG, "Calibration started: collecting $BASELINE_FRAMES frames")
    }

    private fun stopCalibrationAndApply(newBaseline: FloatArray) {
        isCalibrating = false
        calibrationFrames.clear()
        baseline = newBaseline
        calibrationProgress.visibility = View.GONE
        calibrateButton.isEnabled = true
        calibrateButton.text = "Calibration complete"
        Log.i(TAG, "Calibration complete: baseline updated")
        calibrateButton.animate()
            .alpha(0f)
            .setDuration(800)
            .withEndAction { calibrateButton.visibility = View.GONE }
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
            val bMin = b.minOrNull() ?: 0f
            val bMax = b.maxOrNull() ?: 0f
            val bMean = b.average().toFloat()
            Log.d(TAG, "Baseline loaded: ${baselineFile.name} | min=$bMin max=$bMax mean=$bMean (expect ADC 0..4095)")
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
        synchronized(frameBuffer) {
            frame = frameBuffer.copyOf()
        }

        // Calibration: sample one frame every CALIBRATION_FRAME_INTERVAL_MS (like Python)
        if (isCalibrating && calibrationFrames.size < BASELINE_FRAMES) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastCalibrationSampleTimeMs >= CALIBRATION_FRAME_INTERVAL_MS) {
                lastCalibrationSampleTimeMs = now
                calibrationFrames.add(frame.copyOf())
                val n = calibrationFrames.size
                calibrationProgress.progress = n
                calibrateButton.text = "Calibrating... $n/$BASELINE_FRAMES.\nDo not step!"
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

        val base = baseline
        val processed = FloatArray(NUM_VALUES)
        var displayMode: String
        if (base != null) {
            // Python order: subtract baseline, clamp < 0, then THRESHOLD_RAW on delta, then normalize
            for (i in processed.indices) {
                var v = frame[i] - base[i]
                if (v < 0) v = 0f
                if (v < THRESHOLD_RAW) v = 0f
                processed[i] = (v / ADC_MAX).coerceIn(0f, 1f)
            }
            val maxProcessed = processed.maxOrNull() ?: 0f
            if (maxProcessed <= 0f) {
                displayMode = "baseline-subtracted (all zeros — re-record baseline with NO pressure)"
                // Keep showing zeros; do NOT fall back to raw so that at rest you see zeros and when you press (with correct baseline) you see activity
                logBaselineTooHighOnce(frame, base)
            } else {
                displayMode = "baseline-subtracted"
            }
        } else {
            displayMode = "raw (no baseline)"
            for (i in processed.indices) {
                var v = frame[i]
                if (v < THRESHOLD_RAW) v = 0f
                processed[i] = (v / ADC_MAX).coerceIn(0f, 1f)
            }
        }
        // Logcat debug: log when display mode changes, and periodically log stats
        if (displayMode != lastDisplayMode) {
            lastDisplayMode = displayMode
            Log.i(TAG, "Display mode: $displayMode")
        }
        updateCount++
        if (updateCount % LOG_STATS_EVERY == 0) {
            val fMin = frame.minOrNull() ?: 0f
            val fMax = frame.maxOrNull() ?: 0f
            val pMin = processed.minOrNull() ?: 0f
            val pMax = processed.maxOrNull() ?: 0f
            Log.d(TAG, "Stats | mode=$displayMode | frame raw min=$fMin max=$fMax | processed min=$pMin max=$pMax")
        }
        val forDisplay = when (config.mode) {
            PressureDisplayMode.PIX -> preprocessPix(processed)
            PressureDisplayMode.DYNAMIC -> preprocessDynamic(processed)
            PressureDisplayMode.FIXED -> preprocessFixed(processed)
        }
        val bitmap = createHeatmapBitmap(forDisplay, config.mode)
        runOnUiThread {
            if (!isDestroyed && !isFinishing) {
                heatmapImageView.setImageBitmap(bitmap)
            }
        }
    }

    /** Python preprocess_pix: no smooth, no 0.2*max, no thr_norm*0.5, no gamma. */
    private fun preprocessPix(normalized: FloatArray): FloatArray = normalized.copyOf()

    /** Python preprocess_dynamic: gaussian, 0.2*max zero, thr_norm*0.5 zero, gamma. */
    private fun preprocessDynamic(normalized: FloatArray): FloatArray {
        val out = normalized.copyOf()
        gaussianFilter2D(out, ROWS, COLS, config.smoothSigma)
        val m = out.maxOrNull() ?: 0f
        if (m > 0f) {
            val threshold = DYNAMIC_LOW_FRACTION * m
            for (i in out.indices) if (out[i] < threshold) out[i] = 0f
        }
        for (i in out.indices) if (out[i] < THR_NORM_HALF) out[i] = 0f
        for (i in out.indices) if (out[i] > 0f) out[i] = out[i].pow(config.gamma)
        return out
    }

    /** Python preprocess_fixed: gaussian, thr_norm*0.5 zero, gamma (no 0.2*max). */
    private fun preprocessFixed(normalized: FloatArray): FloatArray {
        val out = normalized.copyOf()
        gaussianFilter2D(out, ROWS, COLS, config.smoothSigma)
        for (i in out.indices) if (out[i] < THR_NORM_HALF) out[i] = 0f
        for (i in out.indices) if (out[i] > 0f) out[i] = out[i].pow(config.gamma)
        return out
    }

    /** 3x3 Gaussian kernel (sigma), applied on ROWS x COLS stored row-major in data. */
    private fun gaussianFilter2D(data: FloatArray, rows: Int, cols: Int, sigma: Float) {
        val k = 3
        val kernel = FloatArray(k * k)
        var sum = 0f
        for (dy in -1..1) for (dx in -1..1) {
            val g = exp(-(dx * dx + dy * dy) / (2f * sigma * sigma))
            kernel[(dy + 1) * k + (dx + 1)] = g
            sum += g
        }
        for (i in kernel.indices) kernel[i] /= sum
        val tmp = FloatArray(data.size)
        for (r in 0 until rows) for (c in 0 until cols) {
            var v = 0f
            for (dy in -1..1) for (dx in -1..1) {
                val rr = (r + dy).coerceIn(0, rows - 1)
                val cc = (c + dx).coerceIn(0, cols - 1)
                v += data[rr * cols + cc] * kernel[(dy + 1) * k + (dx + 1)]
            }
            tmp[r * cols + c] = v
        }
        tmp.copyInto(data)
    }

    private fun percentile95(data: FloatArray): Float {
        val nonzero = data.filter { it > 0 }
        if (nonzero.isEmpty()) return 1.0f
        val sorted = nonzero.sorted()
        val p95 = sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.size - 1)]
        return if (p95 > 1e-6f) p95 else 1.0f
    }

    /** Bilinear sample from data[rows x cols] at (row, col) float. */
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

    private fun createHeatmapBitmap(data: FloatArray, mode: PressureDisplayMode): Bitmap {
        // PIX: use same display size as dynamic but fill with nearest-neighbor so pixels are crisp (no blur from scaling a 16x48 bitmap).
        // DYNAMIC/FIXED: use bilinear sampling for smooth heatmap.
        val upscale = when (mode) {
            PressureDisplayMode.PIX -> config.upscaleDynamic.coerceAtLeast(1)  // large bitmap, nearest-neighbor fill
            PressureDisplayMode.DYNAMIC -> config.upscaleDynamic.coerceAtLeast(1)
            PressureDisplayMode.FIXED -> config.upscaleFixed.coerceAtLeast(1)
        }
        val displayWidth = ROWS * upscale
        val displayHeight = COLS * upscale
        val useNearestNeighbor = (mode == PressureDisplayMode.PIX)

        val bitmap = Bitmap.createBitmap(displayWidth, displayHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val (vmin, vmax) = when (mode) {
            PressureDisplayMode.FIXED -> config.vminFixed to config.vmaxFixed
            PressureDisplayMode.PIX -> 0.0f to if (config.pixDynamicScaling) percentile95(data) else 1.0f
            PressureDisplayMode.DYNAMIC -> 0.0f to percentile95(data)
        }
        val vrange = if (vmax > vmin) vmax - vmin else 1e-6f

        val colors = intArrayOf(
            Color.rgb(255, 255, 255),
            Color.rgb(0, 51, 255),
            Color.rgb(0, 230, 255),
            Color.rgb(0, 242, 51),
            Color.rgb(255, 255, 0),
            Color.rgb(255, 153, 0),
            Color.rgb(255, 0, 0)
        )

        val scaleF = upscale.toFloat()
        val pxForSample = if (FLIP_LR) { px: Int -> displayWidth - 1 - px } else { px: Int -> px }
        val pyForSample = if (FLIP_UD) { py: Int -> displayHeight - 1 - py } else { py: Int -> py }
        val pixels = IntArray(displayWidth * displayHeight)
        for (py in 0 until displayHeight) {
            for (px in 0 until displayWidth) {
                val value = if (useNearestNeighbor) {
                    val row = (ROWS - 1 - (pxForSample(px) / scaleF).toInt()).coerceIn(0, ROWS - 1)
                    val col = (pyForSample(py) / scaleF).toInt().coerceIn(0, COLS - 1)
                    data[row * COLS + col]
                } else {
                    val rowF = (ROWS - 1 - (pxForSample(px) / scaleF)).coerceIn(0f, (ROWS - 1).toFloat())
                    val colF = (pyForSample(py) / scaleF).coerceIn(0f, (COLS - 1).toFloat())
                    sampleBilinear(data, ROWS, COLS, rowF, colF)
                }
                val color = if (value <= 0f) {
                    Color.WHITE
                } else {
                    val normalized = ((value - vmin) / vrange).coerceIn(0f, 1f)
                    val colorIdx = (normalized * (colors.size - 1)).toInt().coerceIn(0, colors.size - 1)
                    val nextIdx = (colorIdx + 1).coerceAtMost(colors.size - 1)
                    val t = (normalized * (colors.size - 1)) - colorIdx
                    interpolateColor(colors[colorIdx], colors[nextIdx], t)
                }
                pixels[py * displayWidth + px] = color
            }
        }
        bitmap.setPixels(pixels, 0, displayWidth, 0, 0, displayWidth, displayHeight)

        return bitmap
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

    private fun showDisconnectDialog() {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            if (disconnectDialog?.isShowing == true) return@runOnUiThread

            disconnectDialog = AlertDialog.Builder(this)
                .setTitle(getString(R.string.dialog_connection_lost))
                .setMessage(getString(R.string.dialog_wearable_disconnected))
                .setCancelable(false)
                .setPositiveButton("OK") { _, _ ->
                    finish()
                }
                .create()

            disconnectDialog?.show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isCalibrating) {
            calibrateButton.visibility = View.VISIBLE
            calibrateButton.alpha = 1f
            calibrateButton.text = "Calibrate"
        }
        val filter = IntentFilter().apply {
            addAction(MainActivity.ACTION_SENSOR_DATA)
            addAction(MainActivity.ACTION_DEVICE_DISCONNECTED)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(sensorDataReceiver, filter)
    }

    override fun onPause() {
        super.onPause()
        updateRunnable?.let { updateHandler?.removeCallbacks(it) }
        LocalBroadcastManager.getInstance(this).unregisterReceiver(sensorDataReceiver)
    }

    override fun onDestroy() {
        updateRunnable = null
        updateHandler = null
        super.onDestroy()
    }
}
