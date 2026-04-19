package com.example.ble_viewer

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class AutoShareEmailWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val KEY_AUTO_SHARE_EMAIL = "auto_share_email"
        private const val KEY_AUTO_SHARE_VERIFIED_EMAIL = "auto_share_verified_email"
        private const val KEY_AUTO_SHARE_VERIFIED_EMAILS = "auto_share_verified_emails"
    }

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("SolematePrefs", Context.MODE_PRIVATE)
        val recipients = prefs.getStringSet(KEY_AUTO_SHARE_VERIFIED_EMAILS, emptySet())
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.toMutableSet()
            ?: mutableSetOf()

        if (recipients.isEmpty()) {
            val legacy = prefs.getString(KEY_AUTO_SHARE_VERIFIED_EMAIL, "").orEmpty().trim()
            if (legacy.isNotBlank()) recipients.add(legacy)
            val configured = prefs.getString(KEY_AUTO_SHARE_EMAIL, "").orEmpty().trim()
            if (configured.isNotBlank()) recipients.add(configured)
        }

        if (recipients.isEmpty()) return Result.success()

        val apiKey = BuildConfig.SENDGRID_API_KEY.trim()
        val fromEmail = BuildConfig.SENDGRID_FROM_EMAIL.trim()
        val alertMessage = inputData.getString("auto_share_alert_message").orEmpty().ifBlank {
            "A health alert was detected by SoleMate."
        }

        if (apiKey.isBlank() || fromEmail.isBlank()) {
            return Result.retry()
        }

        return runCatching {
            val plainText = """
                SoleMate Health Alert

                Alert: $alertMessage
                Time: ${java.util.Date()}

                This email was sent because Auto-share is enabled and an alert was detected.
            """.trimIndent()

            val payload = JSONObject().apply {
                put("personalizations", JSONArray().put(
                    JSONObject().put(
                        "to",
                        JSONArray().apply {
                            recipients.forEach { email ->
                                put(JSONObject().put("email", email))
                            }
                        }
                    )
                ))
                put("from", JSONObject().put("email", fromEmail).put("name", "SoleMate"))
                put("subject", "SoleMate Health Monitoring Alert")
                put("content", JSONArray().put(
                    JSONObject()
                        .put("type", "text/plain")
                        .put("value", plainText)
                ))
            }

            val baseUrl = BuildConfig.SENDGRID_API_BASE_URL.trim().ifBlank { "https://api.sendgrid.com" }.trimEnd('/')
            val sendUrl = "$baseUrl/v3/mail/send"

            val connection = (URL(sendUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 15000
                readTimeout = 15000
            }

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(payload.toString())
                writer.flush()
            }

            val code = connection.responseCode
            connection.disconnect()

            when (code) {
                200, 201, 202 -> Result.success()
                in 500..599 -> Result.retry()
                else -> Result.failure()
            }
        }.getOrElse {
            Result.retry()
        }
    }
}
