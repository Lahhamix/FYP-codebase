package com.example.ble_viewer

import android.util.Base64
import android.util.Log
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object AESCrypto {

    private const val TAG = "AESCrypto"

    // Separate keys for each data type (must match Arduino)
    private val accelKey = byteArrayOf(
        0x41.toByte(), 0x63.toByte(), 0x63.toByte(), 0x65.toByte(),
        0x6C.toByte(), 0x4B.toByte(), 0x65.toByte(), 0x79.toByte(),
        0x31.toByte(), 0x32.toByte(), 0x33.toByte(), 0x34.toByte(),
        0x35.toByte(), 0x36.toByte(), 0x37.toByte(), 0x38.toByte()
    )

    private val gyroKey = byteArrayOf(
        0x47.toByte(), 0x79.toByte(), 0x72.toByte(), 0x6F.toByte(),
        0x4B.toByte(), 0x65.toByte(), 0x79.toByte(), 0x31.toByte(),
        0x32.toByte(), 0x33.toByte(), 0x34.toByte(), 0x35.toByte(),
        0x36.toByte(), 0x37.toByte(), 0x38.toByte(), 0x39.toByte()
    )

    private val heartRateKey = byteArrayOf(
        0x48.toByte(), 0x65.toByte(), 0x61.toByte(), 0x72.toByte(),
        0x74.toByte(), 0x52.toByte(), 0x61.toByte(), 0x74.toByte(),
        0x65.toByte(), 0x4B.toByte(), 0x65.toByte(), 0x79.toByte(),
        0x31.toByte(), 0x32.toByte(), 0x33.toByte(), 0x34.toByte()
    )

    private val spo2Key = byteArrayOf(
        0x53.toByte(), 0x70.toByte(), 0x4F.toByte(), 0x32.toByte(),
        0x4B.toByte(), 0x65.toByte(), 0x79.toByte(), 0x31.toByte(),
        0x32.toByte(), 0x33.toByte(), 0x34.toByte(), 0x35.toByte(),
        0x36.toByte(), 0x37.toByte(), 0x38.toByte(), 0x39.toByte()
    )

    // Separate IVs for each data type (must match Arduino)
    private val accelIV = byteArrayOf(
        0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
        0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F
    )

    private val gyroIV = byteArrayOf(
        0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17,
        0x18, 0x19, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E, 0x1F
    )

    private val heartRateIV = byteArrayOf(
        0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27,
        0x28, 0x29, 0x2A, 0x2B, 0x2C, 0x2D, 0x2E, 0x2F
    )

    private val spo2IV = byteArrayOf(
        0x30, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37,
        0x38, 0x39, 0x3A, 0x3B, 0x3C, 0x3D, 0x3E, 0x3F
    )

    // Default key/IV for backward compatibility
    private val aesKey = byteArrayOf(
        0x53.toByte(), 0x69.toByte(), 0x6D.toByte(), 0x70.toByte(),
        0x6C.toByte(), 0x65.toByte(), 0x4B.toByte(), 0x65.toByte(),
        0x79.toByte(), 0x31.toByte(), 0x32.toByte(), 0x33.toByte(),
        0x34.toByte(), 0x35.toByte(), 0x36.toByte(), 0x37.toByte()
    )

    private val aesIV = byteArrayOf(
        0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
        0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F
    )

    /**
     * 🔓 Helper function to decrypt with specific key and IV
     */
    private fun decryptWithKeyIV(base64Input: String, key: ByteArray, iv: ByteArray): String {
        return try {
            // Validate key and IV
            if (key.size != 16) {
                Log.e(TAG, "Invalid key size: ${key.size}, expected 16")
                return "DECRYPT_ERROR"
            }
            if (iv.size != 16) {
                Log.e(TAG, "Invalid IV size: ${iv.size}, expected 16")
                return "DECRYPT_ERROR"
            }
            
            // Step 1: Clean the input
            val cleanedInput = base64Input.trim()
                .filter { !it.isWhitespace() && it != '\u0000' }
                .filter { it in "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=" }
            
            if (cleanedInput.isEmpty()) {
                Log.e(TAG, "Empty input after cleaning")
                return "DECRYPT_ERROR"
            }
            
            // Validate Base64 format: must be multiple of 4 characters
            val base64Length = cleanedInput.length
            if (base64Length % 4 != 0) {
                Log.e(TAG, "Invalid Base64 length: $base64Length (not multiple of 4)")
                return "DECRYPT_ERROR"
            }
            
            // Validate minimum length (at least 16 bytes encrypted = 24 base64 chars)
            if (base64Length < 24) {
                Log.w(TAG, "Base64 too short: $base64Length chars (minimum 24)")
                return "DECRYPT_ERROR"
            }
            
            // Step 2: Decode Base64 to bytes
            val decodedBytes = try {
                Base64.decode(cleanedInput, Base64.DEFAULT)
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "Base64 decode failed: ${e.message}")
                return "DECRYPT_ERROR"
            }
            
            if (decodedBytes.isEmpty()) {
                Log.e(TAG, "Decoded bytes are empty")
                return "DECRYPT_ERROR"
            }
            
            // Validate that decoded bytes are multiple of 16 (AES block size)
            if (decodedBytes.size % 16 != 0) {
                Log.w(TAG, "Invalid encrypted data size: ${decodedBytes.size} (not multiple of 16)")
                return "DECRYPT_ERROR"
            }
            
            // Step 3: Decrypt using AES-128-CBC with PKCS5Padding
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val keySpec = SecretKeySpec(key, "AES")
            val ivSpec = IvParameterSpec(iv)
            
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            
            // Step 4: Perform decryption
            val decryptedBytes = try {
                cipher.doFinal(decodedBytes)
            } catch (e: javax.crypto.BadPaddingException) {
                Log.e(TAG, "Bad padding - likely wrong key/IV or corrupted data: ${e.message}")
                return "DECRYPT_ERROR"
            } catch (e: javax.crypto.IllegalBlockSizeException) {
                Log.e(TAG, "Illegal block size: ${e.message}")
                return "DECRYPT_ERROR"
            }
            
            // Step 5: Convert decrypted bytes to UTF-8 string
            val result = String(decryptedBytes, Charsets.UTF_8)
            val trimmedResult = result.trim()
            
            // Validate that result contains only printable characters
            if (!trimmedResult.all { it.isLetterOrDigit() || it in ".,;:!? -+()[]{}/" }) {
                Log.w(TAG, "Decrypted data contains non-printable characters")
                // Still return it but warn
            }
            
            Log.d(TAG, "✅ Decryption successful: $trimmedResult")
            trimmedResult
            
        } catch (e: Exception) {
            Log.e(TAG, "Decryption failed with exception: ${e.message}", e)
            "DECRYPT_ERROR"
        }
    }

    /**
     * 🔓 Default AES Decryption Function (backward compatibility)
     */
    fun decryptBase64AES(base64Input: String): String {
        return decryptWithKeyIV(base64Input, aesKey, aesIV)
    }

    /**
     * 🔓 Separate decryption functions for each data type
     */
    fun decryptAccel(base64Input: String): String {
        return decryptWithKeyIV(base64Input, accelKey, accelIV)
    }

    fun decryptGyro(base64Input: String): String {
        return decryptWithKeyIV(base64Input, gyroKey, gyroIV)
    }

    fun decryptHeartRate(base64Input: String): String {
        return decryptWithKeyIV(base64Input, heartRateKey, heartRateIV)
    }

    fun decryptSpO2(base64Input: String): String {
        return decryptWithKeyIV(base64Input, spo2Key, spo2IV)
    }

    /**
     * Test function to verify encryption/decryption keys match between Arduino and Android
     */
    fun testKeysMatch() {
        Log.d(TAG, "=== Testing Encryption Keys ===")
        
        // Test vectors that should match Arduino
        val testVectors = mapOf(
            "Accel" to Triple("1.0,2.0,3.0", accelKey, accelIV),
            "Gyro" to Triple("4.0,5.0,6.0", gyroKey, gyroIV),
            "HeartRate" to Triple("72.0,70,69,71", heartRateKey, heartRateIV),
            "SpO2" to Triple("98.5,98.2", spo2Key, spo2IV)
        )
        
        for ((name, data) in testVectors) {
            val (plaintext, key, iv) = data
            Log.d(TAG, "Testing $name:")
            Log.d(TAG, "  Key: ${key.joinToString(",") { "0x%02X".format(it) }}")
            Log.d(TAG, "  IV:  ${iv.joinToString(",") { "0x%02X".format(it) }}")
            Log.d(TAG, "  Plaintext: $plaintext")
        }
        
        Log.d(TAG, "=== Key Test Complete ===")
    }
}
