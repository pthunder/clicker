package dev.clicker.remote.appletv

import java.io.ByteArrayOutputStream

/** TLV8 encoding used by HomeKit-style pairing (HAP). Values over 255 bytes are split into chunks. */
object Tlv8 {
    const val METHOD = 0x00
    const val IDENTIFIER = 0x01
    const val SALT = 0x02
    const val PUBLIC_KEY = 0x03
    const val PROOF = 0x04
    const val ENCRYPTED_DATA = 0x05
    const val SEQ_NO = 0x06
    const val ERROR = 0x07
    const val BACK_OFF = 0x08
    const val SIGNATURE = 0x0A
    const val NAME = 0x11

    fun write(vararg items: Pair<Int, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((tag, value) in items) {
            if (value.isEmpty()) {
                out.write(tag); out.write(0)
                continue
            }
            var pos = 0
            while (pos < value.size) {
                val len = minOf(255, value.size - pos)
                out.write(tag)
                out.write(len)
                out.write(value, pos, len)
                pos += len
            }
        }
        return out.toByteArray()
    }

    fun read(data: ByteArray): Map<Int, ByteArray> {
        val result = LinkedHashMap<Int, ByteArray>()
        var pos = 0
        while (pos + 1 < data.size) {
            val tag = data[pos].toInt() and 0xFF
            val len = data[pos + 1].toInt() and 0xFF
            val value = data.copyOfRange(pos + 2, pos + 2 + len)
            result[tag] = result[tag]?.plus(value) ?: value
            pos += 2 + len
        }
        return result
    }

    fun describeError(tlv: Map<Int, ByteArray>): String? {
        val error = tlv[ERROR] ?: return null
        val code = error.firstOrNull()?.toInt() ?: -1
        return when (code) {
            1 -> "unknown error"
            2 -> "wrong PIN or authentication failed"
            3 -> "too many attempts, wait and try again"
            4 -> "too many paired devices"
            5 -> "pairing limit reached"
            6 -> "Apple TV is busy pairing with something else"
            else -> "pairing error $code"
        }
    }
}
