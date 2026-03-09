package com.example.ble_viewer

import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.Log
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Secure storage for the ECDH shared secret using Android Keystore.
 *
 * NOTE: This makes it much harder (not impossible) to extract the secret
 * from the app, because the wrapping key is hardware-backed and non‑exportable.
 */
object SharedSecretStore {

    private const val TAG = "SharedSecretStore"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val MASTER_KEY_ALIAS = "solemate_shared_secret_master"
    private const val PREFS_NAME = "SolemateSecrets"
    private const val SHARED_SECRET_KEY = "shared_secret"

    private fun getOrCreateMasterKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(MASTER_KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            MASTER_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(false)
            .build()
        keyGen.init(spec)
        return keyGen.generateKey()
    }

    fun saveSharedSecret(context: Context, secret: ByteArray) {
        try {
            val key = getOrCreateMasterKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(secret)
            val combined = iv + ciphertext

            val encoded = Base64.encodeToString(combined, Base64.NO_WRAP)
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(SHARED_SECRET_KEY, encoded)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save shared secret: ${e.message}", e)
        }
    }

    fun loadSharedSecret(context: Context): ByteArray? {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val encoded = prefs.getString(SHARED_SECRET_KEY, null) ?: return null
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            if (combined.size <= 12) return null

            val iv = combined.copyOfRange(0, 12)
            val ciphertext = combined.copyOfRange(12, combined.size)

            val key = getOrCreateMasterKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load shared secret: ${e.message}", e)
            null
        }
    }

    fun clearSharedSecret(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(SHARED_SECRET_KEY)
            .apply()
    }
}

