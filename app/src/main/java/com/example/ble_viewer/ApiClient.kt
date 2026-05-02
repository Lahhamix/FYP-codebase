package com.example.ble_viewer

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object ApiClient {

    data class Response(val code: Int, val body: JSONObject?)

    fun baseUrl(context: Context): String =
        BuildConfig.EMAIL_BACKEND_BASE_URL.trim().ifBlank { "http://10.0.2.2:3000" }.trimEnd('/')

    private fun request(
        context: Context,
        method: String,
        path: String,
        payload: JSONObject?,
        accessToken: String? = null
    ): Response {
        return try {
            val conn = (URL("${baseUrl(context)}$path").openConnection() as HttpURLConnection).apply {
                requestMethod = method
                setRequestProperty("Accept", "application/json")
                if (accessToken != null) setRequestProperty("Authorization", "Bearer $accessToken")
                connectTimeout = 15_000
                readTimeout    = 15_000
                if (payload != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
            }
            if (payload != null) {
                OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()); it.flush() }
            }
            val code   = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body   = stream?.bufferedReader()?.use { JSONObject(it.readText()) }
            conn.disconnect()
            Response(code, body)
        } catch (e: Exception) {
            Log.e("ApiClient", "$method $path: ${e.message}")
            Response(-1, null)
        }
    }

    fun post(context: Context, path: String, payload: JSONObject, accessToken: String? = null): Response =
        request(context, "POST", path, payload, accessToken)

    fun patch(context: Context, path: String, payload: JSONObject, accessToken: String? = null): Response =
        request(context, "PATCH", path, payload, accessToken)

    fun get(context: Context, path: String, accessToken: String? = null): Response =
        request(context, "GET", path, null, accessToken)

    fun refreshAccessToken(context: Context): String? {
        val raw = SessionManager.getRefreshToken(context) ?: return null
        val resp = post(context, "/auth/refresh", JSONObject().put("refreshToken", raw))
        if (resp.code in 200..299) {
            val newAccess  = resp.body?.optString("accessToken").orEmpty()
            val newRefresh = resp.body?.optString("refreshToken").orEmpty()
            if (newAccess.isNotBlank()) {
                SessionManager.updateTokens(context, newAccess, newRefresh.ifBlank { raw })
                return newAccess
            }
        }
        return null
    }

    fun authedPatch(context: Context, path: String, payload: JSONObject): Response {
        val token = SessionManager.getAccessToken(context) ?: return Response(401, null)
        val resp  = patch(context, path, payload, token)
        if (resp.code != 401) return resp
        val newToken = refreshAccessToken(context) ?: return Response(401, null)
        return patch(context, path, payload, newToken)
    }
}
