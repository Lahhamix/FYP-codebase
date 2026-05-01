package com.example.ble_viewer

import android.content.Context

object BpPredictionStore {
    private const val PREFS_NAME = "SolematePrefs"
    private const val KEY_SBP = "latest_bp_sbp"
    private const val KEY_DBP = "latest_bp_dbp"
    private const val KEY_UPDATED_MS = "latest_bp_updated_ms"

    data class Prediction(
        val sbp: Float,
        val dbp: Float,
        val updatedMs: Long
    )

    fun save(context: Context, sbp: Float, dbp: Float, updatedMs: Long = System.currentTimeMillis()) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_SBP, sbp)
            .putFloat(KEY_DBP, dbp)
            .putLong(KEY_UPDATED_MS, updatedMs)
            .apply()
    }

    fun latest(context: Context): Prediction? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_SBP) || !prefs.contains(KEY_DBP)) return null
        val sbp = prefs.getFloat(KEY_SBP, Float.NaN)
        val dbp = prefs.getFloat(KEY_DBP, Float.NaN)
        if (!sbp.isFinite() || !dbp.isFinite()) return null
        return Prediction(sbp, dbp, prefs.getLong(KEY_UPDATED_MS, 0L))
    }
}
