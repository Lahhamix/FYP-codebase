package com.example.ble_viewer

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import org.jtransforms.fft.FloatFFT_1D
import org.pytorch.executorch.EValue
import org.pytorch.executorch.Module
import org.pytorch.executorch.Tensor
import java.io.File
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class BpPrediction(
    val sbp: Float,
    val dbp: Float,
)

/**
 * ExecuTorch BP inference. The PyTorch training model is `BPEstimationModel(ppg, ppg1, ppg2)` with
 * internal `torch.stft`, which does not export cleanly to Core ATen. The deployed `.pte` is
 * [model_architecture.BPEstimationModelForExecuTorch]: same weights, six explicit inputs.
 *
 * Preprocessing for the three waveform tensors matches `testing/plot_ppg_model.py`. Spectrograms
 * match `torch.stft(..., center=True, pad_mode="reflect", window=ones, n_fft=128, hop=32, ...)` —
 * see [logSpectrogramTorchLike] (T=20, 65 bins).
 *
 * Inputs: ppg, ppg1, ppg2 — (1, 1, 625); spec0, spec1, spec2 — (1, 20, 65).
 * Output: (1, 2) → SBP, DBP.
 */
class BpModelRunner(private val context: Context) {
    companion object {
        private const val TAG = "BP_MODEL"

        private const val ASSET_PATH = "models/bp_model.pte"
        private const val LOCAL_REL_PATH = "models/bp_model.pte"

        private const val PREFS_NAME = "bp_model_loader"
        private const val KEY_PKG_UPDATE_TIME = "pkg_update_time_bp_model"

        private const val L = 625

        private const val N_FFT = 128
        private const val HOP = 32
        private const val WIN = 128
        private const val F_BINS = (N_FFT / 2) + 1
        private const val CENTER = true
        private const val PAD = N_FFT / 2

        const val SBP_RANGE_PLUS_MINUS = 6
        const val DBP_RANGE_PLUS_MINUS = 4
    }

    @Volatile private var module: Module? = null

    private val modelFile: File get() = File(context.filesDir, LOCAL_REL_PATH)

    private fun syncModelFileFromAssets() {
        val legacyFlat = File(context.filesDir, "bp_model.pte")
        if (legacyFlat.exists()) {
            runCatching { legacyFlat.delete() }
        }

        val pkgUpdateTime = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0),
            ).lastUpdateTime
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cached = prefs.getLong(KEY_PKG_UPDATE_TIME, -1L)
        val dst = modelFile
        if (dst.exists() && pkgUpdateTime == cached) return

        module = null
        dst.parentFile?.mkdirs()
        context.assets.open(ASSET_PATH).use { input ->
            dst.outputStream().use { output -> input.copyTo(output) }
        }
        prefs.edit().putLong(KEY_PKG_UPDATE_TIME, pkgUpdateTime).apply()
        Log.i(TAG, "Synced BP model asset -> ${dst.absolutePath}")
    }

    private fun ensureLoaded(): Module {
        synchronized(this) {
            syncModelFileFromAssets()
            module?.let { return it }

            val dst = modelFile
            val loaded = Module.load(dst.absolutePath)
            module = loaded
            Log.i(TAG, "Loaded ExecuTorch BP model from ${dst.absolutePath}")
            return loaded
        }
    }

    fun predictFromWaveform(windowInt32: IntArray): BpPrediction? {
        if (windowInt32.size != L) return null

        val ppg0 = FloatArray(L) { windowInt32[it].toFloat() }
        zNormalizeInPlace(ppg0)
        val ppg1 = gradient1d(ppg0)
        val ppg2 = gradient1d(ppg1)
        zNormalizeInPlace(ppg0)
        zNormalizeInPlace(ppg1)
        zNormalizeInPlace(ppg2)

        val spec0 = logSpectrogramTorchLike(ppg0)
        val spec1 = logSpectrogramTorchLike(ppg1)
        val spec2 = logSpectrogramTorchLike(ppg2)

        val m = ensureLoaded()
        val t = spec0.size / F_BINS
        if (t <= 0) return null

        val inPpg = Tensor.fromBlob(ppg0, longArrayOf(1, 1, L.toLong()))
        val inPpg1 = Tensor.fromBlob(ppg1, longArrayOf(1, 1, L.toLong()))
        val inPpg2 = Tensor.fromBlob(ppg2, longArrayOf(1, 1, L.toLong()))
        val inSpec0 = Tensor.fromBlob(spec0, longArrayOf(1, t.toLong(), F_BINS.toLong()))
        val inSpec1 = Tensor.fromBlob(spec1, longArrayOf(1, t.toLong(), F_BINS.toLong()))
        val inSpec2 = Tensor.fromBlob(spec2, longArrayOf(1, t.toLong(), F_BINS.toLong()))

        return try {
            val out = m.forward(
                EValue.from(inPpg),
                EValue.from(inPpg1),
                EValue.from(inPpg2),
                EValue.from(inSpec0),
                EValue.from(inSpec1),
                EValue.from(inSpec2),
            )
            val y = out[0].toTensor().dataAsFloatArray
            if (y.size < 2) return null
            BpPrediction(sbp = y[0], dbp = y[1])
        } catch (e: Exception) {
            Log.e(TAG, "BP inference failed: ${e.message}", e)
            null
        }
    }

    private fun gradient1d(x: FloatArray): FloatArray {
        val n = x.size
        val out = FloatArray(n)
        if (n == 0) return out
        if (n == 1) {
            out[0] = 0f
            return out
        }
        out[0] = x[1] - x[0]
        for (i in 1 until n - 1) {
            out[i] = 0.5f * (x[i + 1] - x[i - 1])
        }
        out[n - 1] = x[n - 1] - x[n - 2]
        return out
    }

    private fun zNormalizeInPlace(x: FloatArray) {
        if (x.isEmpty()) return
        var mean = 0.0
        for (v in x) mean += v.toDouble()
        mean /= x.size.toDouble()
        var varSum = 0.0
        for (v in x) {
            val d = v.toDouble() - mean
            varSum += d * d
        }
        val std = sqrt(max(1e-12, varSum / x.size.toDouble()))
        for (i in x.indices) x[i] = ((x[i].toDouble() - mean) / std).toFloat()
    }

    private fun logSpectrogramTorchLike(x: FloatArray): FloatArray {
        val padded = if (CENTER) reflectPadTorch(x, PAD) else x
        val t = 1 + ((padded.size - WIN) / HOP).coerceAtLeast(0)
        val out = FloatArray(t * F_BINS)
        val fft = FloatFFT_1D(N_FFT.toLong())
        val buf = FloatArray(N_FFT * 2)
        for (ti in 0 until t) {
            val start = ti * HOP
            for (i in 0 until N_FFT) {
                val v = padded[start + i]
                buf[2 * i] = v
                buf[2 * i + 1] = 0f
            }
            fft.complexForward(buf)
            val rowOff = ti * F_BINS
            for (k in 0 until F_BINS) {
                val re = buf[2 * k]
                val im = buf[2 * k + 1]
                val mag = sqrt((re * re + im * im).toDouble()).toFloat()
                out[rowOff + k] = ln(1.0 + mag.toDouble()).toFloat()
            }
        }
        return out
    }

    private fun reflectPadTorch(x: FloatArray, pad: Int): FloatArray {
        if (pad <= 0) return x
        val n = x.size
        if (n < 2) return x
        val p = min(pad, n - 1)
        val out = FloatArray(n + 2 * pad)
        for (i in 0 until pad) {
            val idx = 1 + (i % p)
            out[pad - 1 - i] = x[idx]
        }
        for (i in 0 until n) out[pad + i] = x[i]
        for (i in 0 until pad) {
            val idx = (n - 2) - (i % p)
            out[pad + n + i] = x[idx]
        }
        return out
    }
}
