package com.example.ble_viewer

import java.security.MessageDigest
import java.security.SecureRandom

object PasswordSecurity {

    private val secureRandom = SecureRandom()

    fun newSaltHex(byteCount: Int = 16): String {
        val bytes = ByteArray(byteCount)
        secureRandom.nextBytes(bytes)
        return bytes.toHex()
    }

    fun hashPassword(password: String, saltHex: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = "$saltHex:$password".toByteArray(Charsets.UTF_8)
        return digest.digest(input).toHex()
    }

    fun verifyPassword(enteredPassword: String, storedPassword: String?, storedSalt: String?): Boolean {
        val password = storedPassword.orEmpty()
        val salt = storedSalt.orEmpty()
        if (password.isBlank()) return false
        if (salt.isBlank()) {
            return password == enteredPassword
        }
        return hashPassword(enteredPassword, salt) == password
    }

    fun createStoredPassword(password: String): StoredPassword {
        val salt = newSaltHex()
        return StoredPassword(
            hash = hashPassword(password, salt),
            salt = salt
        )
    }

    data class StoredPassword(
        val hash: String,
        val salt: String
    )

    private fun ByteArray.toHex(): String {
        return joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
