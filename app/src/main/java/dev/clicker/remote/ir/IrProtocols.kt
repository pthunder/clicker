package dev.clicker.remote.ir

/** A ready-to-send IR signal: carrier frequency and alternating on/off durations in microseconds. */
data class IrSignal(val frequencyHz: Int, val pattern: IntArray) {
    val durationUs: Int get() = pattern.sum()
}

/**
 * NEC protocol (LG TVs and many others).
 * Frame: 9 ms mark, 4.5 ms space, 32 bits LSB first (address, ~address, command, ~command), stop mark.
 * Bit: 560 us mark, then 560 us (0) or 1690 us (1) space.
 */
object Nec {
    fun encode(address: Int, command: Int, repeats: Int = 0): IrSignal {
        val bytes = if (address > 0xFF) {
            // Extended NEC: 16-bit address, low byte first
            intArrayOf(address and 0xFF, (address shr 8) and 0xFF, command and 0xFF, command.inv() and 0xFF)
        } else {
            intArrayOf(address and 0xFF, address.inv() and 0xFF, command and 0xFF, command.inv() and 0xFF)
        }
        val p = mutableListOf(9000, 4500)
        for (b in bytes) for (bit in 0 until 8) {
            p += 560
            p += if ((b shr bit) and 1 == 1) 1690 else 560
        }
        p += 560
        // Pad the frame to the standard 108 ms, then add NEC repeat codes if asked.
        repeat(repeats) {
            p += 108_000 - p.sum() % 108_000
            p += listOf(9000, 2250, 560)
        }
        return IrSignal(38_000, p.toIntArray())
    }
}

/**
 * Kaseikyo 48-bit protocol (Panasonic, Denon and others).
 *
 * Uses the same address/command layout as Flipper Zero and Flipper-IRDB files: the 32-bit
 * address packs id (bits 24-25), vendor id (8-23), genre1 (4-7) and genre2 (0-3); the command
 * is 10 bits. Timing unit 432 us. Layout reference: Flipper Zero firmware (GPL-3.0), reimplemented.
 */
object Kaseikyo {
    private const val UNIT = 432

    fun encode(address: Long, command: Int, frames: Int = 2): IrSignal {
        val id = ((address shr 24) and 3).toInt()
        val vendor = ((address shr 8) and 0xFFFF).toInt()
        val genre1 = ((address shr 4) and 0xF).toInt()
        val genre2 = (address and 0xF).toInt()

        val d = IntArray(6)
        d[0] = vendor and 0xFF
        d[1] = (vendor shr 8) and 0xFF
        var parity = d[0] xor d[1]
        parity = (parity and 0xF) xor (parity shr 4)
        d[2] = (parity and 0xF) or (genre1 shl 4)
        d[3] = (genre2 and 0xF) or ((command and 0xF) shl 4)
        d[4] = (id shl 6) or ((command shr 4) and 0x3F)
        d[5] = d[2] xor d[3] xor d[4]

        val p = mutableListOf<Int>()
        repeat(frames) { frame ->
            if (frame > 0) p += 130_000 - p.sum() % 130_000 // gap: frames repeat every 130 ms
            p += 8 * UNIT
            p += 4 * UNIT
            for (b in d) for (bit in 0 until 8) {
                p += UNIT
                p += if ((b shr bit) and 1 == 1) 3 * UNIT else UNIT
            }
            p += UNIT
        }
        return IrSignal(38_000, p.toIntArray())
    }

    /** Parses Flipper-style little-endian hex bytes, e.g. "52 54 32 00" -> 0x00325452. */
    fun flipperHex(text: String): Long =
        text.trim().split(Regex("\\s+")).reversed().fold(0L) { acc, b -> (acc shl 8) or b.toLong(16) }
}
