package dev.clicker.remote.appletv

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom

/** The crypto building blocks of HAP pairing: HKDF-SHA512, ChaCha20-Poly1305, Ed25519 and X25519. */
object HapCrypto {
    private val random = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    fun hkdfSha512(secret: ByteArray, salt: String, info: String, length: Int = 32): ByteArray {
        val gen = HKDFBytesGenerator(SHA512Digest())
        gen.init(HKDFParameters(secret, salt.toByteArray(), info.toByteArray()))
        return ByteArray(length).also { gen.generateBytes(it, 0, length) }
    }

    /** 12-byte nonce: four zero bytes followed by an 8-byte label such as "PS-Msg05". */
    fun labelNonce(label: String): ByteArray {
        val raw = label.toByteArray()
        require(raw.size <= 12)
        return ByteArray(12 - raw.size) + raw
    }

    /** 12-byte little-endian counter nonce, as used on the encrypted Companion channel. */
    fun counterNonce(counter: Long): ByteArray =
        ByteArray(12) { i -> if (i < 8) ((counter ushr (8 * i)) and 0xFF).toByte() else 0 }

    fun seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray? = null): ByteArray =
        chacha(true, key, nonce, plaintext, aad)

    fun open(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray? = null): ByteArray =
        chacha(false, key, nonce, ciphertext, aad)

    private fun chacha(encrypt: Boolean, key: ByteArray, nonce: ByteArray, input: ByteArray, aad: ByteArray?): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(encrypt, AEADParameters(KeyParameter(key), 128, nonce, aad))
        val out = ByteArray(cipher.getOutputSize(input.size))
        val n = cipher.processBytes(input, 0, input.size, out, 0)
        val total = n + cipher.doFinal(out, n)
        return if (total == out.size) out else out.copyOf(total)
    }

    class Ed25519KeyPair(val seed: ByteArray) {
        private val private = Ed25519PrivateKeyParameters(seed, 0)
        val publicKey: ByteArray = private.generatePublicKey().encoded

        fun sign(message: ByteArray): ByteArray {
            val signer = Ed25519Signer()
            signer.init(true, private)
            signer.update(message, 0, message.size)
            return signer.generateSignature()
        }

        companion object {
            fun generate() = Ed25519KeyPair(randomBytes(32))
        }
    }

    fun ed25519Verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        val verifier = Ed25519Signer()
        verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
        verifier.update(message, 0, message.size)
        return verifier.verifySignature(signature)
    }

    class X25519KeyPair {
        private val private = X25519PrivateKeyParameters(random)
        val publicKey: ByteArray = private.generatePublicKey().encoded

        fun agree(peerPublic: ByteArray): ByteArray {
            val agreement = X25519Agreement()
            agreement.init(private)
            return ByteArray(agreement.agreementSize).also {
                agreement.calculateAgreement(X25519PublicKeyParameters(peerPublic, 0), it, 0)
            }
        }
    }
}
