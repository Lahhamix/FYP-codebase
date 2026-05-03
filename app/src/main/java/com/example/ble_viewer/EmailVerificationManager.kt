package com.example.ble_viewer

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Patterns
import androidx.core.content.edit
import org.json.JSONObject

object EmailVerificationManager {

    private const val PREFS_NAME = "SolematePrefs"
    private const val KEY_PENDING_EMAIL               = "pending_signup_email"
    private const val KEY_PENDING_USERNAME            = "pending_signup_username"
    private const val KEY_PENDING_CREATED_AT          = "pending_signup_created_at"
    private const val KEY_PENDING_RESEND_AVAILABLE_AT = "pending_signup_resend_available_at"
    private const val KEY_PENDING_RESEND_COUNT        = "pending_signup_resend_count"
    private const val KEY_PENDING_VERIFICATION_ACTIVE = "pending_signup_verification_active"
    private const val KEY_PENDING_USER_ID             = "pending_signup_user_id"

    private const val RESEND_COOLDOWN_MS  = 60_000L
    private const val MAX_RESEND_REQUESTS = 5

    data class PendingRegistration(
        val email: String,
        val username: String,
        val password: String,
        val createdAtMillis: Long = System.currentTimeMillis()
    )

    sealed class RequestCodeResult {
        data class Success(val resendAvailableAtMillis: Long) : RequestCodeResult()
        data class EmailAlreadyRegistered(val message: String) : RequestCodeResult()
        data class UsernameTaken(val message: String) : RequestCodeResult()
        data class NetworkError(val message: String) : RequestCodeResult()
        data class TooManyRequests(val message: String) : RequestCodeResult()
        data class InvalidEmail(val message: String) : RequestCodeResult()
    }

    sealed class VerifyCodeResult {
        object Success : VerifyCodeResult()
        data class InvalidCode(val message: String) : VerifyCodeResult()
        data class ExpiredCode(val message: String) : VerifyCodeResult()
        data class TooManyAttempts(val message: String) : VerifyCodeResult()
        data class NetworkError(val message: String) : VerifyCodeResult()
    }

    fun isValidEmail(email: String): Boolean =
        Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()

    fun storePendingRegistration(context: Context, registration: PendingRegistration) {
        prefs(context).edit {
            putString(KEY_PENDING_EMAIL,    registration.email)
            putString(KEY_PENDING_USERNAME, registration.username)
            putLong(KEY_PENDING_CREATED_AT, registration.createdAtMillis)
            putBoolean(KEY_PENDING_VERIFICATION_ACTIVE, true)
        }
    }

    fun loadPendingRegistration(context: Context): PendingRegistration? {
        val p        = prefs(context)
        val email    = p.getString(KEY_PENDING_EMAIL, null).orEmpty().trim()
        val username = p.getString(KEY_PENDING_USERNAME, null).orEmpty().trim()
        val active   = p.getBoolean(KEY_PENDING_VERIFICATION_ACTIVE, false)
        if (!active || email.isBlank() || username.isBlank()) return null
        return PendingRegistration(
            email           = email,
            username        = username,
            password        = "",
            createdAtMillis = p.getLong(KEY_PENDING_CREATED_AT, System.currentTimeMillis())
        )
    }

    fun clearPendingRegistration(context: Context) {
        prefs(context).edit {
            remove(KEY_PENDING_EMAIL)
            remove(KEY_PENDING_USERNAME)
            remove(KEY_PENDING_CREATED_AT)
            remove(KEY_PENDING_RESEND_AVAILABLE_AT)
            remove(KEY_PENDING_RESEND_COUNT)
            remove(KEY_PENDING_VERIFICATION_ACTIVE)
            remove(KEY_PENDING_USER_ID)
        }
    }

    fun resendAvailableAt(context: Context): Long =
        prefs(context).getLong(KEY_PENDING_RESEND_AVAILABLE_AT, 0L)

    fun resendRemainingSeconds(context: Context): Int {
        val remaining = resendAvailableAt(context) - System.currentTimeMillis()
        return if (remaining <= 0L) 0 else ((remaining + 999L) / 1000L).toInt()
    }

    fun canResend(context: Context): Boolean {
        val p           = prefs(context)
        val resendCount = p.getInt(KEY_PENDING_RESEND_COUNT, 0)
        val availableAt = p.getLong(KEY_PENDING_RESEND_AVAILABLE_AT, 0L)
        return resendCount < MAX_RESEND_REQUESTS && System.currentTimeMillis() >= availableAt
    }

    fun requestVerificationCode(
        context: Context,
        registration: PendingRegistration,
        onResult: (RequestCodeResult) -> Unit
    ) {
        if (!isValidEmail(registration.email)) {
            postResult(onResult, RequestCodeResult.InvalidEmail("Invalid email format"))
            return
        }

        storePendingRegistration(context, registration)

        runOnBackground {
            val existingUserId = prefs(context).getString(KEY_PENDING_USER_ID, null)
                ?.takeIf { it.isNotBlank() }

            if (existingUserId != null) {
                val resp = ApiClient.post(
                    context, "/auth/change-pending-email",
                    JSONObject().apply {
                        put("userId", existingUserId)
                        put("email",  registration.email)
                    }
                )
                if (resp.code == 400 || resp.code == 404) {
                    // Stale userId — pending record no longer exists; start fresh
                    prefs(context).edit { remove(KEY_PENDING_USER_ID) }
                    doFreshRegister(context, registration, onResult)
                } else {
                    handleSendCodeResponse(context, resp, registration.email, onResult)
                }
            } else {
                doFreshRegister(context, registration, onResult)
            }
        }
    }

    private fun doFreshRegister(
        context: Context,
        registration: PendingRegistration,
        onResult: (RequestCodeResult) -> Unit
    ) {
        val resp = ApiClient.post(
            context, "/auth/register",
            JSONObject().apply {
                put("username", registration.username)
                put("email",    registration.email)
                put("password", registration.password)
            }
        )
        if (resp.code == 201) {
            val userId = resp.body?.optString("userId")?.takeIf { it.isNotBlank() }
            if (userId != null) {
                prefs(context).edit { putString(KEY_PENDING_USER_ID, userId) }
            }
        }
        handleSendCodeResponse(context, resp, registration.email, onResult)
    }

    fun resendVerificationCode(
        context: Context,
        onResult: (RequestCodeResult) -> Unit
    ) {
        if (!canResend(context)) {
            postResult(onResult, RequestCodeResult.TooManyRequests(context.getString(R.string.error_too_many_requests)))
            return
        }
        val userId = prefs(context).getString(KEY_PENDING_USER_ID, null)
        if (userId == null) {
            postResult(onResult, RequestCodeResult.NetworkError(context.getString(R.string.error_server_generic)))
            return
        }
        runOnBackground {
            val resp = ApiClient.post(
                context, "/auth/resend-verification",
                JSONObject().apply { put("userId", userId) }
            )
            val email = prefs(context).getString(KEY_PENDING_EMAIL, "").orEmpty()
            handleSendCodeResponse(context, resp, email, onResult)
        }
    }

    fun verifyCode(
        context: Context,
        code: String,
        onResult: (VerifyCodeResult) -> Unit
    ) {
        val userId = prefs(context).getString(KEY_PENDING_USER_ID, null)
        if (userId == null) {
            postResult(onResult, VerifyCodeResult.NetworkError(context.getString(R.string.error_server_generic)))
            return
        }
        runOnBackground {
            val resp = ApiClient.post(
                context, "/auth/verify-email",
                JSONObject().apply {
                    put("userId", userId)
                    put("code",   code)
                }
            )
            when {
                resp.code in 200..299 -> {
                    val body = resp.body ?: JSONObject()
                    val user = body.optJSONObject("user") ?: JSONObject()
                    SessionManager.saveSession(
                        context,
                        accessToken       = body.optString("accessToken"),
                        refreshToken      = body.optString("refreshToken"),
                        userId            = user.optString("id"),
                        username          = user.optString("username"),
                        email             = user.optString("email"),
                        displayName       = user.optString("displayName").ifBlank { null },
                        profilePictureUrl = user.optString("profilePictureUrl").ifBlank { null }
                    )
                    postResult(onResult, VerifyCodeResult.Success)
                }
                resp.code == -1 -> {
                    val errorDetail = resp.body?.optString("_error")
                    val errorMsg = errorDetail ?: context.getString(R.string.error_no_internet)
                    postResult(onResult, VerifyCodeResult.NetworkError(errorMsg))
                }
                resp.code == 400 -> {
                    val errCode = resp.body?.optString("code").orEmpty()
                    val msg     = resp.body?.optString("error") ?: context.getString(R.string.error_server_generic)
                    when (errCode) {
                        "CODE_EXPIRED" -> postResult(onResult, VerifyCodeResult.ExpiredCode(msg))
                        else           -> postResult(onResult, VerifyCodeResult.InvalidCode(msg))
                    }
                }
                resp.code == 429 -> {
                    val msg = resp.body?.optString("error") ?: context.getString(R.string.error_too_many_requests)
                    postResult(onResult, VerifyCodeResult.TooManyAttempts(msg))
                }
                else -> {
                    val msg = resp.body?.optString("error") ?: context.getString(R.string.error_server_generic)
                    postResult(onResult, VerifyCodeResult.NetworkError(msg))
                }
            }
        }
    }

    fun completeRegistration(context: Context) {
        clearPendingRegistration(context)
    }

    // ── Private helpers ───────────────────────────────────────────

    private fun handleSendCodeResponse(
        context: Context,
        resp: ApiClient.Response,
        email: String,
        onResult: (RequestCodeResult) -> Unit
    ) {
        when {
            resp.code == -1 -> {
                // Extract actual error from response body if available
                val errorDetail = resp.body?.optString("_error")
                val errorMsg = errorDetail ?: context.getString(R.string.error_no_internet)
                postResult(onResult, RequestCodeResult.NetworkError(errorMsg))
            }
            resp.code in 200..299 || resp.code == 201 -> {
                val now               = System.currentTimeMillis()
                val resendAvailableAt = now + RESEND_COOLDOWN_MS
                val currentCount      = prefs(context).getInt(KEY_PENDING_RESEND_COUNT, 0)
                prefs(context).edit {
                    putLong(KEY_PENDING_RESEND_AVAILABLE_AT, resendAvailableAt)
                    putInt(KEY_PENDING_RESEND_COUNT, (currentCount + 1).coerceAtLeast(1))
                    putBoolean(KEY_PENDING_VERIFICATION_ACTIVE, true)
                }
                postResult(onResult, RequestCodeResult.Success(resendAvailableAt))
            }
            resp.code == 400 -> {
                val msg = resp.body?.optString("error") ?: context.getString(R.string.error_server_generic)
                postResult(onResult, RequestCodeResult.NetworkError(msg))
            }
            resp.code == 409 -> {
                val errCode = resp.body?.optString("code").orEmpty()
                val msg     = resp.body?.optString("error") ?: context.getString(R.string.error_server_generic)
                if (errCode == "USERNAME_TAKEN") {
                    postResult(onResult, RequestCodeResult.UsernameTaken(msg))
                } else {
                    postResult(onResult, RequestCodeResult.EmailAlreadyRegistered(msg))
                }
            }
            resp.code == 429 -> {
                val msg = resp.body?.optString("error") ?: context.getString(R.string.error_too_many_requests)
                postResult(onResult, RequestCodeResult.TooManyRequests(msg))
            }
            else -> {
                val msg = resp.body?.optString("error") ?: context.getString(R.string.error_server_generic)
                postResult(onResult, RequestCodeResult.NetworkError(msg))
            }
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun runOnBackground(block: () -> Unit) {
        Thread(block, "email-verification-bg").start()
    }

    private fun <T> postResult(callback: (T) -> Unit, result: T) {
        Handler(Looper.getMainLooper()).post { callback(result) }
    }
}
