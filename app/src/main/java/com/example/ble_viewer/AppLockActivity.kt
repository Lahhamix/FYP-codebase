
package com.example.ble_viewer

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

class AppLockActivity : AppCompatActivity() {
	private val uiHandler = Handler(Looper.getMainLooper())
	private var unlockFlowStarted = false
	private var authRunnable: Runnable? = null

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_app_lock)
		window.setFlags(
			WindowManager.LayoutParams.FLAG_SECURE,
			WindowManager.LayoutParams.FLAG_SECURE
		)

		AppLockManager.setLockScreenVisible(true)
		AppLockManager.markSessionLocked()

		// Block navigation bypass. Pressing back sends app to background.
		onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
			override fun handleOnBackPressed() {
				moveTaskToBack(true)
			}
		})

		// Allow user to tap the lock screen to trigger unlock.
		findViewById<View>(android.R.id.content).setOnClickListener {
			startUnlockIfNeeded()
		}
	}

	override fun onResume() {
		super.onResume()
		AppLockManager.setLockScreenVisible(true)

		// Delay prompt slightly so Recents can show the blocked screen without immediately
		// popping biometric UI while the task-switcher is still on top.
		authRunnable?.let { uiHandler.removeCallbacks(it) }
		authRunnable = Runnable { if (hasWindowFocus()) startUnlockIfNeeded() }
		uiHandler.postDelayed(authRunnable!!, 250L)
	}

	override fun onPause() {
		super.onPause()
		authRunnable?.let { uiHandler.removeCallbacks(it) }
	}

	override fun onWindowFocusChanged(hasFocus: Boolean) {
		super.onWindowFocusChanged(hasFocus)
		if (hasFocus) startUnlockIfNeeded()
	}

	private fun startUnlockIfNeeded() {
		if (unlockFlowStarted) return
		unlockFlowStarted = true

		AppLockManager.authenticateForUnlock(
			activity = this,
			onSuccess = {
				finish()
				overridePendingTransition(0, 0)
			},
			onFailure = {
				unlockFlowStarted = false
				moveTaskToBack(true)
			}
		)
	}

	override fun onDestroy() {
		super.onDestroy()
		if (isFinishing) {
			AppLockManager.setLockScreenVisible(false)
		}
	}
}

