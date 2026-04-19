package com.example.ble_viewer

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

object AutoShareScheduler {

    private const val UNIQUE_PERIODIC_WORK_NAME = "auto_share_sendgrid_email"
    private const val KEY_ALERT_MESSAGE = "auto_share_alert_message"
    private const val ALERT_WORK_TAG = "auto_share_alert_email"
    private const val KEY_AUTO_SHARE_EMAIL = "auto_share_email"
    private const val KEY_AUTO_SHARE_VERIFIED_EMAIL = "auto_share_verified_email"
    private const val KEY_AUTO_SHARE_VERIFIED_EMAILS = "auto_share_verified_emails"

    fun reschedule(context: Context) {
        // Auto-share is alert-driven now; ensure any old periodic work is removed.
        cancel(context)
    }

    fun enqueueAlert(context: Context, alertMessage: String) {
        val prefs = context.getSharedPreferences("SolematePrefs", Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean("auto_share_enabled", false)
        if (!isEnabled) return

        val recipients = prefs.getStringSet(KEY_AUTO_SHARE_VERIFIED_EMAILS, emptySet())
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.toSet()
            ?: emptySet()

        if (recipients.isEmpty()) {
            val configuredEmail = prefs.getString(KEY_AUTO_SHARE_EMAIL, "").orEmpty().trim()
            val verifiedEmail = prefs.getString(KEY_AUTO_SHARE_VERIFIED_EMAIL, "").orEmpty().trim()
            if (configuredEmail.isBlank() || !configuredEmail.equals(verifiedEmail, ignoreCase = true)) return
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val inputData = Data.Builder()
            .putString(KEY_ALERT_MESSAGE, alertMessage)
            .build()

        val request = OneTimeWorkRequestBuilder<AutoShareEmailWorker>()
            .setInputData(inputData)
            .setConstraints(constraints)
            .addTag(ALERT_WORK_TAG)
            .build()

        WorkManager.getInstance(context).enqueue(request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_PERIODIC_WORK_NAME)
        WorkManager.getInstance(context).cancelAllWorkByTag(ALERT_WORK_TAG)
    }
}
