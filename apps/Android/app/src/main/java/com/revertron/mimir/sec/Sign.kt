package com.revertron.mimir.sec

import android.util.Log
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.CipherParameters
import org.bouncycastle.crypto.KeyGenerationParameters
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.*


class Sign {

    companion object {
        private const val TAG = "Sec"

        private val RANDOM = SecureRandom()

        // Generate private and public keys
        fun generateKeypair(): AsymmetricCipherKeyPair? {
            val gen = Ed25519KeyPairGenerator()
            gen.init(KeyGenerationParameters(RANDOM, 256))
            return gen.generateKeyPair()
        }

        // Sign message using private key
        fun sign(key: CipherParameters, message: ByteArray): ByteArray? {
            val signer = Ed25519Signer()
            signer.init(true, key)
            signer.update(message, 0, message.size)
            return signer.generateSignature()
        }

        // Verify message using public key
        fun verify(key: CipherParameters, message: ByteArray, signature: ByteArray): Boolean {
            val verifier = Ed25519Signer()
            verifier.init(false, key)
            verifier.update(message, 0, message.size)
            return verifier.verifySignature(signature)
        }

        fun debug() {
            val gen = Ed25519KeyPairGenerator()
            gen.init(KeyGenerationParameters(RANDOM, 256))
            val pair = gen.generateKeyPair()

            val message = "Some text".toByteArray()
            // Sign
            val signer = Ed25519Signer()
            signer.init(true, pair.private)
            signer.update(message, 0, message.size)
            val signature: ByteArray = signer.generateSignature()

            // Verify
            val verifier = Ed25519Signer()
            verifier.init(false, pair.public)
            verifier.update(message, 0, message.size)
            val verified: Boolean = verifier.verifySignature(signature)

            Log.i(TAG, "Signature is verified: $verified")
        }
    }
}