package com.revertron.mimir.sec

import android.util.Log
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.engines.ChaCha7539Engine
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.macs.Poly1305
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.util.encoders.Hex
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.SecureRandom

/**
 * Cryptographic utilities for group chat encryption.
 *
 * - Shared keys: 32-byte random keys generated for each chat
 * - Key exchange: ECIES-like encryption using Ed25519→X25519 conversion
 * - Message encryption: ChaCha20-Poly1305 AEAD
 */
object GroupChatCrypto {
    private const val TAG = "GroupChatCrypto"
    private val secureRandom = SecureRandom()

    // Nonce size for ChaCha20-Poly1305
    private const val NONCE_SIZE = 12 // 96 bits
    private const val MAC_SIZE = 16 // 128 bits Poly1305 tag

    /**
     * Generates a random 32-byte shared key for a new chat.
     */
    fun generateSharedKey(): ByteArray {
        return ByteArray(32).also { secureRandom.nextBytes(it) }
    }

    /**
     * Encrypts the shared key for a recipient using ECIES-like scheme with Ed25519 keys.
     *
     * Algorithm:
     * 1. Generate ephemeral X25519 keypair
     * 2. Perform X25519 agreement with recipient's public key (converted from Ed25519)
     * 3. Derive encryption key using HKDF-SHA256
     * 4. Encrypt shared key using ChaCha20-Poly1305
     * 5. Return: [ephemeral_pubkey(32)][nonce(12)][ciphertext][tag(16)]
     */
    fun encryptSharedKey(sharedKey: ByteArray, recipientEd25519Pubkey: ByteArray): ByteArray {
        require(sharedKey.size == 32) { "Shared key must be 32 bytes" }
        require(recipientEd25519Pubkey.size == 32) { "Recipient pubkey must be 32 bytes" }

        try {
            // Convert recipient's Ed25519 public key to X25519
            val recipientX25519Pubkey = convertEd25519PublicToX25519(recipientEd25519Pubkey)

            // Generate ephemeral X25519 keypair
            val ephemeralPrivate = X25519PrivateKeyParameters(secureRandom)
            val ephemeralPublic = ephemeralPrivate.generatePublicKey()

            // Perform X25519 key agreement
            val agreement = X25519Agreement()
            agreement.init(ephemeralPrivate)
            val sharedSecret = ByteArray(32)
            agreement.calculateAgreement(recipientX25519Pubkey, sharedSecret, 0)

            // Derive encryption key using HKDF
            val encryptionKey = deriveKey(sharedSecret, "group-invite-key".toByteArray(Charsets.UTF_8), 32)

            // Generate random nonce
            val nonce = ByteArray(NONCE_SIZE)
            secureRandom.nextBytes(nonce)

            // Encrypt using ChaCha20-Poly1305
            val ciphertext = encryptChaCha20Poly1305(sharedKey, encryptionKey, nonce)

            // Return: [ephemeral_pubkey][nonce][ciphertext+tag]
            return ByteArrayOutputStream().apply {
                write(ephemeralPublic.encoded)
                write(nonce)
                write(ciphertext)
            }.toByteArray()

        } catch (e: Exception) {
            Log.e(TAG, "Error encrypting shared key", e)
            throw CryptoException("Failed to encrypt shared key", e)
        }
    }

    /**
     * Decrypts the shared key using the recipient's private key.
     *
     * @param encryptedData Format: [ephemeral_pubkey(32)][nonce(12)][ciphertext+tag]
     * @param recipientEd25519Privkey Recipient's Ed25519 private key (32 or 64 bytes)
     */
    fun decryptSharedKey(encryptedData: ByteArray, recipientEd25519Privkey: ByteArray): ByteArray {
        require(encryptedData.size >= 32 + NONCE_SIZE + MAC_SIZE) {
            "Encrypted data too short: ${encryptedData.size}"
        }

        try {
            var offset = 0

            // Extract ephemeral public key
            val ephemeralPubkeyBytes = encryptedData.copyOfRange(offset, offset + 32)
            offset += 32
            val ephemeralPubkey = X25519PublicKeyParameters(ephemeralPubkeyBytes, 0)

            // Extract nonce
            val nonce = encryptedData.copyOfRange(offset, offset + NONCE_SIZE)
            offset += NONCE_SIZE

            // Extract ciphertext + tag
            val ciphertext = encryptedData.copyOfRange(offset, encryptedData.size)

            // Convert recipient's Ed25519 private key to X25519
            val recipientX25519Private = convertEd25519PrivateToX25519(recipientEd25519Privkey)

            // Perform X25519 key agreement
            val agreement = X25519Agreement()
            agreement.init(recipientX25519Private)
            val sharedSecret = ByteArray(32)
            agreement.calculateAgreement(ephemeralPubkey, sharedSecret, 0)

            // Derive decryption key using HKDF
            val decryptionKey = deriveKey(sharedSecret, "group-invite-key".toByteArray(Charsets.UTF_8), 32)

            // Decrypt using ChaCha20-Poly1305
            return decryptChaCha20Poly1305(ciphertext, decryptionKey, nonce)

        } catch (e: Exception) {
            Log.e(TAG, "Error decrypting shared key", e)
            throw CryptoException("Failed to decrypt shared key", e)
        }
    }

    /**
     * Encrypts a message using ChaCha20-Poly1305 with the shared key.
     *
     * @return [nonce(12)][ciphertext+tag(16)]
     */
    fun encryptMessage(plaintext: ByteArray, sharedKey: ByteArray): ByteArray {
        require(sharedKey.size == 32) { "Shared key must be 32 bytes" }

        val nonce = ByteArray(NONCE_SIZE)
        secureRandom.nextBytes(nonce)

        val ciphertext = encryptChaCha20Poly1305(plaintext, sharedKey, nonce)

        return ByteArrayOutputStream().apply {
            write(nonce)
            write(ciphertext)
        }.toByteArray()
    }

    /**
     * Decrypts a message using ChaCha20-Poly1305 with the shared key.
     *
     * @param encryptedData Format: [nonce(12)][ciphertext+tag(16)]
     */
    fun decryptMessage(encryptedData: ByteArray, sharedKey: ByteArray): ByteArray {
        require(sharedKey.size == 32) { "Shared key must be 32 bytes" }
        require(encryptedData.size >= NONCE_SIZE + MAC_SIZE) {
            "Encrypted data too short: ${encryptedData.size}"
        }

        val nonce = encryptedData.copyOfRange(0, NONCE_SIZE)
        val ciphertext = encryptedData.copyOfRange(NONCE_SIZE, encryptedData.size)

        return decryptChaCha20Poly1305(ciphertext, sharedKey, nonce)
    }

    // === Internal helpers ===

    /**
     * Converts Ed25519 public key to X25519 (Curve25519) public key.
     * Uses the standard conversion algorithm.
     */
    private fun convertEd25519PublicToX25519(ed25519Pubkey: ByteArray): X25519PublicKeyParameters {
        // RFC 8032 Section 5.1.3: Ed25519 public key to X25519 conversion
        // Extract the u-coordinate from the Edwards curve y-coordinate
        require(ed25519Pubkey.size == 32) { "Ed25519 public key must be 32 bytes" }

        // The Ed25519 public key is the encoded point on Edwards curve
        // We need to recover the Montgomery u-coordinate: u = (1+y) / (1-y) mod p
        // where p = 2^255 - 19
        val p = BigInteger("57896044618658097711785492504343953926634992332820282019728792003956564819949")
        val y_bytes = ed25519Pubkey

        // Decode y (little-endian, with sign bit)
        var y = BigInteger(1, y_bytes.reversedArray())

        // Compute u = (1 + y) / (1 - y) mod p
        val one = BigInteger.ONE
        val numerator = (one + y) % p
        val denominator = (one - y) % p
        val denom_inv = denominator.modInverse(p)
        val u = (numerator * denom_inv) % p

        // Encode u as little-endian 32 bytes
        val u_raw = u.toByteArray()
        val u_bytes = ByteArray(32)
        // Pad on the left with zeros if needed, or take last 32 bytes if too long
        val copyLen = minOf(32, u_raw.size)
        System.arraycopy(u_raw, maxOf(0, u_raw.size - 32), u_bytes, 32 - copyLen, copyLen)
        return X25519PublicKeyParameters(u_bytes.reversedArray(), 0)
    }

    /**
     * Converts Ed25519 private key to X25519 (Curve25519) private key.
     * According to RFC 8032 Section 5.1.5
     */
    private fun convertEd25519PrivateToX25519(ed25519Privkey: ByteArray): X25519PrivateKeyParameters {
        // Ed25519 private keys are either 32 bytes (seed) or 64 bytes (seed+public)
        val seed = if (ed25519Privkey.size == 32) {
            ed25519Privkey
        } else {
            ed25519Privkey.copyOfRange(0, 32)
        }

        // RFC 8032: hash the seed with SHA-512 and use first 32 bytes
        val digest = org.bouncycastle.crypto.digests.SHA512Digest()
        val hash = ByteArray(64)
        digest.update(seed, 0, seed.size)
        digest.doFinal(hash, 0)

        // Apply clamping for X25519 (RFC 7748)
        hash[0] = (hash[0].toInt() and 0xF8).toByte()
        hash[31] = (hash[31].toInt() and 0x7F).toByte()
        hash[31] = (hash[31].toInt() or 0x40).toByte()

        // Use first 32 bytes as X25519 private key
        val x25519Seed = hash.copyOfRange(0, 32)
        return X25519PrivateKeyParameters(x25519Seed, 0)
    }

    /**
     * Derives a key using HKDF-SHA256.
     */
    private fun deriveKey(inputKeyMaterial: ByteArray, info: ByteArray, outputLength: Int): ByteArray {
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(inputKeyMaterial, null, info))
        val output = ByteArray(outputLength)
        hkdf.generateBytes(output, 0, outputLength)
        return output
    }

    /**
     * Encrypts data using ChaCha20-Poly1305 AEAD.
     * @return ciphertext + MAC tag (16 bytes)
     */
    private fun encryptChaCha20Poly1305(plaintext: ByteArray, key: ByteArray, nonce: ByteArray): ByteArray {
        require(key.size == 32) { "Key must be 32 bytes" }
        require(nonce.size == NONCE_SIZE) { "Nonce must be $NONCE_SIZE bytes" }

        // According to RFC 8439, the Poly1305 key is derived from the first 32 bytes
        // of the ChaCha20 keystream (counter=0), BEFORE encrypting the plaintext.

        // Step 1: Generate Poly1305 key from counter=0
        val polyKeyGen = ChaCha7539Engine()
        polyKeyGen.init(true, ParametersWithIV(KeyParameter(key), nonce))
        val polyKey = ByteArray(32)
        polyKeyGen.processBytes(ByteArray(32), 0, 32, polyKey, 0)  // counter 0 → 1

        // Step 2: Encrypt plaintext using counter=1+
        val cipher = ChaCha7539Engine()
        cipher.init(true, ParametersWithIV(KeyParameter(key), nonce))
        cipher.processBytes(ByteArray(32), 0, 32, ByteArray(32), 0)  // Skip counter 0 (used for polyKey)

        val ciphertext = ByteArray(plaintext.size)
        cipher.processBytes(plaintext, 0, plaintext.size, ciphertext, 0)

        // Step 3: Compute Poly1305 MAC
        val poly = Poly1305()
        poly.init(KeyParameter(polyKey))
        poly.update(ciphertext, 0, ciphertext.size)

        val tag = ByteArray(MAC_SIZE)
        poly.doFinal(tag, 0)

        // Return ciphertext + tag
        return ciphertext + tag
    }

    /**
     * Decrypts data using ChaCha20-Poly1305 AEAD.
     * @param ciphertextWithTag ciphertext + MAC tag (16 bytes)
     */
    private fun decryptChaCha20Poly1305(ciphertextWithTag: ByteArray, key: ByteArray, nonce: ByteArray): ByteArray {
        require(key.size == 32) { "Key must be 32 bytes" }
        require(nonce.size == NONCE_SIZE) { "Nonce must be $NONCE_SIZE bytes" }
        require(ciphertextWithTag.size >= MAC_SIZE) { "Ciphertext too short" }

        val ciphertext = ciphertextWithTag.copyOfRange(0, ciphertextWithTag.size - MAC_SIZE)
        val receivedTag = ciphertextWithTag.copyOfRange(ciphertextWithTag.size - MAC_SIZE, ciphertextWithTag.size)

        // According to RFC 8439, the Poly1305 key is derived from counter=0,
        // and the ciphertext is encrypted starting from counter=1.

        // Step 1: Generate Poly1305 key from counter=0
        val polyKey = ByteArray(32)
        val polyKeyGen = ChaCha7539Engine()
        polyKeyGen.init(true, ParametersWithIV(KeyParameter(key), nonce))
        polyKeyGen.processBytes(ByteArray(32), 0, 32, polyKey, 0)  // counter 0 → 1

        // Step 2: Verify MAC before decrypting
        val poly = Poly1305()
        poly.init(KeyParameter(polyKey))
        poly.update(ciphertext, 0, ciphertext.size)

        val computedTag = ByteArray(MAC_SIZE)
        poly.doFinal(computedTag, 0)

        // Verify MAC tag (constant-time comparison)
        if (!computedTag.contentEquals(receivedTag)) {
            throw CryptoException("MAC verification failed - message corrupted or tampered")
        }

        // Step 3: Decrypt using counter=1+ (skip counter=0 which was used for polyKey)
        val cipher = ChaCha7539Engine()
        cipher.init(false, ParametersWithIV(KeyParameter(key), nonce))
        cipher.processBytes(ByteArray(32), 0, 32, ByteArray(32), 0)  // Skip counter 0 (used for polyKey)

        val plaintext = ByteArray(ciphertext.size)
        cipher.processBytes(ciphertext, 0, ciphertext.size, plaintext, 0)

        return plaintext
    }

    class CryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)
}