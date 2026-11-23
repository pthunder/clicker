package dev.clicker.remote.appletv

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * OPACK, Apple's compact binary serialization used by the Companion protocol.
 *
 * Supported types when packing: null, Boolean, Int/Long (non-negative), Float/Double,
 * String, ByteArray, UUID, List and Map. Integers always unpack as [Long] and floats
 * as [Double].
 *
 * Format reference: pyatv (MIT), pyatv/support/opack.py.
 */
object Opack {

    fun pack(value: Any?): ByteArray {
        val out = ByteArrayOutputStream()
        write(out, value)
        return out.toByteArray()
    }

    /** Unpacks one value from [data] and returns it with the number of bytes used. */
    fun unpack(data: ByteArray): Pair<Any?, Int> {
        val reader = Reader(data)
        val value = reader.read()
        return value to reader.pos
    }

    @Suppress("UNCHECKED_CAST")
    fun unpackMap(data: ByteArray): Map<String, Any?> =
        unpack(data).first as? Map<String, Any?>
            ?: throw IllegalArgumentException("OPACK payload is not a dictionary")

    private fun write(out: ByteArrayOutputStream, value: Any?) {
        when (value) {
            null -> out.write(0x04)
            is Boolean -> out.write(if (value) 0x01 else 0x02)
            is UUID -> {
                out.write(0x05)
                out.write(ByteBuffer.allocate(16).putLong(value.mostSignificantBits)
                    .putLong(value.leastSignificantBits).array())
            }
            is Int -> writeInt(out, value.toLong())
            is Long -> writeInt(out, value)
            is Float -> writeDouble(out, value.toDouble())
            is Double -> writeDouble(out, value)
            is String -> {
                val bytes = value.toByteArray(Charsets.UTF_8)
                writeSized(out, bytes.size, small = 0x40, sizedBase = 0x61, sizeBytes = intArrayOf(1, 2, 3, 4))
                out.write(bytes)
            }
            is ByteArray -> {
                writeSized(out, value.size, small = 0x70, sizedBase = 0x91, sizeBytes = intArrayOf(1, 2, 4, 8))
                out.write(value)
            }
            is List<*> -> {
                out.write(0xD0 + minOf(value.size, 0xF))
                value.forEach { write(out, it) }
                if (value.size >= 0xF) out.write(0x03)
            }
            is Map<*, *> -> {
                out.write(0xE0 + minOf(value.size, 0xF))
                value.forEach { (k, v) -> write(out, k); write(out, v) }
                if (value.size >= 0xF) out.write(0x03)
            }
            else -> throw IllegalArgumentException("Cannot OPACK ${value::class.java}")
        }
    }

    private fun writeInt(out: ByteArrayOutputStream, value: Long) {
        require(value >= 0) { "OPACK integers must be non-negative" }
        when {
            value < 0x28 -> out.write((value + 8).toInt())
            value <= 0xFF -> { out.write(0x30); writeLe(out, value, 1) }
            value <= 0xFFFF -> { out.write(0x31); writeLe(out, value, 2) }
            value <= 0xFFFFFFFFL -> { out.write(0x32); writeLe(out, value, 4) }
            else -> { out.write(0x33); writeLe(out, value, 8) }
        }
    }

    private fun writeDouble(out: ByteArrayOutputStream, value: Double) {
        out.write(0x36)
        out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(value).array())
    }

    /** Length prefix: inline for up to 32 bytes, otherwise a marker plus a little-endian length. */
    private fun writeSized(out: ByteArrayOutputStream, length: Int, small: Int, sizedBase: Int, sizeBytes: IntArray) {
        if (length <= 0x20) {
            out.write(small + length)
            return
        }
        for ((index, bytes) in sizeBytes.withIndex()) {
            val max = if (bytes >= 8) Long.MAX_VALUE else (1L shl (8 * bytes)) - 1
            if (length <= max) {
                out.write(sizedBase + index)
                writeLe(out, length.toLong(), bytes)
                return
            }
        }
    }

    private fun writeLe(out: ByteArrayOutputStream, value: Long, bytes: Int) {
        for (i in 0 until bytes) out.write(((value ushr (8 * i)) and 0xFF).toInt())
    }

    private class Reader(private val data: ByteArray) {
        var pos = 0
        private val objects = mutableListOf<Any?>()

        private fun u8(): Int = data[pos++].toInt() and 0xFF

        private fun le(bytes: Int): Long {
            var v = 0L
            for (i in 0 until bytes) v = v or ((data[pos + i].toLong() and 0xFF) shl (8 * i))
            pos += bytes
            return v
        }

        private fun take(n: Int): ByteArray = data.copyOfRange(pos, pos + n).also { pos += n }

        fun read(): Any? {
            val tag = u8()
            var remember = true
            val value: Any? = when {
                tag == 0x01 -> { remember = false; true }
                tag == 0x02 -> { remember = false; false }
                tag == 0x04 -> { remember = false; null }
                tag == 0x05 -> {
                    val b = ByteBuffer.wrap(take(16))
                    UUID(b.long, b.long)
                }
                tag == 0x06 -> le(8) // absolute time, kept as raw integer
                tag in 0x08..0x2F -> { remember = false; (tag - 8).toLong() }
                tag == 0x35 -> ByteBuffer.wrap(take(4)).order(ByteOrder.LITTLE_ENDIAN).float.toDouble()
                tag == 0x36 -> ByteBuffer.wrap(take(8)).order(ByteOrder.LITTLE_ENDIAN).double
                tag and 0xF0 == 0x30 -> le(1 shl (tag and 0xF))
                tag in 0x40..0x60 -> String(take(tag - 0x40), Charsets.UTF_8)
                tag in 0x61..0x64 -> String(take(le(tag and 0xF).toInt()), Charsets.UTF_8)
                tag in 0x70..0x90 -> take(tag - 0x70)
                tag in 0x91..0x94 -> take(le(1 shl ((tag and 0xF) - 1)).toInt())
                tag and 0xF0 == 0xD0 -> {
                    remember = false
                    val list = mutableListOf<Any?>()
                    val count = tag and 0xF
                    if (count == 0xF) {
                        while (data[pos].toInt() != 0x03) list += read()
                        pos++
                    } else repeat(count) { list += read() }
                    list
                }
                tag and 0xF0 == 0xE0 -> {
                    remember = false
                    val map = LinkedHashMap<Any?, Any?>()
                    val count = tag and 0xF
                    if (count == 0xF) {
                        while (data[pos].toInt() != 0x03) { val k = read(); map[k] = read() }
                        pos++
                    } else repeat(count) { val k = read(); map[k] = read() }
                    map
                }
                tag in 0xA0..0xC0 -> objects[tag - 0xA0]
                tag in 0xC1..0xC4 -> objects[le(tag - 0xC0).toInt()]
                else -> throw IllegalArgumentException("Unknown OPACK tag 0x%02x".format(tag))
            }
            if (remember && objects.none { sameValue(it, value) }) objects += value
            return value
        }

        private fun sameValue(a: Any?, b: Any?): Boolean = when {
            a is ByteArray && b is ByteArray -> a.contentEquals(b)
            a is Number && b is Number -> a.toDouble() == b.toDouble()
            else -> a == b
        }
    }
}
