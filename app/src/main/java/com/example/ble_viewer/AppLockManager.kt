package com.example.ble_viewer

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import androidx.fragment.app.FragmentActivity

object AppLockManager {

    const val PREFS_NAME              = "SolematePrefs"
    const val KEY_APP_LOCK_ENABLED    = "app_lock_enabled"
    const val KEY_APP_LOCK_TIMEOUT_MS = "app_lock_timeout_ms"

    private const val DEFAULT_LOCK_TIMEOUT_MS          = 3_000L
    private const val INTERNAL_BG_TRANSITION_DELAY_MS  = 350L

    @Volatile private var initialized          = false
    @Volatile private var isSessionUnlocked    = false
    @Volatile private var isAuthInProgress     = false
    @Volatile private var isLockScreenVisible  = false

    private var startedActivities            = 0
    private var appContext: Context?         = null
    private val mainHandler                  = Handler(Looper.getMainLooper())
    private var pendingBackgroundMark: Runnable? = null
    private var pendingTimeoutLock: Runnable?    = null
    private var lastBackgroundAtElapsedMs        = 0L

    fun init(application: Application) {
        if (initialized) return
        initialized = true
        appContext  = application.applicationContext

        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

            override fun onActivityStarted(activity: Activity) {
                val wasInBackground = startedActivities == 0
                startedActivities += 1

                pendingBackgroundMark?.let { mainHandler.removeCallbacks(it) }
                pendingBackgroundMark = null

                if (wasInBackground) {
                    pendingTimeoutLock?.let { mainHandler.removeCallbacks(it) }
                    pendingTimeoutLock = null
                    if (shouldLockNow(activity)) launchLockScreen(activity)
                }
            }

            override fun onActivityResumed(activity: Activity)  { applyForegroundSecurityFlags(activity) }
            override fun onActivityPaused(activity: Activity)   { applyBackgroundSecurityFlags(activity) }

            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                if (startedActivities == 0) {
                    val bgRunnable = Runnable {
                        if (startedActivities == 0) markBackgroundNow()
                    }
                    pendingBackgroundMark = bgRunnable
                    mainHandler.postDelayed(bgRunnable, INTERNAL_BG_TRANSITION_DELAY_MS)
                }
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    fun markSessionUnlocked() {
        isSessionUnlocked   = true
        isAuthInProgress    = false
        isLockScreenVisible = false
    }

    fun markSessionLocked() { isSessionUnlocked = false }

    fun setAuthInProgress(value: Boolean)   { isAuthInProgress    = value }
    fun setLockScreenVisible(value: Boolean) { isLockScreenVisible = value }

    fun isAppLockEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_APP_LOCK_ENABLED, false)

    fun setAppLockEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_APP_LOCK_ENABLED, enabled).apply()
        if (!enabled) markSessionUnlocked()
    }

    fun authenticateForUnlock(
        activity: Activity,
        onSuccess: () -> Unit,
        onFailure: () -> Unit
    ) {
        isAuthInProgress = true

        if (activity is FragmentActivity && BiometricAuthHelper.isBiometricAvailable(activity)) {
            BiometricAuthHelper.authenticateWithBiometric(
                activity  = activity,
                onSuccess = { markSessionUnlocked(); onSuccess() },
                onFailure = { isAuthInProgress = false; onFailure() }
            )
        } else {
            isAuthInProgress = false
            onFailure()
        }
    }

    // ── Private helpers ──────────────────────────────────────────

    private fun shouldLockNow(activity: Activity): Boolean {
        if (!isAppLockEnabled(activity)) return false
        if (isSessionUnlocked)           return false
        if (isAuthInProgress || isLockScreenVisible) return false
        if (shouldSkipLock(activity))    return false
        return true
    }

    private fun launchLockScreen(activity: Activity) {
        isAuthInProgress    = true
        isLockScreenVisible = true
        activity.startActivity(
            Intent(activity, AppLockActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
        )
        activity.overridePendingTransition(0, 0)
    }

    private fun shouldSkipLock(activity: Activity) =
        activity is WelcomeActivity      ||
        activity is LoginActivity        ||
        activity is RegistrationActivity ||
        activity is ForgotPasswordActivity ||
        activity is AppLockActivity

    private fun markBackgroundNow() {
        lastBackgroundAtElapsedMs = SystemClock.elapsedRealtime()
        val timeout = prefs(appContext).getLong(KEY_APP_LOCK_TIMEOUT_MS, DEFAULT_LOCK_TIMEOUT_MS)
            .coerceAtLeast(0L)
        val r = Runnable {
            if (startedActivities == 0) {
                isSessionUnlocked = false
                isAuthInProgress  = false
            }
        }
        pendingTimeoutLock = r
        mainHandler.postDelayed(r, timeout)
    }

    private fun applyForegroundSecurityFlags(activity: Activity) {
        if (activity is AppLockActivity) {
            activity.window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
            return
        }
        if (!isAppLockEnabled(activity) || shouldSkipLock(activity)) {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            return
        }
        if (!isSessionUnlocked || isAuthInProgress) {
            activity.window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private fun applyBackgroundSecurityFlags(activity: Activity) {
        if (!isAppLockEnabled(activity)) return
        if (shouldSkipLock(activity) && activity !is AppLockActivity) return
        activity.window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
    }

    private fun prefs(context: Context?) =
        (context ?: appContext)!!.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
