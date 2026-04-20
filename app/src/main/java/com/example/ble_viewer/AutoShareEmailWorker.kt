package com.example.ble_viewer

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
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

        val alertMessage = inputData.getString("auto_share_alert_message").orEmpty().ifBlank {
            "A health alert was detected by SoleMate."
        }

        val backendBaseUrl = BuildConfig.EMAIL_BACKEND_BASE_URL.trim()
            .ifBlank { "http://10.0.2.2:3000" }
            .trimEnd('/')
        val sendUrl = "$backendBaseUrl/send-email"

        val plainText = """
            SoleMate Health Alert

            Alert: $alertMessage
            Time: ${java.util.Date()}

            This email was sent because Auto-share is enabled and an alert was detected.
        """.trimIndent()

        val html = "<pre>${plainText.replace("&", "&amp;").replace("<", "&lt;")}</pre>"

        var anyFailed = false
        for (email in recipients) {
            val success = runCatching {
                val payload = JSONObject().apply {
                    put("to", email)
                    put("subject", "SoleMate Health Monitoring Alert")
                    put("text", plainText)
                    put("html", html)
                }

                val connection = (URL(sendUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/json")
                    connectTimeout = 15000
                    readTimeout = 15000
                }

                OutputStreamWriter(connection.outputStream).use { writer ->
                    writer.write(payload.toString())
                    writer.flush()
                }

                val code = connection.responseCode
                connection.disconnect()
                code in 200..202
            }.getOrElse { false }

            if (!success) anyFailed = true
        }

        return if (anyFailed) Result.retry() else Result.success()
    }
}
