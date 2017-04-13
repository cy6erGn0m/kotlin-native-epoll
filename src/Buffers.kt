package kotlinx.native.nio

import kotlinx.cinterop.*

abstract class ByteBuffer(val capacity: Int) : Closeable {
    init {
        if (capacity < 0) throw IllegalArgumentException()
    }

    protected var limit: Int = capacity
    protected var position: Int = 0

    abstract fun get(): Byte
    abstract fun put(b: Byte)

    abstract fun put(other: ByteBuffer)

    abstract fun hasArray(): Boolean
    abstract fun isDirect(): Boolean

    abstract fun isReadOnly(): Boolean
    abstract fun array(): ByteArray

    abstract fun arrayOffset(): Int

    fun capacity() = capacity

    fun limit(n: Int): ByteBuffer {
        require(n >= 0) { "limit shouldn't be negative: $n" }
        require(n <= capacity) { "limit is out of bounds: $n, capacity = $capacity" }

        limit = n
        if (position > n) {
            position = n
        }

        return this
    }

    fun limit() = limit

    fun position(n: Int): ByteBuffer {
        require(n >= 0) { "position shouldn't be negative: $n" }
        require(n < limit) { "position is out of bounds: $n, limit = $limit" }

        position = n
        return this
    }

    fun position() = position

    fun clear(): ByteBuffer {
        position = 0
        limit = capacity
        return this
    }

    fun flip() {
        limit = position
        position = 0
    }

    fun rewind() {
        position = 0
    }

    fun remaining() = limit - position
    fun hasRemaining() = limit > position
    
    companion object {
        fun allocate(capacity: Int): ByteBuffer = HeapByteBuffer(capacity)
        fun allocateDirect(capacity: Int): ByteBuffer = nativeHeap.allocDirectBuffer(capacity)
    }
}

internal class HeapByteBuffer(val array: ByteArray, val offset: Int, length: Int) : ByteBuffer(length) {
    constructor(capacity: Int) : this(ByteArray(capacity), 0, capacity)

    override fun hasArray() = true
    override fun array() = array

    override fun isDirect() = false
    override fun arrayOffset() = offset

    override fun isReadOnly() = false

    override fun get(): Byte {
        if (position >= limit) throw IllegalStateException()
        return array[offset + position++]
    }

    override fun put(b: Byte) {
        if (position >= limit) throw IllegalStateException()
        array[offset + position++] = b
    }

    override fun put(other: ByteBuffer) {
        val size = other.remaining()
        if (size > remaining()) throw IllegalArgumentException()

        var p = offset + position
        for (i in 0..size - 1) {
            array[p++] = other.get()
        }
    
        position += size
    }

    override fun close() {
    }
}

internal class DirectByteBuffer(val placement: NativeFreeablePlacement, val array: CPointer<ByteVar>, val offset: Int, length: Int) : ByteBuffer(length) {
    override fun hasArray() = false
    override fun array() = throw IllegalStateException()

    override fun isDirect() = true
    override fun arrayOffset() = throw IllegalStateException()

    override fun isReadOnly() = false

    override fun get(): Byte {
        if (position >= limit) throw IllegalStateException()
        return array[offset + position++]
    }

    override fun put(b: Byte) {
        if (position >= limit) throw IllegalStateException()
        array[offset + position++] = b
    }
    
    override fun put(other: ByteBuffer) {
        val size = other.remaining()
        if (size > remaining()) throw IllegalArgumentException()

        if (other is HeapByteBuffer) {
            val otherPosition = other.position()
            copy(other.array, other.offset + otherPosition, array, offset + position, size)
            other.position(otherPosition + size)
        } else {
            var p = offset + position
            for (i in 0..size - 1) {
                array[p++] = other.get()
            }
        }

        position += size
    }

    override fun close() {
        placement.free(array)
    }
}

fun NativeFreeablePlacement.allocDirectBuffer(capacity: Int): ByteBuffer = DirectByteBuffer(this, allocArray<ByteVar>(capacity), 0, capacity)

@Suppress("NOTHING_TO_INLINE")
private inline fun copy(from: ByteArray, fromOffset: Int, to: CPointer<ByteVar>, toOffset: Int, size: Int) {
    for (i in 0..size - 1) {
        to[toOffset + i] = from[fromOffset + i]
    }
}

