package dev.clicker.remote.appletv

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class OpackTest {

    @Test
    fun decodesPyatvOutputIncludingBackReferences() {
        val decoded = Opack.unpackMap(ReferenceVectors.OPACK.hexToBytes())
        assertEquals("_systemInfo", decoded["_i"])
        assertEquals(2L, decoded["_t"])
        assertEquals(54321L, decoded["_x"])
        val c = decoded["_c"] as Map<*, *>
        assertEquals(0L, c["_bf"])
        assertEquals(512L, c["_cf"])
        assertEquals(128L, c["_clFl"])
        assertEquals("a1b2c3d4e5f6", c["_i"]) // encoded as a back-reference to the earlier "_i"
        assertArrayEquals("0123456789abcdef0123456789abcdef0123".toByteArray(), c["_idsID"] as ByteArray)
        assertEquals("170.18", c["_sv"])
        assertEquals(4000000000L, c["_sid"])
        assertEquals(1000.0, c["f"])
        assertEquals(true, c["t"])
        assertEquals(null, c["n"])
        assertEquals("x".repeat(40), c["long"])
        val list = c["list"] as List<*>
        assertEquals(1L, list[0])
        assertEquals("two", list[1])
        assertArrayEquals(byteArrayOf(3), list[2] as ByteArray)
    }

    @Test
    fun roundTrips() {
        val message = mapOf(
            "_i" to "_hidC", "_t" to 2, "_x" to 70000L,
            "_c" to mapOf("_hBtS" to 1, "_hidC" to 6, "w" to 1000.0, "big" to ByteArray(300) { it.toByte() }),
            "many" to (0 until 20).toList(),
        )
        val decoded = Opack.unpackMap(Opack.pack(message))
        assertEquals("_hidC", decoded["_i"])
        assertEquals(70000L, decoded["_x"])
        val c = decoded["_c"] as Map<*, *>
        assertEquals(6L, c["_hidC"])
        assertEquals(1000.0, c["w"])
        assertArrayEquals(ByteArray(300) { it.toByte() }, c["big"] as ByteArray)
        assertEquals((0L until 20L).toList(), decoded["many"])
    }

    @Test
    fun encodesSmallValuesCompactly() {
        assertEquals("08", Opack.pack(0).toHex())
        assertEquals("2f", Opack.pack(39).toHex())
        assertEquals("3028", Opack.pack(40).toHex())
        assertEquals("310002", Opack.pack(512).toHex())
        assertEquals("425f69", Opack.pack("_i").toHex())
        assertEquals("e0", Opack.pack(emptyMap<String, Any>()).toHex())
    }
}
