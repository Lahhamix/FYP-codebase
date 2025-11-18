package com.example.ble_viewer

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object AESCrypto {

    // ⚙️ Must match the Arduino's AES key (16 bytes)
    private val aesKey = byteArrayOf(
        0x53, 0x69, 0x6D, 0x70, 0x6C, 0x65, 0x4B, 0x65,
        0x79, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37
    )

    // ⚙️ Must match the Arduino's IV (16 bytes)
    private val aesIV = byteArrayOf(
        0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
        0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F
    )

    // 🔓 AES Decryption Function
    fun decryptBase64AES(base64Input: String): String {
        return try {
            val decodedBytes = Base64.decode(base64Input, Base64.DEFAULT)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val keySpec = SecretKeySpec(aesKey, "AES")
            val ivSpec = IvParameterSpec(aesIV)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            val decryptedBytes = cipher.doFinal(decodedBytes)
            String(decryptedBytes).trim()
        } catch (e: Exception) {
            e.printStackTrace()
            "DECRYPT_ERROR"
        }
    }
}
