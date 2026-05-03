package com.example.ble_viewer

import android.content.Context
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

object ReadingsUploadService {
    private const val TAG = "ReadingsUpload"
    
    data class HealthReading(
        val heartRate: Int? = null,
        val spo2: Double? = null,
        val bpSystolic: Int? = null,
        val bpDiastolic: Int? = null,
        val swellingValue: String? = null,
        val stepCount: Int? = null,
        val motionStatus: String? = null,
        val recordedAt: String? = null  // ISO 8601 timestamp
    )
    
    /**
     * Upload a single health reading to the backend.
     * Call this whenever new BLE data is received.
     */
    fun uploadReading(context: Context, reading: HealthReading) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val token = SessionManager.getAccessToken(context) ?: run {
                    android.util.Log.w(TAG, "No access token available")
                    return@launch
                }

                val url = URL("${ApiClient.baseUrl(context)}/readings")
                val connection = url.openConnection() as HttpURLConnection
                
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.doOutput = true
                
                // Build JSON payload - only include non-null fields
                val jsonData = JSONObject().apply {
                    reading.heartRate?.let { put("heart_rate", it) }
                    reading.spo2?.let { put("spo2", it) }
                    reading.bpSystolic?.let { put("bp_systolic", it) }
                    reading.bpDiastolic?.let { put("bp_diastolic", it) }
                    reading.swellingValue?.let { put("swelling_value", it) }
                    reading.stepCount?.let { put("step_count", it) }
                    reading.motionStatus?.let { put("motion_status", it) }
                    reading.recordedAt?.let { put("recorded_at", it) }
                }
                
                // Send request
                connection.outputStream.bufferedWriter().use { writer ->
                    writer.write(jsonData.toString())
                }
                
                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_CREATED) {
                    android.util.Log.d(TAG, "Reading uploaded successfully: ${reading.heartRate}bpm / ${reading.spo2}%")
                } else {
                    val errorStream = connection.errorStream?.bufferedReader()?.readText() ?: ""
                    android.util.Log.e(TAG, "Upload failed ($responseCode): $errorStream")
                }
                
                connection.disconnect()
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error uploading reading: ${e.message}", e)
            }
        }
    }
    
    /**
     * Upload multiple readings in batch (for backfill/sync).
     */
    fun uploadReadingsBatch(context: Context, readings: List<HealthReading>) {
        readings.forEach { reading ->
            uploadReading(context, reading)
        }
    }
    
    /**
     * Format timestamp to ISO 8601 format expected by backend.
     */
    fun getCurrentTimestamp(): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
    }
}
