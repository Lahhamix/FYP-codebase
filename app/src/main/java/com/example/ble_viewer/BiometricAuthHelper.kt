package com.example.ble_viewer

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

object BiometricAuthHelper {

    private val APP_LOCK_AUTHENTICATORS =
        BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

    fun isAppLockAvailable(activity: FragmentActivity): Boolean {
        return BiometricManager.from(activity).canAuthenticate(APP_LOCK_AUTHENTICATORS) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    fun authenticateForAppLock(
        activity: FragmentActivity,
        onSuccess: () -> Unit,
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
                    onFailure()
                }
            }
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(R.string.app_lock_prompt_title))
            .setSubtitle(activity.getString(R.string.app_lock_prompt_subtitle))
            .setAllowedAuthenticators(APP_LOCK_AUTHENTICATORS)
            .build()

        prompt.authenticate(promptInfo)
    }
}