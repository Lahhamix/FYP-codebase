package com.example.ble_viewer

import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.view.animation.DecelerateInterpolator
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class PressureMatrixActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_START_CALIBRATION = "start_calibration"
        const val EXTRA_SHOW_FOOT_OVERVIEW = "show_foot_overview"
        private const val PREFS_NAME = "SolematePrefs"
        private const val KEY_PROFILE_IMAGE_PATH = "profile_image_path"

        /** @see PlantarPressureLiveController */
        const val HEATMAP_SNAPSHOT_FILE = PlantarPressureLiveController.HEATMAP_SNAPSHOT_FILE
        const val KEY_HEATMAP_LAST_UPDATED_MS = PlantarPressureLiveController.KEY_HEATMAP_LAST_UPDATED_MS
    }

    private var pressureLive: PlantarPressureLiveController? = null
    private var disconnectDialog: AlertDialog? = null
    private var isFootOverviewMode: Boolean = false

    private val footOverviewDisconnectReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == MainActivity.ACTION_DEVICE_DISCONNECTED) {
                showDisconnectDialog()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isFootOverviewMode = intent.getBooleanExtra(EXTRA_SHOW_FOOT_OVERVIEW, false)

        if (isFootOverviewMode) {
            setContentView(R.layout.activity_foot_overview)
            setupFootOverviewMode()
            return
        }

        setContentView(R.layout.activity_pressure_matrix)

        val heatmapImageView = findViewById<ImageView>(R.id.heatmapImageView)
        val calibrateButton = findViewById<Button>(R.id.calibrateButton)
        val calibrationProgress = findViewById<ProgressBar>(R.id.calibrationProgress)

        pressureLive = PlantarPressureLiveController(
            activity = this,
            heatmapImageView = heatmapImageView,
            calibrateButton = calibrateButton,
            calibrationProgress = calibrationProgress,
            zoneAtaxiaStatus = null,
            zoneMotionStatus = null,
            zoneStepDonut = null,
            trackStepsMotion = false,
            onDeviceDisconnected = { showDisconnectDialog() },
            rotateHeatmapClockwise90ForDisplay = false,
        ).also {
            it.initialize(intent.getBooleanExtra(EXTRA_START_CALIBRATION, false))
        }
    }

    override fun onResume() {
        super.onResume()
        if (isFootOverviewMode) {
            LocalBroadcastManager.getInstance(this).registerReceiver(
                footOverviewDisconnectReceiver,
                IntentFilter(MainActivity.ACTION_DEVICE_DISCONNECTED),
            )
        } else {
            pressureLive?.onResume()
        }
    }

    override fun onPause() {
        super.onPause()
        if (isFootOverviewMode) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(footOverviewDisconnectReceiver)
        } else {
            pressureLive?.onPause()
        }
    }

    override fun onDestroy() {
        pressureLive?.onDestroy()
        pressureLive = null
        super.onDestroy()
    }

    private fun showDisconnectDialog() {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            if (disconnectDialog?.isShowing == true) return@runOnUiThread

            disconnectDialog = AlertDialog.Builder(this)
                .setTitle(getString(R.string.dialog_connection_lost))
                .setMessage(getString(R.string.dialog_wearable_disconnected))
                .setCancelable(false)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    finish()
                }
                .create()

            disconnectDialog?.show()
        }
    }

    private fun setupFootOverviewMode() {
        bindFootOverviewToolbar()
        bindFootOverviewNavigation()
        bindFootOverviewInteractions()
    }

    private fun bindFootOverviewToolbar() {
        val username = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString("username", getString(R.string.username_placeholder))
            .orEmpty()
            .ifBlank { getString(R.string.username_placeholder) }

        findViewById<TextView>(R.id.toolbar_username)?.text = username

        val imagePath = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(KEY_PROFILE_IMAGE_PATH, null)

        val profileImageView = findViewById<ImageView>(R.id.toolbar_profile_image)
        if (!imagePath.isNullOrBlank()) {
            val bitmap = BitmapFactory.decodeFile(imagePath)
            if (bitmap != null) {
                profileImageView?.setImageBitmap(bitmap)
            } else {
                profileImageView?.setImageResource(R.drawable.profile)
            }
        } else {
            profileImageView?.setImageResource(R.drawable.profile)
        }

        findViewById<View>(R.id.profile_card)?.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }

        findViewById<TextView>(R.id.toolbar_username)?.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }

    private fun bindFootOverviewNavigation() {
        val scrollView = findViewById<ScrollView>(R.id.foot_overview_scroll)

        findViewById<LinearLayout>(R.id.nav_home)?.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            })
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        }

        findViewById<LinearLayout>(R.id.nav_settings)?.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }

        findViewById<LinearLayout>(R.id.nav_foot_overview)?.setOnClickListener {
            scrollView.post {
                scrollView.smoothScrollTo(0, 0)
            }
        }
    }

    private fun bindFootOverviewInteractions() {
        val scrollView = findViewById<ScrollView>(R.id.foot_overview_scroll)
        val zoneCard1 = findViewById<View>(R.id.zone_card_1)
        val zoneCard2 = findViewById<View>(R.id.zone_card_2)
        val zoneCard3 = findViewById<View>(R.id.zone_card_3)

        findViewById<View>(R.id.zone_marker_1)?.setOnClickListener {
            scrollToAndHighlightZone(scrollView, zoneCard1)
        }
        findViewById<View>(R.id.zone_marker_2)?.setOnClickListener {
            scrollToAndHighlightZone(scrollView, zoneCard2)
        }
        findViewById<View>(R.id.zone_marker_3)?.setOnClickListener {
            scrollToAndHighlightZone(scrollView, zoneCard3)
        }

        findViewById<View>(R.id.zone_1_view_analytics)?.setOnClickListener {
            startActivity(Intent(this, PlantarFootAnalyticsActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
        findViewById<View>(R.id.zone_2_view_analytics)?.setOnClickListener {
            startActivity(Intent(this, BigToeAnalyticsActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
        findViewById<View>(R.id.zone_3_view_analytics)?.setOnClickListener {
            startActivity(Intent(this, AnkleCuffAnalyticsActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }

    private fun scrollToAndHighlightZone(scrollView: ScrollView, target: View) {
        scrollView.post {
            val startY = scrollView.scrollY
            val endY = target.top
            ValueAnimator.ofInt(startY, endY).apply {
                duration = 900
                interpolator = DecelerateInterpolator()
                addUpdateListener { scrollView.scrollTo(0, it.animatedValue as Int) }
                start()
            }
            target.setBackgroundResource(R.drawable.foot_overview_zone_card_highlight_bg)
            target.postDelayed({
                target.setBackgroundResource(R.drawable.foot_overview_zone_card_bg)
            }, 1800L)
        }
    }
}
