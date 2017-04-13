package kotlinx.native.nio

import errno.*
import epoll.*
import kotlinx.cinterop.*

class SocketChannel internal constructor(val fd: Int) : ReadableByteChannel, WritableByteChannel {
    constructor() : this(socket(AF_INET, SOCK_STREAM, 0).ensureUnixCallResult { it >= 0 })

    private var tmpBuffer: DirectByteBuffer? = null
    
    override val isOpen: Boolean get() = true   

    override fun read(buffer: ByteBuffer): Int {
        val size = buffer.remaining()

        val rc: Long
        if (buffer !is DirectByteBuffer) {
            val tmpBuffer = direct(size)
            rc = read(fd, tmpBuffer.array, size.toLong())
            if (rc > 0L) {
                tmpBuffer.position(0)
                tmpBuffer.limit(rc.toInt())
                buffer.put(tmpBuffer)
            }
        } else {
            rc = read(fd, buffer.array, size.toLong())
            if (rc > 0L) {
                buffer.position(buffer.position() + rc.toInt())
            }
        }

        return when {
            rc == 0L -> -1
            rc > 0L -> rc.toInt()
            else -> {
                val errno = errno()
                if (errno == EAGAIN || errno == EWOULDBLOCK) {
                    0
                } else {
                    throw Exception("read() failed with $errno: ${errorMessage(errno)}")
                }
            }
        }
    }

    override fun write(buffer: ByteBuffer): Int {
        val size = buffer.remaining()
        var rc: Long
        
        if (buffer !is DirectByteBuffer) {
            val tmp = direct(size)
            val oldPosition = buffer.position()
            tmp.clear()
            tmp.put(buffer)
            buffer.position(oldPosition)

            rc = write(fd, tmp.array, size.toLong())
        } else {
            rc = write(fd, buffer.array, size.toLong())
        }

        if (rc > 0L) {
            buffer.position(buffer.position() + rc.toInt())
        }

        return when {
            rc >= 0L -> rc.toInt()
            else -> {
                val errno = errno()
                if (errno == EAGAIN || errno == EWOULDBLOCK) {
                    0
                } else throw Exception("write() failed with $errno: ${errorMessage(errno)}")
            }
        }
    }
    
    override fun close() {
        close(fd)
        tmpBuffer?.close()
        tmpBuffer = null
    }
    
    private fun direct(size: Int): DirectByteBuffer {
        val buffer = tmpBuffer
        val result = if (buffer == null || buffer.capacity() < size) {
            (nativeHeap.allocDirectBuffer(size) as DirectByteBuffer).apply { tmpBuffer = this }
        } else buffer

        return result
    }
}

