package com.example.ble_viewer

import android.content.Context
import java.io.File
import java.util.Locale

object SwellingHistoryStore {
    private const val FILE_NAME = "swelling_history.csv"
    private const val MAX_LINES = 5_000
    private const val MIN_SAME_LABEL_INTERVAL_MS = 60_000L

    data class Sample(
        val epochMs: Long,
        val label: String
    )

    fun appendLabel(context: Context, rawLabel: String, epochMs: Long = System.currentTimeMillis()) {
        val label = normalizeLabel(rawLabel) ?: return
        if (label == "calibrating") return

        val file = File(context.filesDir, FILE_NAME)
        try {
            val latest = latest(context)
            if (latest != null &&
                latest.label == label &&
                epochMs - latest.epochMs < MIN_SAME_LABEL_INTERVAL_MS
            ) {
                return
            }

            file.appendText("$epochMs,$label\n")
            maybeTrim(file)
        } catch (_: Exception) {
        }
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

    fun latest(context: Context): Sample? {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return null
        return try {
            file.useLines { lines -> lines.mapNotNull { parseLine(it) }.lastOrNull() }
        } catch (_: Exception) {
            null
        }
    }

    fun severityScore(label: String): Int {
        return when (normalizeLabel(label)) {
            "mild" -> 1
            "moderate" -> 2
            "severe" -> 3
            else -> 0
        }
    }

    fun normalizeLabel(rawLabel: String): String? {
        return when (rawLabel.trim().lowercase(Locale.US)) {
            "none", "normal", "no swelling", "no_swelling" -> "none"
            "mild" -> "mild"
            "moderate" -> "moderate"
            "severe" -> "severe"
            "calibrating" -> "calibrating"
            else -> null
        }
    }

    private fun parseLine(line: String): Sample? {
        val parts = line.split(',')
        if (parts.size < 2) return null
        val ts = parts[0].trim().toLongOrNull() ?: return null
        val label = normalizeLabel(parts[1]) ?: return null
        return Sample(ts, label)
    }

    private fun maybeTrim(file: File) {
        try {
            val lines = file.readLines()
            if (lines.size <= MAX_LINES) return
            file.writeText(lines.takeLast(MAX_LINES).joinToString(separator = "\n", postfix = "\n"))
        } catch (_: Exception) {
        }
    }
}
