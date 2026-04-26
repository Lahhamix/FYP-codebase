package com.example.ble_viewer

import android.content.Context
import android.util.Log
import org.jtransforms.fft.FloatFFT_1D
import org.pytorch.executorch.EValue
import org.pytorch.executorch.Module
import org.pytorch.executorch.Tensor
import java.io.File
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class BpPrediction(
    val sbp: Float,
    val dbp: Float
)

/**
 * ExecuTorch BP model runner (Option A: spectrogram computed on-device; model consumes precomputed specs).
 *
 * Model input contract (must match the exported .pte):
 * - ppg, ppg1, ppg2: (1, 1, 625) float32
 * - spec0, spec1, spec2: (1, T, 65) float32, where T depends on STFT settings (center=True).
 *
 * Output:
 * - (1, 2) float32 => [sbp, dbp]
 */
class BpModelRunner(private val context: Context) {
    companion object {
        private const val TAG = "BP_MODEL"

        // Asset location for the ExecuTorch program
        private const val ASSET_PATH = "models/bp_model.pte"

        // Waveform contract (from firmware)
        private const val L = 625

        // Must match training / model definition (model_architecture.py)
        private const val N_FFT = 128
        private const val HOP = 32
        private const val WIN = 128
        private const val F_BINS = (N_FFT / 2) + 1 // 65
        private const val CENTER = true
        private const val PAD = N_FFT / 2 // 64

        // BP Range UI uncertainty (requested)
        const val SBP_RANGE_PLUS_MINUS = 6
        const val DBP_RANGE_PLUS_MINUS = 4
    }

    @Volatile private var module: Module? = null

    private fun ensureLoaded(): Module {
        val existing = module
        if (existing != null) return existing

        synchronized(this) {
            val again = module
            if (again != null) return again

            val dst = File(context.filesDir, "bp_model.pte")
            if (!dst.exists()) {
                dst.parentFile?.mkdirs()
                context.assets.open(ASSET_PATH).use { input ->
                    dst.outputStream().use { output -> input.copyTo(output) }
                }
            }

            val loaded = Module.load(dst.absolutePath)
            module = loaded
            Log.i(TAG, "Loaded ExecuTorch BP model from ${dst.absolutePath}")
            return loaded
        }
    }

    fun predictFromWaveform(windowInt32: IntArray): BpPrediction? {
        if (windowInt32.size != L) return null

        // Convert to float
        val ppg = FloatArray(L) { windowInt32[it].toFloat() }
        val ppg1 = firstDerivative(ppg)
        val ppg2 = firstDerivative(ppg1)

        // Normalize each channel (robust default; adjust if your training used different scaling)
        zNormalizeInPlace(ppg)
        zNormalizeInPlace(ppg1)
        zNormalizeInPlace(ppg2)

        val spec0 = logSpectrogram(ppg)
        val spec1 = logSpectrogram(ppg1)
        val spec2 = logSpectrogram(ppg2)

        val m = ensureLoaded()

        val t = spec0.size / F_BINS
        if (t <= 0) return null

        val inPpg = Tensor.fromBlob(ppg, longArrayOf(1, 1, L.toLong()))
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

            // NOTE: If your model outputs normalized BP, de-normalize here (mean/std) to mmHg.
            BpPrediction(sbp = y[0], dbp = y[1])
        } catch (e: Exception) {
            Log.e(TAG, "BP inference failed: ${e.message}", e)
            null
        }
    }

    private fun firstDerivative(x: FloatArray): FloatArray {
        val out = FloatArray(x.size)
        if (x.isEmpty()) return out
        out[0] = 0f
        for (i in 1 until x.size) out[i] = x[i] - x[i - 1]
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

    /**
     * torch.stft defaults we mimic:
     * - center=True => reflect pad by N_FFT/2 on both ends
     * - onesided=True => keep 0..N_FFT/2 bins
     * - window=None => rectangular window (no Hann)
     * - return_complex=True => magnitude, then log1p
     *
     * Returns flattened float array of shape (T, F_BINS) in row-major, matching (B, T, F) for GRU.
     */
    private fun logSpectrogram(x: FloatArray): FloatArray {
        val padded = if (CENTER) reflectPad(x, PAD) else x
        val t = 1 + ((padded.size - WIN) / HOP).coerceAtLeast(0)
        val out = FloatArray(t * F_BINS)

        val fft = FloatFFT_1D(N_FFT.toLong())
        val buf = FloatArray(N_FFT * 2) // complex interleaved

        for (ti in 0 until t) {
            val start = ti * HOP
            // real samples into complex buffer
            for (i in 0 until N_FFT) {
                val v = padded[start + i]
                buf[2 * i] = v
                buf[2 * i + 1] = 0f
            }
            fft.complexForward(buf)

            // magnitude for bins 0..N_FFT/2
            val rowOff = ti * F_BINS
            for (k in 0 until F_BINS) {
                val re = buf[2 * k]
                val im = buf[2 * k + 1]
                val mag = sqrt((re * re + im * im).toDouble()).toFloat()
                out[rowOff + k] = ln(1.0 + mag.toDouble()).toFloat() // log1p
            }
        }
        return out
    }

    private fun reflectPad(x: FloatArray, pad: Int): FloatArray {
        if (pad <= 0) return x
        val n = x.size
        val out = FloatArray(n + 2 * pad)
        // left reflect
        for (i in 0 until pad) {
            val src = min(n - 1, pad - i)
            out[i] = x[src]
        }
        // center
        for (i in 0 until n) out[pad + i] = x[i]
        // right reflect
        for (i in 0 until pad) {
            val src = max(0, n - 2 - i)
            out[pad + n + i] = x[src]
        }
        return out
    }
}

