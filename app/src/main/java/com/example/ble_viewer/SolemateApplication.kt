package com.example.ble_viewer

import android.app.Activity
import android.app.Application
import android.os.Bundle

import android.os.Handler
import android.os.Looper
import androidx.fragment.app.FragmentActivity

class SolemateApplication : Application() {

    companion object {
        private const val PREFS_NAME = "SolematePrefs"
        private const val KEY_APP_LOCK_ENABLED = "app_lock_enabled"

        @Volatile
        private var isUnlockedThisForegroundSession = false

        @Volatile
        private var isAuthInProgress = false

        fun markSessionUnlocked() {
            isUnlockedThisForegroundSession = true
        }

        fun setAuthInProgress(value: Boolean) {
            isAuthInProgress = value
        }
    }

    private var startedActivities = 0
    private val appStateHandler = Handler(Looper.getMainLooper())
    private var pendingLockReset: Runnable? = null

    private fun shouldSkipAppLock(activity: Activity): Boolean {
        return activity is WelcomeActivity ||
            activity is LoginActivity ||
            activity is RegistrationActivity ||
            activity is ForgotPasswordActivity
    }

    override fun onCreate() {
        super.onCreate()
        AutoShareScheduler.reschedule(this)

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                TextSizeScaleManager.applyTo(activity)
            }

            override fun onActivityStarted(activity: Activity) {
                startedActivities += 1
                pendingLockReset?.let { appStateHandler.removeCallbacks(it) }
                pendingLockReset = null
            }

            override fun onActivityResumed(activity: Activity) {
                TextSizeScaleManager.applyTo(activity)

                if (shouldSkipAppLock(activity)) return
                if (activity !is FragmentActivity) return

                val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                val appLockEnabled = prefs.getBoolean(KEY_APP_LOCK_ENABLED, false)

                if (!appLockEnabled || isUnlockedThisForegroundSession || isAuthInProgress) return
                if (!BiometricAuthHelper.isAppLockAvailable(activity)) return

                // Launch AppLockActivity if not already showing
                if (activity !is com.example.ble_viewer.AppLockActivity) {
                    isAuthInProgress = true
                    setAuthInProgress(true)
                    val intent = android.content.Intent(activity, com.example.ble_viewer.AppLockActivity::class.java)
                    activity.startActivity(intent)
                }
            }

            override fun onActivityPaused(activity: Activity) = Unit

            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                if (startedActivities == 0) {
                    val resetRunnable = Runnable {
                        if (startedActivities == 0) {
                            isUnlockedThisForegroundSession = false
                            isAuthInProgress = false
                        }
                    }
                    pendingLockReset = resetRunnable
                    // Delay reset so internal transitions do not immediately retrigger App Lock.
                    appStateHandler.postDelayed(resetRunnable, 1000L)
                }
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}