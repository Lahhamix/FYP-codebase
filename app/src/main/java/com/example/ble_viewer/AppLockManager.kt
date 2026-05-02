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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.content.edit
import androidx.fragment.app.FragmentActivity

/**
 * Centralized app-lock coordinator.
 *
 * Responsibilities:
 * - Track foreground/background transitions at app level.
 * - Trigger lock only when app is freshly opened or returns after timeout.
 * - Avoid relocking while navigating inside the app.
 * - Protect Recents previews and screenshots for sensitive screens.
 * - Run biometric authentication with PIN fallback.
 */
object AppLockManager {

    const val PREFS_NAME = "SolematePrefs"
    const val KEY_APP_LOCK_ENABLED = "app_lock_enabled"
    const val KEY_APP_LOCK_PIN = "app_lock_pin"
    const val KEY_APP_LOCK_TIMEOUT_MS = "app_lock_timeout_ms"

    private const val DEFAULT_LOCK_TIMEOUT_MS = 3_000L
    private const val INTERNAL_BG_TRANSITION_DELAY_MS = 350L

    @Volatile
    private var initialized = false

    @Volatile
    private var isSessionUnlocked = false

    @Volatile
    private var isAuthInProgress = false

    @Volatile
    private var isLockScreenVisible = false

    private var startedActivities = 0
    private var appContext: Context? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingBackgroundMark: Runnable? = null
    private var pendingTimeoutLock: Runnable? = null
    private var lastBackgroundAtElapsedMs: Long = 0L

    fun init(application: Application) {
        if (initialized) return
        initialized = true
        appContext = application.applicationContext

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

                    if (shouldLockNow(activity)) {
                        launchLockScreen(activity)
                    }
                }
            }

            override fun onActivityResumed(activity: Activity) {
                applyForegroundSecurityFlags(activity)
            }

            override fun onActivityPaused(activity: Activity) {
                // Set secure flag before potential snapshot capture for Recents.
                applyBackgroundSecurityFlags(activity)
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                if (startedActivities == 0) {
                    val bgRunnable = Runnable {
                        if (startedActivities == 0) {
                            markBackgroundNow()
                        }
                    }
                    pendingBackgroundMark = bgRunnable
                    // Delay to avoid false background during internal activity transitions.
                    mainHandler.postDelayed(bgRunnable, INTERNAL_BG_TRANSITION_DELAY_MS)
                }
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    fun markSessionUnlocked() {
        isSessionUnlocked = true
        isAuthInProgress = false
        isLockScreenVisible = false
    }

    fun markSessionLocked() {
        isSessionUnlocked = false
    }

    fun setAuthInProgress(value: Boolean) {
        isAuthInProgress = value
    }

    fun setLockScreenVisible(value: Boolean) {
        isLockScreenVisible = value
    }

    fun isAppLockEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_APP_LOCK_ENABLED, false)
    }

    fun setAppLockEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_APP_LOCK_ENABLED, enabled) }
        if (!enabled) {
            markSessionUnlocked()
        }
    }

    fun authenticateForUnlock(
        activity: Activity,
        onSuccess: () -> Unit,
        onFailure: () -> Unit
    ) {
        isAuthInProgress = true

        if (activity is FragmentActivity && BiometricAuthHelper.isBiometricAvailable(activity)) {
            BiometricAuthHelper.authenticateWithBiometric(
                activity = activity,
                onSuccess = {
                    markSessionUnlocked()
                    onSuccess()
                },
                onFallbackToPin = {
                    promptForPin(activity, onSuccess, onFailure)
                },
                onFailure = {
                    isAuthInProgress = false
                    onFailure()
                }
            )
        } else {
            promptForPin(activity, onSuccess, onFailure)
        }
    }

    fun ensurePinExists(
        activity: Activity,
        onSuccess: () -> Unit,
        onFailure: () -> Unit
    ) {
        if (pinStore(activity).hasPin()) {
            onSuccess()
            return
        }
        promptForNewPin(activity, onSuccess, onFailure)
    }

    private fun shouldLockNow(activity: Activity): Boolean {
        if (!isAppLockEnabled(activity)) return false
        if (isSessionUnlocked) return false
        if (isAuthInProgress || isLockScreenVisible) return false
        if (shouldSkipLock(activity)) return false
        return true
    }

    private fun launchLockScreen(activity: Activity) {
        isAuthInProgress = true
        isLockScreenVisible = true

        val intent = Intent(activity, AppLockActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
        activity.startActivity(intent)
        activity.overridePendingTransition(0, 0)
    }

    private fun shouldSkipLock(activity: Activity): Boolean {
        return activity is WelcomeActivity ||
            activity is LoginActivity ||
            activity is RegistrationActivity ||
            activity is ForgotPasswordActivity ||
            activity is AppLockActivity
    }

    private fun markBackgroundNow() {
        lastBackgroundAtElapsedMs = SystemClock.elapsedRealtime()

        val timeout = lockTimeoutMs(appContext)
        val timeoutRunnable = Runnable {
            if (startedActivities == 0) {
                // Timeout reached while app is in background: next foreground requires auth.
                isSessionUnlocked = false
                isAuthInProgress = false
            }
        }
        pendingTimeoutLock = timeoutRunnable
        mainHandler.postDelayed(timeoutRunnable, timeout)
    }

    private fun lockTimeoutMs(context: Context?): Long {
        val safeContext = context ?: return DEFAULT_LOCK_TIMEOUT_MS
        return prefs(safeContext).getLong(KEY_APP_LOCK_TIMEOUT_MS, DEFAULT_LOCK_TIMEOUT_MS)
            .coerceAtLeast(0L)
    }

    private fun applyForegroundSecurityFlags(activity: Activity) {
        if (activity is AppLockActivity) {
            // Keep lock screen secure always.
            activity.window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
            return
        }

        if (!isAppLockEnabled(activity) || shouldSkipLock(activity)) {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            return
        }

        // While locked/auth in progress, keep sensitive screens secure.
        if (!isSessionUnlocked || isAuthInProgress) {
            activity.window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private fun applyBackgroundSecurityFlags(activity: Activity) {
        if (!isAppLockEnabled(activity)) return
        if (shouldSkipLock(activity) && activity !is AppLockActivity) return

        activity.window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
    }

    private fun promptForPin(
        activity: Activity,
        onSuccess: () -> Unit,
        onFailure: () -> Unit
    ) {
        val storage = pinStore(activity)
        if (!storage.hasPin()) {
            promptForNewPin(
                activity = activity,
                onSuccess = {
                    markSessionUnlocked()
                    onSuccess()
                },
                onFailure = {
                    isAuthInProgress = false
                    onFailure()
                }
            )
            return
        }

        val input = AppCompatEditText(activity).apply {
            hint = "Enter PIN"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }

        AlertDialog.Builder(activity)
            .setTitle("Unlock with PIN")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("Unlock") { _, _ ->
                val entered = input.text?.toString().orEmpty().trim()
                if (storage.verifyPin(entered)) {
                    markSessionUnlocked()
                    onSuccess()
                } else {
                    isAuthInProgress = false
                    onFailure()
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                isAuthInProgress = false
                onFailure()
            }
            .show()
    }

    private fun promptForNewPin(
        activity: Activity,
        onSuccess: () -> Unit,
        onFailure: () -> Unit
    ) {
        val pinInput = AppCompatEditText(activity).apply {
            hint = "Create PIN (4-8 digits)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        val confirmInput = AppCompatEditText(activity).apply {
            hint = "Confirm PIN"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }

        val container = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(pinInput)
            addView(confirmInput)
        }

        AlertDialog.Builder(activity)
            .setTitle("Set App Lock PIN")
            .setView(container)
            .setCancelable(false)
            .setPositiveButton("Save") { _, _ ->
                val pin = pinInput.text?.toString().orEmpty().trim()
                val confirm = confirmInput.text?.toString().orEmpty().trim()
                val valid = pin.length in 4..8 && pin.all { it.isDigit() } && pin == confirm
                if (valid) {
                    pinStore(activity).savePin(pin)
                    onSuccess()
                } else {
                    isAuthInProgress = false
                    onFailure()
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                isAuthInProgress = false
                onFailure()
            }
            .show()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun pinStore(context: Context): AppLockPinStore = SharedPrefsPinStore(context)

    interface AppLockPinStore {
        fun hasPin(): Boolean
        fun savePin(pin: String)
        fun verifyPin(candidate: String): Boolean
    }

    /**
     * SharedPreferences-backed implementation.
     * Replace this class with EncryptedSharedPreferences later without changing callers.
     */
    private class SharedPrefsPinStore(context: Context) : AppLockPinStore {
        private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        override fun hasPin(): Boolean = prefs.getString(KEY_APP_LOCK_PIN, null)?.isNotBlank() == true

        override fun savePin(pin: String) {
            prefs.edit { putString(KEY_APP_LOCK_PIN, pin) }
        }

        override fun verifyPin(candidate: String): Boolean {
            return prefs.getString(KEY_APP_LOCK_PIN, null) == candidate
        }
    }
}
