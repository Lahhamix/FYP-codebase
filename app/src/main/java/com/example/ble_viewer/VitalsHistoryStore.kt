package com.example.ble_viewer

import android.content.Context
import java.io.File
import java.util.Locale

/**
 * Lightweight on-device history for vitals charts.
 *
 * Stored as CSV lines in internal storage:
 *   epochMs,type,value
 * where type is "hr" or "spo2".
 */
object VitalsHistoryStore {
    private const val FILE_NAME = "vitals_history.csv"
    private const val MAX_LINES = 60_000 // safety cap (~many hours depending on sampling)

    data class Sample(
        val epochMs: Long,
        val type: Type,
        val value: Double
    )

    enum class Type { HR, SPO2 }

    fun appendHeartRate(context: Context, bpm: Int, epochMs: Long = System.currentTimeMillis()) {
        append(context, epochMs, Type.HR, bpm.toDouble())
    }

    fun appendSpo2(context: Context, spo2: Double, epochMs: Long = System.currentTimeMillis()) {
        append(context, epochMs, Type.SPO2, spo2)
    }

    fun readSince(context: Context, sinceEpochMs: Long): List<Sample> {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return emptyList()

        return try {
            file.useLines { lines ->
                lines.mapNotNull { parseLine(it) }
                    .filter { it.epochMs >= sinceEpochMs }
                    .toList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun latestOfType(context: Context, type: Type): Sample? {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return null

        return try {
            // Read from end-ish: file is small enough; keep simple/robust.
            file.useLines { lines ->
                lines.mapNotNull { parseLine(it) }
                    .filter { it.type == type }
                    .lastOrNull()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun append(context: Context, epochMs: Long, type: Type, value: Double) {
        val file = File(context.filesDir, FILE_NAME)
        try {
            file.appendText("${epochMs},${typeToken(type)},${formatValue(value)}\n")
            maybeTrim(file)
        } catch (_: Exception) {
            // Best effort — history should never break the app.
        }
    }

    private fun maybeTrim(file: File) {
        try {
            val lines = file.readLines()
            if (lines.size <= MAX_LINES) return
            val trimmed = lines.takeLast(MAX_LINES)
            file.writeText(trimmed.joinToString(separator = "\n", postfix = "\n"))
        } catch (_: Exception) {
        }
    }

    private fun parseLine(line: String): Sample? {
        val parts = line.split(',')
        if (parts.size < 3) return null
        val ts = parts[0].trim().toLongOrNull() ?: return null
        val type = when (parts[1].trim().lowercase(Locale.US)) {
            "hr" -> Type.HR
            "spo2" -> Type.SPO2
            else -> return null
        }
        val v = parts[2].trim().toDoubleOrNull() ?: return null
        return Sample(ts, type, v)
    }

    private fun typeToken(type: Type): String = when (type) {
        Type.HR -> "hr"
        Type.SPO2 -> "spo2"
    }

    private fun formatValue(value: Double): String {
        return if (value % 1.0 == 0.0) {
            value.toLong().toString()
        } else {
            String.format(Locale.US, "%.2f", value)
        }
    }
}

