package com.example.ble_viewer

import kotlin.math.max
import kotlin.math.min

enum class BpClass {
    HYPOTENSION,
    NORMAL,
    HYPERTENSION
}

object BpUi {
    fun classify(sbp: Float, dbp: Float): BpClass {
        // Simple 3-bucket classification as requested.
        // Adjust thresholds if you want stricter clinical cutoffs.
        return when {
            sbp < 90f || dbp < 60f -> BpClass.HYPOTENSION
            sbp >= 130f || dbp >= 80f -> BpClass.HYPERTENSION
            else -> BpClass.NORMAL
        }
    }

    fun formatRange(sbp: Float, dbp: Float): String {
        val sbpInt = sbp.toInt()
        val dbpInt = dbp.toInt()
        return "${sbpInt}±${BpModelRunner.SBP_RANGE_PLUS_MINUS} / ${dbpInt}±${BpModelRunner.DBP_RANGE_PLUS_MINUS}"
    }

    /** Map class to [0..1] where 0=left(hypo), 0.5=center(normal), 1=right(hyper). */
    fun classToNormalizedPos(c: BpClass): Float = when (c) {
        BpClass.HYPOTENSION -> 0.0f
        BpClass.NORMAL -> 0.5f
        BpClass.HYPERTENSION -> 1.0f
    }

    fun clamp01(x: Float): Float = min(1f, max(0f, x))
}

