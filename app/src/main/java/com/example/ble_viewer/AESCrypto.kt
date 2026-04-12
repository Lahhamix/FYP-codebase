package com.example.ble_viewer

import android.util.Log
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object AESCrypto {

    private const val TAG = "AESCrypto"
    private var isInitialized = false

    // These will be populated by the init function after the key exchange
    private lateinit var heartRateKey: SecretKeySpec
    private lateinit var spo2Key: SecretKeySpec
    private lateinit var stepsKey: SecretKeySpec
    private lateinit var motionKey: SecretKeySpec
    private lateinit var flexKey: SecretKeySpec
    private lateinit var pressureKey: SecretKeySpec

    // IVs can remain static as they don't need to be secret, just unique for each encryption.
    // These MUST match the values in the Arduino code.
    private val heartRateIV = IvParameterSpec(ByteArray(16) { (0x20 + it).toByte() })
    private val spo2IV = IvParameterSpec(ByteArray(16) { (0x30 + it).toByte() })
    private val stepsIV = IvParameterSpec(ByteArray(16) { (0x00 + it).toByte() })
    private val motionIV = IvParameterSpec(ByteArray(16) { (0x10 + it).toByte() })
    private val flexIV = IvParameterSpec(ByteArray(16) { (0x40 + it).toByte() })
    private val pressureIV = IvParameterSpec(ByteArray(16) { (0x50 + it).toByte() })

    /**
     * Fallback: Initialize with legacy static keys (for Arduino firmware without key exchange).
     * Use when key exchange service is not found on the device.
     */
    fun initWithLegacyKeys() {
        heartRateKey = SecretKeySpec(byteArrayOf(
            0x48, 0x65, 0x61, 0x72, 0x74, 0x52, 0x61, 0x74,
            0x65, 0x4B, 0x65, 0x79, 0x31, 0x32, 0x33, 0x34
        ), "AES")
        spo2Key = SecretKeySpec(byteArrayOf(
            0x53, 0x70, 0x4F, 0x32, 0x4B, 0x65, 0x79, 0x31,
            0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39
        ), "AES")
        stepsKey = SecretKeySpec(byteArrayOf(
            0x53, 0x74, 0x65, 0x70, 0x73, 0x4B, 0x65, 0x79,
            0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38
        ), "AES")
        motionKey = SecretKeySpec(byteArrayOf(
            0x4D, 0x6F, 0x74, 0x69, 0x6F, 0x6E, 0x4B, 0x65,
            0x79, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37
        ), "AES")
        flexKey = SecretKeySpec(byteArrayOf(
            0x46, 0x6C, 0x65, 0x78, 0x4B, 0x65, 0x79, 0x31,
            0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39
        ), "AES")
        pressureKey = SecretKeySpec(byteArrayOf(
            0x50, 0x72, 0x65, 0x73, 0x73, 0x4B, 0x65, 0x79,
            0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38
        ), "AES")
        isInitialized = true
        Log.d(TAG, "AESCrypto initialized with legacy keys (no key exchange).")
    }

    /**
     * Initializes the crypto engine with a shared secret from ECDH.
     * This MUST be called after the key exchange is complete and before any decryption.
     */
    fun init(sharedSecret: ByteArray) {
        // Use a Key Derivation Function (KDF) to create different keys for each purpose
        // from the same shared secret. This is a standard and secure practice.
        heartRateKey = SecretKeySpec(deriveKey(sharedSecret, "HEART_RATE"), "AES")
        spo2Key = SecretKeySpec(deriveKey(sharedSecret, "SPO2"), "AES")
        stepsKey = SecretKeySpec(deriveKey(sharedSecret, "STEPS"), "AES")
        motionKey = SecretKeySpec(deriveKey(sharedSecret, "MOTION"), "AES")
        flexKey = SecretKeySpec(deriveKey(sharedSecret, "FLEX"), "AES")
        pressureKey = SecretKeySpec(deriveKey(sharedSecret, "PRESSURE"), "AES")
        isInitialized = true
        Log.d(TAG, "AESCrypto initialized successfully from shared secret.")
    }

    /**
     * A simple key derivation function using SHA-256. This creates a unique 16-byte key
     * for a given purpose (e.g., "HEART_RATE") from the master shared secret.
     * This logic MUST be identical on the peripheral side.
     */
    private fun deriveKey(sharedSecret: ByteArray, purpose: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(sharedSecret)
        digest.update(purpose.toByteArray(Charsets.UTF_8))
        return digest.digest().copyOf(16) // Use the first 128 bits (16 bytes) for the AES key
    }

    private fun decryptWithKeyIV(encryptedBytes: ByteArray, key: SecretKeySpec, iv: IvParameterSpec): String {
        if (!isInitialized) {
            Log.e(TAG, "Decryption failed: AESCrypto has not been initialized.")
            return "DECRYPT_ERROR: NOT_INITIALIZED"
        }
        
        return try {
            if (encryptedBytes.isEmpty()) {
                Log.w(TAG, "Decryption failed: input byte array is empty.")
                return "DECRYPT_ERROR: EMPTY_INPUT"
            }

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, key, iv)
            val decryptedBytes = cipher.doFinal(encryptedBytes)
            String(decryptedBytes, Charsets.UTF_8).trim()
        } catch (e: Exception) {
            Log.e(TAG, "Decryption failed with exception: ${e.message}", e)
            "DECRYPT_ERROR"
        }
    }

    // Public-facing decryption functions
    fun decryptHeartRate(encryptedBytes: ByteArray): String = decryptWithKeyIV(encryptedBytes, heartRateKey, heartRateIV)
    fun decryptSpO2(encryptedBytes: ByteArray): String = decryptWithKeyIV(encryptedBytes, spo2Key, spo2IV)
    fun decryptSteps(encryptedBytes: ByteArray): String = decryptWithKeyIV(encryptedBytes, stepsKey, stepsIV)
    fun decryptMotion(encryptedBytes: ByteArray): String = decryptWithKeyIV(encryptedBytes, motionKey, motionIV)
    fun decryptFlex(encryptedBytes: ByteArray): String = decryptWithKeyIV(encryptedBytes, flexKey, flexIV)
    
    // Binary decryption for pressure matrix (decrypts 16-byte encrypted payload, returns 12-byte decrypted)
    // Arduino pads 12-byte payload to 16 with PKCS7; Cipher.doFinal() returns plaintext with padding already removed.
    fun decryptPressurePayload(encryptedBytes: ByteArray): ByteArray? {
        if (!isInitialized) {
            Log.e(TAG, "Decryption failed: AESCrypto has not been initialized.")
            return null
        }

        if (encryptedBytes.size != 16) {
            Log.w(TAG, "Decryption failed: encrypted payload must be 16 bytes, got ${encryptedBytes.size}")
            return null
        }

        // Arduino sends an initial "empty" pressure packet with 16 zero bytes (not encrypted). Skip decryption.
        if (encryptedBytes.all { it == 0.toByte() }) {
            return ByteArray(12) { 0 }
        }

        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, pressureKey, pressureIV)
            val decryptedBytes = cipher.doFinal(encryptedBytes)
            // doFinal() with PKCS5Padding returns unpadded plaintext (12 bytes), not 16
            if (decryptedBytes.size == 12) decryptedBytes else null
        } catch (e: Exception) {
            Log.e(TAG, "Decryption failed with exception: ${e.message}", e)
            null
        }
    }
}
