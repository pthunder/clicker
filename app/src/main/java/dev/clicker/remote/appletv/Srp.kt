package dev.clicker.remote.appletv

import java.math.BigInteger
import java.security.MessageDigest

/**
 * SRP-6a client as used by HAP pair-setup: RFC 5054 3072-bit group, generator 5, SHA-512,
 * username "Pair-Setup".
 *
 * Byte conventions match srptools (which pyatv uses): integers are hashed in their minimal
 * big-endian form, except that k and u use values padded to the length of N.
 */
class SrpClient(
    private val password: String,
    privateKey: ByteArray,
    private val username: String = "Pair-Setup",
) {
    private val a = BigInteger(1, privateKey)

    /** Client public value A = g^a mod N. */
    val publicKey: ByteArray = bytes(G.modPow(a, N))

    /** Session key K, available after [process]. */
    lateinit var sessionKey: ByteArray
        private set

    /** Client proof M1, available after [process]. */
    lateinit var proof: ByteArray
        private set

    private lateinit var expectedServerProof: ByteArray

    fun process(salt: ByteArray, serverPublic: ByteArray) {
        val bigA = BigInteger(1, publicKey)
        val bigB = BigInteger(1, serverPublic)
        require(bigB.mod(N) != BigInteger.ZERO) { "invalid server public key" }
        val k = int(sha512(bytes(N), pad(G)))
        val u = int(sha512(pad(bigA), pad(bigB)))
        // The salt is hashed exactly as received, leading zero bytes included.
        val x = int(sha512(salt, sha512("$username:$password".toByteArray())))
        val v = G.modPow(x, N)
        val base = bigB.subtract(k.multiply(v)).mod(N)
        val secret = base.modPow(a.add(u.multiply(x)), N)

        sessionKey = sha512(bytes(secret))
        val hNxorHg = int(sha512(bytes(N))).xor(int(sha512(bytes(G))))
        proof = sha512(
            bytes(hNxorHg),
            bytes(int(sha512(username.toByteArray()))),
            salt,
            bytes(bigA),
            bytes(bigB),
            sessionKey,
        )
        expectedServerProof = sha512(bytes(bigA), proof, sessionKey)
    }

    fun verifyServerProof(serverProof: ByteArray): Boolean =
        MessageDigest.isEqual(serverProof, expectedServerProof)

    companion object {
        private const val N_HEX =
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74" +
            "020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F1437" +
            "4FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
            "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF05" +
            "98DA48361C55D39A69163FA8FD24CF5F83655D23DCA3AD961C62F356208552BB" +
            "9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B" +
            "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF695581718" +
            "3995497CEA956AE515D2261898FA051015728E5A8AAAC42DAD33170D04507A33" +
            "A85521ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7" +
            "ABF5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6BF12FFA06D98A0864" +
            "D87602733EC86A64521F2B18177B200CBBE117577A615D6C770988C0BAD946E2" +
            "08E24FA074E5AB3143DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF"

        val N = BigInteger(N_HEX, 16)
        val G: BigInteger = BigInteger.valueOf(5)
        private val N_LEN = bytes(N).size

        /** Minimal unsigned big-endian bytes (zero encodes as a single 0 byte). */
        fun bytes(value: BigInteger): ByteArray {
            val raw = value.toByteArray()
            return if (raw.size > 1 && raw[0].toInt() == 0) raw.copyOfRange(1, raw.size) else raw
        }

        private fun pad(value: BigInteger): ByteArray {
            val raw = bytes(value)
            return ByteArray(N_LEN - raw.size) + raw
        }

        private fun int(hash: ByteArray) = BigInteger(1, hash)

        private fun sha512(vararg parts: ByteArray): ByteArray {
            val md = MessageDigest.getInstance("SHA-512")
            parts.forEach { md.update(it) }
            return md.digest()
        }
    }
}
