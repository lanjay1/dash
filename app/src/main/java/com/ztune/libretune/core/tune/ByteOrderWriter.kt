package com.ztune.libretune.core.tune

import com.ztune.libretune.core.ini.types.DataType
import com.ztune.libretune.core.ini.types.Endianness
import java.nio.ByteBuffer
import java.nio.ByteOrder as NioByteOrder

/**
 * Endianness-aware binary writer for encoding ECU calibration values into raw bytes.
 *
 * Maintains a growable internal buffer so callers don't need to know the exact
 * required capacity upfront. The [toByteArray] method returns only the written
 * portion.
 *
 * @param capacity Initial buffer capacity in bytes. The buffer grows automatically
 *   if more space is needed.
 * @param endianness The byte order for multi-byte values. Defaults to little-endian.
 */
class ByteOrderWriter(
    capacity: Int = 1024,
    private val endianness: Endianness = Endianness.LITTLE_ENDIAN
) {
    private var buffer = ByteArray(capacity)
    private var position = 0

    /** Current write cursor position (number of bytes written so far). */
    fun position(): Int = position

    /** Convert the configured [Endianness] to NIO's [NioByteOrder]. */
    private val nioOrder: NioByteOrder
        get() = when (endianness) {
            Endianness.BIG_ENDIAN -> NioByteOrder.BIG_ENDIAN
            Endianness.LITTLE_ENDIAN -> NioByteOrder.LITTLE_ENDIAN
        }

    /**
     * Return a copy of the written bytes (from index 0 to [position]).
     * The returned array's length equals [position].
     */
    fun toByteArray(): ByteArray = buffer.copyOf(position)

    // ---- Growth ----

    /**
     * Ensure at least [additional] bytes can be written from the current position.
     * Grows the buffer by doubling (or to the minimum required size) if needed.
     */
    private fun ensureCapacity(additional: Int) {
        val required = position + additional
        if (required <= buffer.size) return
        var newSize = buffer.size.coerceAtLeast(16)
        while (newSize < required) {
            newSize *= 2
        }
        buffer = buffer.copyOf(newSize)
    }

    // ---- Primitive writers ----

    /** Write an unsigned 8-bit value (only the low 8 bits are used). */
    fun writeU8(value: Int) {
        ensureCapacity(1)
        buffer[position++] = (value and 0xFF).toByte()
    }

    /** Write a signed 8-bit value. */
    fun writeS8(value: Int) {
        ensureCapacity(1)
        buffer[position++] = value.toByte()
    }

    /** Write an unsigned 16-bit value (only the low 16 bits are used). */
    fun writeU16(value: Int) {
        ensureCapacity(2)
        val bb = ByteBuffer.allocate(2).order(nioOrder)
        bb.putShort((value and 0xFFFF).toShort())
        bb.flip()
        bb.get(buffer, position, 2)
        position += 2
    }

    /** Write a signed 16-bit value. */
    fun writeS16(value: Int) {
        ensureCapacity(2)
        val bb = ByteBuffer.allocate(2).order(nioOrder)
        bb.putShort(value.toShort())
        bb.flip()
        bb.get(buffer, position, 2)
        position += 2
    }

    /** Write an unsigned 32-bit value (only the low 32 bits are used). */
    fun writeU32(value: Long) {
        ensureCapacity(4)
        val bb = ByteBuffer.allocate(4).order(nioOrder)
        bb.putInt((value and 0xFFFFFFFFL).toInt())
        bb.flip()
        bb.get(buffer, position, 4)
        position += 4
    }

    /** Write a signed 32-bit value. */
    fun writeS32(value: Int) {
        ensureCapacity(4)
        val bb = ByteBuffer.allocate(4).order(nioOrder)
        bb.putInt(value)
        bb.flip()
        bb.get(buffer, position, 4)
        position += 4
    }

    /** Write a 32-bit IEEE 754 float. */
    fun writeF32(value: Float) {
        ensureCapacity(4)
        val bb = ByteBuffer.allocate(4).order(nioOrder)
        bb.putFloat(value)
        bb.flip()
        bb.get(buffer, position, 4)
        position += 4
    }

    /** Write raw bytes directly into the buffer. */
    fun writeBytes(data: ByteArray) {
        if (data.isEmpty()) return
        ensureCapacity(data.size)
        System.arraycopy(data, 0, buffer, position, data.size)
        position += data.size
    }

    /**
     * Write a null-terminated ASCII string.
     *
     * The string is encoded as its ASCII bytes followed by a 0x00 terminator.
     * Characters outside the ASCII range are replaced with '?' (as per US_ASCII charset).
     *
     * @param value The string to write.
     * @param maxLen Maximum number of data bytes (excluding null terminator).
     *   If the encoded string exceeds this, it is truncated.
     */
    fun writeString(value: String, maxLen: Int = 256) {
        val bytes = value.toByteArray(Charsets.US_ASCII)
        val writeLen = minOf(bytes.size, maxLen).coerceAtLeast(0)
        ensureCapacity(writeLen + 1)
        System.arraycopy(bytes, 0, buffer, position, writeLen)
        buffer[position + writeLen] = 0 // null terminator
        position += writeLen + 1
    }

    /**
     * Write a value based on its [DataType] from a raw (unscaled) double.
     *
     * This performs the **inverse** of [ByteOrderReader.readValue]:
     * - Integer types: the double is rounded/truncated to the appropriate integer width.
     * - [DataType.F32]: the double is cast to float.
     * - [DataType.BITS]: the double is truncated to a byte.
     * - [DataType.STRING]: not supported (throws [UnsupportedOperationException]).
     *
     * The caller should unscale the display value first:
     * `rawValue = (displayValue - translate) / scale`
     */
    fun writeValue(dataType: DataType, value: Double) {
        when (dataType) {
            DataType.U08 -> writeU8(value.toInt())
            DataType.S08 -> writeS8(value.toInt())
            DataType.U16 -> writeU16(value.toInt())
            DataType.S16 -> writeS16(value.toInt())
            DataType.U32 -> writeU32(value.toLong())
            DataType.S32 -> writeS32(value.toInt())
            DataType.F32 -> writeF32(value.toFloat())
            DataType.BITS -> writeU8(value.toInt())
            DataType.STRING -> throw UnsupportedOperationException(
                "Cannot write STRING via writeValue; use writeString() instead."
            )
        }
    }

    /**
     * Write a scaled display value to the buffer at the current position.
     *
     * This is the inverse of [ByteOrderReader.readScaledValueAt]:
     * `rawValue = (displayValue - translate) / scale`
     * then the raw value is encoded per [dataType].
     *
     * @param dataType The on-wire data type.
     * @param displayValue The user-visible scaled value.
     * @param scale The scale factor.
     * @param translate The translate offset.
     */
    fun writeScaledValue(dataType: DataType, displayValue: Double, scale: Double, translate: Double) {
        val raw = (displayValue - translate) / scale
        writeValue(dataType, raw)
    }

    /**
     * Write a value at an absolute offset without changing the current position.
     *
     * The buffer is grown if needed to accommodate the write.
     *
     * @param offset Byte offset into the buffer.
     * @param dataType The type of value to write.
     * @param value The raw (unscaled) value.
     * @throws IllegalArgumentException if [offset] is negative.
     */
    fun writeValueAt(offset: Int, dataType: DataType, value: Double) {
        require(offset >= 0) { "offset must be non-negative, got $offset" }
        if (dataType.byteSize == 0) return // STRING has size 0, handled separately

        val savedPosition = position
        position = offset
        writeValue(dataType, value)
        position = savedPosition
    }
}
