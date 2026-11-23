package dev.clicker.remote.ir

import org.junit.Assert.assertEquals
import org.junit.Test

class IrProtocolsTest {

    /** Reads the bits back out of a pulse pattern (space longer than 2 marks = 1), LSB first per byte. */
    private fun bytesOf(pattern: IntArray, headerPulses: Int, bitCount: Int, mark: Int): IntArray {
        val bytes = IntArray(bitCount / 8)
        for (i in 0 until bitCount) {
            val space = pattern[headerPulses + 2 * i + 1]
            if (space > 2 * mark) bytes[i / 8] = bytes[i / 8] or (1 shl (i % 8))
        }
        return bytes
    }

    @Test
    fun necLgPower() {
        val signal = Nec.encode(0x04, 0x08)
        assertEquals(38_000, signal.frequencyHz)
        assertEquals(9000, signal.pattern[0])
        assertEquals(4500, signal.pattern[1])
        assertEquals(2 + 64 + 1, signal.pattern.size)
        assertEquals(listOf(0x04, 0xFB, 0x08, 0xF7), bytesOf(signal.pattern, 2, 32, 560).toList())
    }

    @Test
    fun kaseikyoDenonPowerMatchesFlipperLayout() {
        val address = Kaseikyo.flipperHex("52 54 32 00")
        assertEquals(0x00325452L, address)
        val signal = Kaseikyo.encode(address, 0x83, frames = 1)
        assertEquals(3456, signal.pattern[0])
        assertEquals(1728, signal.pattern[1])
        assertEquals(2 + 96 + 1, signal.pattern.size)
        // vendor 0x3254 (Denon) little endian; vendor parity (0x54 xor 0x32 = 0x66, 6 xor 6) = 0 | genre1 0x5 << 4;
        // genre2 0x2 | cmd low nibble 0x3 << 4; id 0 | cmd high bits 0x08; checksum
        val d2 = 0x00 or (0x5 shl 4)
        val d3 = 0x2 or (0x3 shl 4)
        val d4 = 0x08
        assertEquals(listOf(0x54, 0x32, d2, d3, d4, d2 xor d3 xor d4), bytesOf(signal.pattern, 2, 48, 432).toList())
    }
}
