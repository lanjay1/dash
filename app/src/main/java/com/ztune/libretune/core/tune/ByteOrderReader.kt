package com.ztune.libretune.core.tune

import com.ztune.libretune.core.ini.types.DataType
import com.ztune.libretune.core.ini.types.Endianness
import java.nio.ByteBuffer
import java.nio.ByteOrder as NioByteOrder

/**
 * Endianness-aware binary reader for decoding raw ECU page data.
 *
 * Wraps a [ByteArray] and provides typed read methods that respect the
 * configured [Endianness]. The reader maintains a mutable [position] cursor
 * so callers can read values sequentially.
 *
 * @param data The byte array to read from.
 * @param endianness The byte order for multi-byte values. Defaults to little-endian
 *   which is the most common ECU convention.
 */
class ByteOrderReader(
    private val data: ByteArray,
    private val endianness: Endianness = Endianness.LITTLE_ENDIAN
) {
    private var position = 0

    /** Current read cursor position. */
    fun position(): Int = position

    /** Set the read cursor to [pos], clamped to [0, data.size]. */
    fun setPosition(pos: Int) {
        position = pos.coerceIn(0, data.size)
    }

    /** Number of bytes remaining from the current position to the end. */
    fun remaining(): Int = data.size - position

    /** Total size of the backing array. */
    fun size(): Int = data.size

    /** Convert the configured [Endianness] to NIO's [NioByteOrder]. */
    private val nioOrder: NioByteOrder
        get() = when (endianness) {
            Endianness.BIG_ENDIAN -> NioByteOrder.BIG_ENDIAN
            Endianness.LITTLE_ENDIAN -> NioByteOrder.LITTLE_ENDIAN
        }

    // ---- Primitive readers ----

    /** Read an unsigned 8-bit value (returned as Byte, caller interprets unsigned). */
    fun readU8(): Byte {
        checkRemaining(1)
        return data[position++]
    }

    /** Read a signed 8-bit value. */
    fun readS8(): Byte {
        checkRemaining(1)
        return data[position++]
    }

    /**
     * Read an unsigned 16-bit value.
     *
     * Returns a [Short] to match Java/Kotlin's unsigned-safe semantics.
     * Callers can use `Short.toUShort().toInt()` for the true unsigned int value.
     */
    fun readU16(): Short {
        checkRemaining(2)
        val bb = ByteBuffer.wrap(data, position, 2).order(nioOrder)
        position += 2
        return bb.getShort()
    }

    /** Read a signed 16-bit value. */
    fun readS16(): Short {
        checkRemaining(2)
        val bb = ByteBuffer.wrap(data, position, 2).order(nioOrder)
        position += 2
        return bb.getShort()
    }

    /**
     * Read an unsigned 32-bit value.
     *
     * Returns an [Int] which holds the bit pattern.
     * For values > 2^31-1, interpret as unsigned: `int.toLong() and 0xFFFFFFFFL`.
     */
    fun readU32(): Int {
        checkRemaining(4)
        val bb = ByteBuffer.wrap(data, position, 4).order(nioOrder)
        position += 4
        return bb.getInt()
    }

    /** Read a signed 32-bit value. */
    fun readS32(): Int {
        checkRemaining(4)
        val bb = ByteBuffer.wrap(data, position, 4).order(nioOrder)
        position += 4
        return bb.getInt()
    }

    /** Read a 32-bit IEEE 754 float. */
    fun readF32(): Float {
        checkRemaining(4)
        val bb = ByteBuffer.wrap(data, position, 4).order(nioOrder)
        position += 4
        return bb.getFloat()
    }

    /** Read [count] raw bytes starting at the current position. */
    fun readBytes(count: Int): ByteArray {
        require(count >= 0) { "count must be non-negative, got $count" }
        checkRemaining(count)
        val result = data.copyOfRange(position, position + count)
        position += count
        return result
    }

    /**
     * Read a null-terminated string starting at the current position.
     *
     * Scans forward until a 0x00 byte is found or the end of the data is reached.
     * The position is advanced past the null terminator (if found).
     *
     * @param maxLen Maximum number of bytes to scan (to avoid runaway reads).
     */
    fun readString(maxLen: Int = 256): String {
        val start = position
        val limit = minOf(start + maxLen, data.size)
        var end = start
        while (end < limit && data[end].toInt() != 0) {
            end++
        }
        position = if (end < data.size) end + 1 else end
        return String(data, start, end - start, Charsets.US_ASCII)
    }

    /**
     * Read a value based on its [DataType], returning a raw (unscaled) double.
     *
     * For integer types this returns the raw integer value as a double.
     * For [DataType.F32] it returns the float bits as a double.
     * For [DataType.BITS] it returns the raw byte as a double.
     * For [DataType.STRING] it reads a null-terminated ASCII string and returns
     * the hash code as a double (strings should be handled separately).
     *
     * The caller is responsible for applying scale/translate to convert
     * raw values to display values.
     */
    fun readValue(dataType: DataType): Double {
        return when (dataType) {
            DataType.U08 -> readU8().toUByte().toDouble()
            DataType.S08 -> readS8().toDouble()
            DataType.U16 -> readU16().toUShort().toDouble()
            DataType.S16 -> readS16().toDouble()
            DataType.U32 -> (readU32().toLong() and 0xFFFFFFFFL).toDouble()
            DataType.S32 -> readS32().toDouble()
            DataType.F32 -> readF32().toDouble()
            DataType.BITS -> readU8().toUByte().toDouble()
            DataType.STRING -> readString().hashCode().toDouble()
        }
    }

    /**
     * Peek at the next byte without advancing the position.
     * Returns null if at the end of the data.
     */
    fun peekU8(): Byte? {
        if (position >= data.size) return null
        return data[position]
    }

    /**
     * Read a value at an absolute offset without affecting the current position.
     *
     * @param offset Byte offset into [data].
     * @param dataType The type of value to read.
     * @return The decoded raw value, or null if [offset] is out of bounds.
     */
    fun readValueAt(offset: Int, dataType: DataType): Double? {
        if (offset < 0 || offset >= data.size) return null
        val savedPosition = position
        position = offset
        val result = try {
            readValue(dataType)
        } catch (_: Exception) {
            return null
        } finally {
            position = savedPosition
        }
        return result
    }

    /**
     * Read a value at an absolute offset, applying scale and translate.
     *
     * This is the most common usage pattern: given a [Constant]'s page data,
     * read the raw bytes at [offset], interpret as [dataType], and convert
     * to a display value using [scale] and [translate].
     *
     * Formula: `displayValue = rawValue * scale + translate`
     */
    fun readScaledValueAt(offset: Int, dataType: DataType, scale: Double, translate: Double): Double? {
        val raw = readValueAt(offset, dataType) ?: return null
        return raw * scale + translate
    }

    /** Throw if there aren't at least [n] bytes remaining. */
    private fun checkRemaining(n: Int) {
        require(position + n <= data.size) {
            "Attempted to read $n byte(s) at position $position, but only ${data.size - position} byte(s) remain"
        }
    }
}