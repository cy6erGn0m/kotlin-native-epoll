package kotlinx.native.nio

interface Closeable {
    fun close(): Unit
}

interface Channel : Closeable {
    val isOpen: Boolean
}

interface ReadableByteChannel : Channel {
    fun read(buffer: ByteBuffer): Int
}

interface WritableByteChannel : Channel {
    fun write(buffer: ByteBuffer): Int
}

