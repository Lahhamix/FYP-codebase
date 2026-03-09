package com.example.ble_viewer

import java.math.BigInteger
import java.security.*
import java.security.spec.*
import javax.crypto.KeyAgreement

/**
 * Secure ECDH key exchange using standard Android/Java crypto.
 * This version is designed for maximum compatibility and stability.
 */
class KeyExchangeManager : AutoCloseable {

    private var privateKey: PrivateKey? = null
    private var publicKey: PublicKey? = null
    private var keyAgreement: KeyAgreement? = null

    init {
        // 1. Generate an ephemeral EC key pair for this session
        val keyPairGenerator = KeyPairGenerator.getInstance("EC")
        keyPairGenerator.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        val keyPair = keyPairGenerator.generateKeyPair()
        
        this.privateKey = keyPair.private
        this.publicKey = keyPair.public

        // 2. Initialize the KeyAgreement with our private key
        keyAgreement = KeyAgreement.getInstance("ECDH")
        keyAgreement?.init(privateKey)
    }

    /**
     * Returns our public key in uncompressed format (65 bytes: 0x04 || X || Y).
     */
    val publicKeyBytes: ByteArray
        get() {
            val pub = publicKey as? java.security.interfaces.ECPublicKey 
                ?: throw IllegalStateException("Key manager not initialized")
            
            val w = pub.w
            // BigInteger.toByteArray() can have a leading sign byte, so we must ensure exactly 32 bytes
            val x = w.affineX.toByteArray().ensureLength(32)
            val y = w.affineY.toByteArray().ensureLength(32)
            
            return byteArrayOf(0x04) + x + y
        }

    /**
     * Generates the shared secret using the peripheral's public key.
     */
    fun generateSharedSecret(peripheralPublicKeyBytes: ByteArray): ByteArray {
        val agreement = keyAgreement ?: throw IllegalStateException("Key manager closed")
        val ourPub = publicKey as? java.security.interfaces.ECPublicKey 
            ?: throw IllegalStateException("Key manager closed")

        try {
            // 1. Reconstruct the peripheral's public key from the raw bytes
            val x = BigInteger(1, peripheralPublicKeyBytes.copyOfRange(1, 33))
            val y = BigInteger(1, peripheralPublicKeyBytes.copyOfRange(33, 65))
            val point = ECPoint(x, y)
            
            val publicKeySpec = ECPublicKeySpec(point, ourPub.params)
            val keyFactory = KeyFactory.getInstance("EC")
            val peripheralPublicKey = keyFactory.generatePublic(publicKeySpec)

            // 2. Compute the shared secret
            agreement.doPhase(peripheralPublicKey, true)
            return agreement.generateSecret()
        } finally {
            // Self-destruct ephemeral material
            close()
        }
    }

    override fun close() {
        privateKey = null
        publicKey = null
        keyAgreement = null
    }

    /**
     * Standardizes byte array length for crypto coordinates.
     */
    private fun ByteArray.ensureLength(targetLen: Int): ByteArray {
        return when {
            size == targetLen -> this
            size > targetLen -> copyOfRange(size - targetLen, size)
            else -> ByteArray(targetLen - size) { 0 } + this
        }
    }
}
