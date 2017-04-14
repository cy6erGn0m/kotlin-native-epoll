package kotlinx.native.nio

import errno.*
import netinet.*
import kotlinx.cinterop.*

open class SocketChannelBase internal constructor(val fd: Int) : ReadableByteChannel, WritableByteChannel {
    constructor() : this(socket(AF_INET, SOCK_STREAM, 0))

    init {
        fd.ensureUnixCallResult("socket()") { it >= 0 }
    }
    
    private var tmpReadBuffer: DirectByteBuffer? = null
    private var tmpWriteBuffer: DirectByteBuffer? = null
    
    override val isOpen: Boolean get() = true   

    fun configureBlocking(blocking: Boolean) {
        var flags = fcntl(fd, F_GETFL, 0).ensureUnixCallResult("fcntl(F_GETFL)") { it >= 0 }
        flags = if (blocking) (flags and O_NONBLOCK.inv()) else (flags or O_NONBLOCK)

        fcntl(fd, F_SETFL, flags).ensureUnixCallResult("fcntl(F_SETFL)") { it == 0 }
    }

    override fun read(buffer: ByteBuffer): Int {
        val size = buffer.remaining()

        val rc: Long
        if (buffer !is DirectByteBuffer) {
            val tmpBuffer = direct(size, false)
            rc = recv(fd, tmpBuffer.array, size.toLong(), MSG_NOSIGNAL)
            if (rc > 0L) {
                tmpBuffer.position(0)
                tmpBuffer.limit(rc.toInt())
                buffer.put(tmpBuffer)
            }
        } else {
            rc = recv(fd, buffer.array, size.toLong(), MSG_NOSIGNAL)
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
            val tmp = direct(size, true)
            val oldPosition = buffer.position()
            tmp.clear()
            tmp.put(buffer)
            buffer.position(oldPosition)

            rc = send(fd, tmp.array, size.toLong(), MSG_NOSIGNAL)
        } else {
            rc = send(fd, buffer.array + buffer.position() + buffer.offset, size.toLong(), MSG_NOSIGNAL)
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
        tmpReadBuffer?.close()
        tmpReadBuffer = null
        tmpWriteBuffer?.close()
        tmpWriteBuffer = null
    }
    
    private fun direct(size: Int, write: Boolean): DirectByteBuffer {
        val buffer = if (write) tmpWriteBuffer else tmpReadBuffer

        val result = if (buffer == null || buffer.capacity() < size) {
            (nativeHeap.allocDirectBuffer(size) as DirectByteBuffer).apply { if (write) tmpWriteBuffer = this else tmpReadBuffer = this }
        } else buffer

        return result
    }
}

