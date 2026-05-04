package com.example.ble_viewer

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager

/**
 * App-wide PPG waveform window assembler.
 *
 * This runs from the BLE callback path, not from an Activity receiver, so BP inference
 * continues while the user is on Home, analytics, Settings, or any other screen.
 */
object PpgWaveProcessor {
    private const val TAG = "PPG_WAVE_PIPE"
    private const val PPG_WAVE_WINDOW_BYTES = 625 * 4

    private var rxFrameId: Int = -1
    private var rxTotalChunks: Int = 0
    private var rxReceived: BooleanArray = BooleanArray(0)
    private var rxChunks: Array<ByteArray> = emptyArray()
    private var lastCompletedFrameId: Int = -1

    @Volatile
    private var bpRunner: BpModelRunner? = null

    @Synchronized
    fun processEncryptedChunk(context: Context, encryptedBytes: ByteArray) {
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
        if (frameId == lastCompletedFrameId) return

        if (rxFrameId != frameId || rxTotalChunks != totalChunks) {
            rxFrameId = frameId
            rxTotalChunks = totalChunks
            rxReceived = BooleanArray(totalChunks)
            rxChunks = Array(totalChunks) { ByteArray(0) }
            Log.i(TAG, "PPG_WAVE frame start frame=$frameId chunks=$totalChunks chunk=$chunkId dataLen=$dataLen")
        }
        if (chunkId >= totalChunks) return
        if (!rxReceived[chunkId]) {
            rxReceived[chunkId] = true
            rxChunks[chunkId] = plain.copyOfRange(8, 8 + dataLen)
        }

        if (rxReceived.count { it } != totalChunks) return

        val assembled = ByteArray(rxChunks.sumOf { it.size })
        var writeOffset = 0
        for (i in 0 until totalChunks) {
            val chunk = rxChunks[i]
            System.arraycopy(chunk, 0, assembled, writeOffset, chunk.size)
            writeOffset += chunk.size
        }

        if (assembled.size != PPG_WAVE_WINDOW_BYTES) {
            Log.w(TAG, "PPG_WAVE assembled ${assembled.size} bytes (expected $PPG_WAVE_WINDOW_BYTES); dropping frame $frameId")
            resetFrame()
            return
        }

        Log.i(
            TAG,
            "PPG_WAVE window ok frame=$frameId bytes=${assembled.size} " +
                "s0=${readInt32LE(assembled, 0)} s1=${readInt32LE(assembled, 4)} s2=${readInt32LE(assembled, 8)}",
        )

        val window = IntArray(PPG_WAVE_WINDOW_BYTES / 4)
        var j = 0
        var off = 0
        while (off + 4 <= assembled.size && j < window.size) {
            window[j++] = readInt32LE(assembled, off)
            off += 4
        }

        lastCompletedFrameId = frameId
        resetFrame()
        runBpInference(context.applicationContext, window)
    }

    @Synchronized
    fun reset() {
        resetFrame()
        lastCompletedFrameId = -1
    }

    private fun resetFrame() {
        rxFrameId = -1
        rxTotalChunks = 0
        rxReceived = BooleanArray(0)
        rxChunks = emptyArray()
    }

    private fun runBpInference(appContext: Context, window: IntArray) {
        Thread {
            val pred = getBpRunner(appContext).predictFromWaveform(window) ?: return@Thread
            BpPredictionStore.save(appContext, pred.sbp, pred.dbp)

            val intent = Intent(MainActivity.ACTION_BP_PREDICTION).apply {
                putExtra(MainActivity.EXTRA_SBP, pred.sbp)
                putExtra(MainActivity.EXTRA_DBP, pred.dbp)
            }
            LocalBroadcastManager.getInstance(appContext).sendBroadcast(intent)
        }.start()
    }

    private fun getBpRunner(appContext: Context): BpModelRunner {
        bpRunner?.let { return it }
        synchronized(this) {
            bpRunner?.let { return it }
            return BpModelRunner(appContext).also { bpRunner = it }
        }
    }

    private fun readInt32LE(buf: ByteArray, off: Int): Int {
        if (off + 4 > buf.size) return 0
        return (buf[off].toInt() and 0xFF) or
            ((buf[off + 1].toInt() and 0xFF) shl 8) or
            ((buf[off + 2].toInt() and 0xFF) shl 16) or
            ((buf[off + 3].toInt() and 0xFF) shl 24)
    }
}
