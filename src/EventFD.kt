package kotlinx.native.nio

import kotlinx.cinterop.*
import epoll.*
import netinet.*

class EventFd(val flags: Int = EFD_NONBLOCK) {
    val fd = eventfd(0, flags).ensureUnixCallResult("eventfd()") { it >= 0 }
    private val buffer = nativeHeap.allocArray<LongVar>(2)
    private var released = false

    fun signal() {
        if (released) throw IllegalStateException("already released")

        buffer[0] = 1L
        write(fd, buffer, 8).ensureUnixCallResult("write(eventfd)") { it == 8L }
    }

    fun get(): Boolean {
        buffer[1] = 0L
        read(fd, buffer + 1, 8).ensureUnixCallResult("read(eventfd)") { it == 8L }
        return buffer[1] > 0L
    }

    fun close() {
        if (!released) {
            released = true
            close(fd)
            nativeHeap.free(buffer)
        }
    }
}
