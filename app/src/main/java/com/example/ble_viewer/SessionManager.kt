package com.example.ble_viewer

import android.content.Context
import androidx.core.content.edit

object SessionManager {

    private const val PREFS = "SolemateSession"
    private const val KEY_ACCESS_TOKEN       = "access_token"
    private const val KEY_REFRESH_TOKEN      = "refresh_token"
    private const val KEY_USER_ID            = "user_id"
    private const val KEY_USERNAME           = "username"
    private const val KEY_EMAIL              = "email"
    private const val KEY_DISPLAY_NAME       = "display_name"
    private const val KEY_PROFILE_PIC_URL    = "profile_picture_url"

    fun saveSession(
        context: Context,
        accessToken: String,
        refreshToken: String,
        userId: String,
        username: String,
        email: String,
        displayName: String? = null,
        profilePictureUrl: String? = null,
        authProvider: String = "local"
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_ACCESS_TOKEN,    accessToken)
            putString(KEY_REFRESH_TOKEN,   refreshToken)
            putString(KEY_USER_ID,         userId)
            putString(KEY_USERNAME,        username)
            putString(KEY_EMAIL,           email)
            putString(KEY_DISPLAY_NAME,    displayName)
            putString(KEY_PROFILE_PIC_URL, profilePictureUrl)
            putString("auth_provider",     authProvider)
        }
        // Keep legacy prefs in sync so existing screens continue to work
        context.getSharedPreferences("SolematePrefs", Context.MODE_PRIVATE).edit {
            putString("username",       username)
            putString("email",          email)
            putBoolean("is_registered", true)
            putBoolean("is_logged_in",  true)
            putString("auth_provider",  authProvider)
            if (displayName != null)       putString("display_name",         displayName)
            if (profilePictureUrl != null) putString("server_profile_picture", profilePictureUrl)
        }
    }

    fun updateTokens(context: Context, accessToken: String, refreshToken: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_ACCESS_TOKEN,  accessToken)
            putString(KEY_REFRESH_TOKEN, refreshToken)
        }
    }

    fun updateUsername(context: Context, username: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_USERNAME, username)
        }
        context.getSharedPreferences("SolematePrefs", Context.MODE_PRIVATE).edit {
            putString("username", username)
        }
    }

    fun updateDisplayName(context: Context, displayName: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_DISPLAY_NAME, displayName)
        }
        context.getSharedPreferences("SolematePrefs", Context.MODE_PRIVATE).edit {
            putString("display_name", displayName)
        }
    }

    fun getAccessToken(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ACCESS_TOKEN, null)

    fun getRefreshToken(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_REFRESH_TOKEN, null)

    fun getUserId(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_USER_ID, null)

    fun getUsername(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_USERNAME, null)

    fun getAuthProvider(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("auth_provider", "local") ?: "local"

    fun getEmail(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_EMAIL, null)

    fun getDisplayName(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_DISPLAY_NAME, null)

    fun getProfilePictureUrl(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PROFILE_PIC_URL, null)

    fun clearSession(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { clear() }
        context.getSharedPreferences("SolematePrefs", Context.MODE_PRIVATE).edit {
            putBoolean("is_logged_in", false)
        }
    }

    fun isLoggedIn(context: Context): Boolean = getAccessToken(context) != null
}
