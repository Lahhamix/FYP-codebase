
package com.example.ble_viewer

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class AppLockActivity : AppCompatActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_app_lock)

		// Immediately trigger authentication
		BiometricAuthHelper.authenticateForAppLock(
			this,
			onSuccess = {
				SolemateApplication.markSessionUnlocked()
				SolemateApplication.setAuthInProgress(false)
				finish()
			},
			onFailure = {
				SolemateApplication.setAuthInProgress(false)
				finishAffinity()
			}
		)
	}
}

