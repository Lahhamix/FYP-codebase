package com.example.ble_viewer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.util.Locale

class AnkleCuffAnalyticsActivity : AppCompatActivity() {
    companion object {
        private const val PREFS_NAME = "SolematePrefs"
        private const val EDEMA_CHAR_UUID = "9a8b0006-6d5e-4c10-b6d9-1f25c09d9e00"
    }

    private enum class TrendWindow(val ms: Long, val label: String) {
        H24(24L * 60L * 60_000L, "Last 24 hours"),
        H8(8L * 60L * 60_000L, "Last 8 hours"),
        H4(4L * 60L * 60_000L, "Last 4 hours")
    }

    private lateinit var bannerText: TextView
    private lateinit var toolbarUsername: TextView
    private lateinit var toolbarProfileImage: ImageView
    private lateinit var gauge: SwellingGaugeView
    private lateinit var severityLabel: TextView
    private lateinit var severityAdvice: TextView
    private lateinit var trendView: SwellingTrendView
    private lateinit var trendBadge: TextView
    private lateinit var trendPeriod: TextView
    private lateinit var trendUpdated: TextView
    private var trendWindow: TrendWindow = TrendWindow.H24

    private val sensorDataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != MainActivity.ACTION_SENSOR_DATA) return
            val uuidString = intent.getStringExtra(MainActivity.EXTRA_UUID_STRING) ?: return
            if (uuidString != EDEMA_CHAR_UUID) return

            val encryptedBytes = intent.getByteArrayExtra(MainActivity.EXTRA_DECRYPTED_DATA) ?: return
            val decrypted = AESCrypto.decryptFlex(encryptedBytes).trim()
            if (decrypted.startsWith("DECRYPT_ERROR")) return

            val label = decrypted.split(",").firstOrNull()?.trim().orEmpty()
            if (label.isBlank()) return

            runOnUiThread {
                SwellingHistoryStore.appendLabel(this@AnkleCuffAnalyticsActivity, label)
                updateSwellingUi(label)
                refreshTrend()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ankle_cuff_analytics)

        bannerText = findViewById(R.id.ankle_info_banner_text)
        toolbarUsername = findViewById(R.id.toolbar_username)
        toolbarProfileImage = findViewById(R.id.toolbar_profile_image)
        gauge = findViewById(R.id.ankle_gauge)
        severityLabel = findViewById(R.id.ankle_severity_label)
        severityAdvice = findViewById(R.id.ankle_severity_advice)
        trendView = findViewById(R.id.ankle_trend_chart)
        trendBadge = findViewById(R.id.ankle_trend_badge)
        trendPeriod = findViewById(R.id.ankle_trend_period)
        trendUpdated = findViewById(R.id.ankle_trend_updated)

        bindToolbar()
        trendPeriod.setOnClickListener { showTrendWindowPopup(it) }
        trendPeriod.text = trendWindow.label

        // Back button in the included toolbar — finish with fade
        val back = findViewById<ImageView>(R.id.toolbar_back)
        back?.setOnClickListener {
            finish()
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        }

        // Toolbar navigation: Home and Settings — use fade transitions
        val navHome = findViewById<LinearLayout>(R.id.nav_home)
        navHome?.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        }

        val navSettings = findViewById<LinearLayout>(R.id.nav_settings)
        navSettings?.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        }

        val latest = SwellingHistoryStore.latest(this)
        updateSwellingUi(latest?.label ?: "calibrating")
        refreshTrend()
    }

    override fun onResume() {
        super.onResume()
        LocalBroadcastManager.getInstance(this).registerReceiver(
            sensorDataReceiver,
            IntentFilter(MainActivity.ACTION_SENSOR_DATA)
        )
        updateToolbarUserFromPrefs()
        refreshTrend()
    }

    override fun onPause() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(sensorDataReceiver)
        super.onPause()
    }

    override fun onBackPressed() {
        super.onBackPressed()
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
    }

    private fun updateSwellingUi(rawLabel: String) {
        val label = SwellingHistoryStore.normalizeLabel(rawLabel) ?: "calibrating"
        gauge.setSwellingLabel(label)

        severityLabel.text = when (label) {
            "none" -> "NO SWELLING DETECTED"
            "mild" -> "MILD LEVEL DETECTED"
            "moderate" -> "MODERATE LEVEL DETECTED"
            "severe" -> "SEVERE LEVEL DETECTED"
            else -> "CALIBRATING"
        }

        severityLabel.backgroundTintList = ColorStateList.valueOf(severityColor(label))
        severityAdvice.text = when (label) {
            "none" -> "No swelling signs detected. Keep monitoring regularly."
            "mild" -> "Mild swelling signs detected. Rest and monitor for changes."
            "moderate" -> "Moderate swelling detected. Consider reducing activity and monitoring closely."
            "severe" -> "Immediate clinical attention is advised for severe symptoms."
            else -> "Collecting baseline data from the ankle cuff sensors."
        }

        bannerText.text = when (label) {
            "none" -> "Your ankle swelling is currently within the Normal Range. Measurements update as flex sensor data arrives."
            "mild" -> "Mild swelling signs are currently detected. Keep monitoring and rest if symptoms persist."
            "moderate" -> "Moderate swelling signs are currently detected. Monitor closely and consider medical advice."
            "severe" -> "Severe ankle swelling is currently detected. Clinical attention is recommended."
            else -> "Your ankle cuff is calibrating. Keep the wearable connected while baseline readings are collected."
        }
    }

    private fun refreshTrend() {
        val since = System.currentTimeMillis() - trendWindow.ms
        val samples = SwellingHistoryStore.readSince(this, since)
        trendView.setSamples(samples, trendWindow.ms)

        val latest = samples.lastOrNull() ?: SwellingHistoryStore.latest(this)
        val latestLabel = latest?.label ?: "calibrating"
        trendBadge.text = displayLabel(latestLabel)
        trendBadge.setTextColor(severityColor(latestLabel))
        trendBadge.backgroundTintList = ColorStateList.valueOf(
            severityColor(latestLabel).withAlpha(28)
        )

        trendUpdated.text = if (latest == null) {
            "Waiting for swelling data"
        } else {
            val age = formatAge(System.currentTimeMillis() - latest.epochMs)
            if (age == "just now") "Updated just now" else "Updated $age ago"
        }
    }

    private fun bindToolbar() {
        updateToolbarUserFromPrefs()

        findViewById<View>(R.id.profile_card).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }

        toolbarUsername.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }

    private fun updateToolbarUserFromPrefs() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val username = prefs.getString("username", getString(R.string.username_placeholder))
            .orEmpty()
            .ifBlank { getString(R.string.username_placeholder) }
        toolbarUsername.text = username

        val imagePath = prefs.getString("profile_image_path", null)
        if (!imagePath.isNullOrBlank()) {
            val bitmap = BitmapFactory.decodeFile(imagePath)
            if (bitmap != null) {
                toolbarProfileImage.setImageBitmap(bitmap)
            } else {
                toolbarProfileImage.setImageResource(R.drawable.profile)
            }
        } else {
            toolbarProfileImage.setImageResource(R.drawable.profile)
        }
    }

    private fun showTrendWindowPopup(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 24, 0, TrendWindow.H24.label)
        popup.menu.add(0, 8, 1, TrendWindow.H8.label)
        popup.menu.add(0, 4, 2, TrendWindow.H4.label)
        popup.setOnMenuItemClickListener { item: MenuItem ->
            trendWindow = when (item.itemId) {
                8 -> TrendWindow.H8
                4 -> TrendWindow.H4
                else -> TrendWindow.H24
            }
            trendPeriod.text = trendWindow.label
            refreshTrend()
            true
        }
        popup.show()
    }

    private fun displayLabel(label: String): String {
        return when (label) {
            "none" -> "Normal"
            "mild" -> "Mild"
            "moderate" -> "Moderate"
            "severe" -> "Severe"
            else -> "Calibrating"
        }
    }

    private fun severityColor(label: String): Int {
        return when (label.lowercase(Locale.US)) {
            "mild" -> 0xFFFFC107.toInt()
            "moderate" -> 0xFFF58433.toInt()
            "severe" -> 0xFFE53935.toInt()
            "none" -> 0xFF2D7EEA.toInt()
            else -> 0xFF6B7480.toInt()
        }
    }

    private fun Int.withAlpha(alpha: Int): Int {
        return (alpha.coerceIn(0, 255) shl 24) or (this and 0x00FFFFFF)
    }

    private fun formatAge(ageMs: Long): String {
        val minutes = ageMs / 60_000L
        val hours = ageMs / 3_600_000L
        return when {
            ageMs < 60_000L -> "just now"
            minutes < 60L -> "${minutes}m"
            hours < 24L -> "${hours}h"
            else -> "${hours / 24L}d"
        }
    }
}
