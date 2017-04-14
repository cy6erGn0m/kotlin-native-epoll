package kotlinx.native.nio

import kotlinx.cinterop.*
import epoll.*
import errno.*
import netinet.*

class SocketChannel : SocketChannelBase {
    constructor(fd: Int) : super(fd)
    constructor() : super()

    fun connect(address: InetSocketAddress): Boolean {
        val server = address.toNative()
        try {
            val rc = connect(fd, server.reinterpret(), sockaddr_in.size.toInt())

            if (rc == 0) return true
            val errno = errno()
            if (errno == EINPROGRESS) return false

            throw Exception("connect() failed: ${errorMessage(errno)} ($errno)")
        } finally {
            nativeHeap.free(server)
        }
    }

    fun finishConnect(): Boolean {
        return memScoped {
            val err = alloc<IntVar>()
            val size = alloc<socklen_tVar>()

            getsockopt(fd, SOL_SOCKET, SO_ERROR, err.ptr, size.ptr).ensureUnixCallResult("getsockopt") { it == 0 }

            when (err.value) {
                EINPROGRESS -> false
                0 -> true
                else -> throw Exception("connect failed: ${errorMessage(err.value)} (${err.value})")
            }
        }
    }
}
