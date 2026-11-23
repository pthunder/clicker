package dev.clicker.remote.appletv

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingCryptoTest {

    @Test
    fun srpMatchesSrptools() {
        val client = SrpClient("1234", ReferenceVectors.APRIV.hexToBytes())
        assertEquals(ReferenceVectors.A, client.publicKey.toHex())
        // salt starts with a zero byte, which must be kept
        client.process(ReferenceVectors.SALT.hexToBytes(), ReferenceVectors.B.hexToBytes())
        assertEquals(ReferenceVectors.K, client.sessionKey.toHex())
        assertEquals(ReferenceVectors.M1, client.proof.toHex())
        assertTrue(client.verifyServerProof(ReferenceVectors.M2.hexToBytes()))
        assertFalse(client.verifyServerProof(ByteArray(64)))
    }

    @Test
    fun wrongPinGivesDifferentProof() {
        val client = SrpClient("4321", ReferenceVectors.APRIV.hexToBytes())
        client.process(ReferenceVectors.SALT.hexToBytes(), ReferenceVectors.B.hexToBytes())
        assertFalse(client.proof.toHex() == ReferenceVectors.M1)
    }

    @Test
    fun tlvSplitsAndJoinsLongValues() {
        val long = ByteArray(600) { (it % 251).toByte() }
        val encoded = Tlv8.write(Tlv8.SEQ_NO to byteArrayOf(3), Tlv8.PUBLIC_KEY to long)
        assertEquals(3 + 600 + 3 * 2, encoded.size)
        val decoded = Tlv8.read(encoded)
        assertArrayEquals(byteArrayOf(3), decoded[Tlv8.SEQ_NO])
        assertArrayEquals(long, decoded[Tlv8.PUBLIC_KEY])
    }

    @Test
    fun chachaRoundTripAndNonces() {
        val key = ByteArray(32) { it.toByte() }
        val sealed = HapCrypto.seal(key, HapCrypto.labelNonce("PS-Msg05"), "hello".toByteArray(), byteArrayOf(8, 0, 0, 21))
        assertEquals(5 + 16, sealed.size)
        assertArrayEquals("hello".toByteArray(), HapCrypto.open(key, HapCrypto.labelNonce("PS-Msg05"), sealed, byteArrayOf(8, 0, 0, 21)))
        assertEquals("000000005053", HapCrypto.labelNonce("PS-Msg05").toHex().take(12))
        assertEquals("010000000000000000000000", HapCrypto.counterNonce(1).toHex())
    }

    @Test
    fun ed25519AndX25519() {
        val pair = HapCrypto.Ed25519KeyPair.generate()
        val sig = pair.sign("abc".toByteArray())
        assertTrue(HapCrypto.ed25519Verify(pair.publicKey, "abc".toByteArray(), sig))
        assertFalse(HapCrypto.ed25519Verify(pair.publicKey, "abd".toByteArray(), sig))
        val a = HapCrypto.X25519KeyPair()
        val b = HapCrypto.X25519KeyPair()
        assertArrayEquals(a.agree(b.publicKey), b.agree(a.publicKey))
    }

    @Test
    fun credentialsRoundTrip() {
        val creds = CompanionCredentials(ByteArray(32) { 1 }, ByteArray(32) { 2 }, "ATV".toByteArray(), "me".toByteArray())
        val parsed = CompanionCredentials.parse(creds.serialize())!!
        assertArrayEquals(creds.ltpk, parsed.ltpk)
        assertArrayEquals(creds.clientId, parsed.clientId)
    }
}
