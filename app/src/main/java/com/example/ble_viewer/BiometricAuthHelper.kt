package com.example.ble_viewer

import android.app.Activity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

object BiometricAuthHelper {

    private val BIOMETRIC_AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_WEAK

    fun isBiometricAvailable(activity: Activity): Boolean {
        return BiometricManager.from(activity).canAuthenticate(BIOMETRIC_AUTHENTICATORS) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    fun authenticateWithBiometric(
        activity: FragmentActivity,
        onSuccess: () -> Unit,
        onFallbackToPin: () -> Unit,
        onFailure: () -> Unit = {}
    ) {
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        errorCode == BiometricPrompt.ERROR_NO_BIOMETRICS ||
                        errorCode == BiometricPrompt.ERROR_HW_UNAVAILABLE
                    ) {
                        onFallbackToPin()
                    } else {
                        onFailure()
                    }
                }
            }
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(R.string.app_lock_prompt_title))
            .setSubtitle(activity.getString(R.string.app_lock_prompt_subtitle))
            .setAllowedAuthenticators(BIOMETRIC_AUTHENTICATORS)
            .setNegativeButtonText(activity.getString(R.string.app_lock_use_pin))
            .build()

        prompt.authenticate(promptInfo)
    }

    // Backward-compatible wrappers used by existing settings flow.
    fun isAppLockAvailable(activity: FragmentActivity): Boolean {
        return isBiometricAvailable(activity)
    }

    fun authenticateForAppLock(
        activity: FragmentActivity,
        onSuccess: () -> Unit,
        onFailure: () -> Unit = {}
    ) {
        authenticateWithBiometric(
            activity = activity,
            onSuccess = onSuccess,
            onFallbackToPin = onFailure,
            onFailure = onFailure
        )
    }
}