package kotlinx.native.nio

import kotlinx.cinterop.*
import epoll.*
import errno.*
import netinet.*

class SocketChannel : SocketChannelBase {
    constructor(fd: Int) : super(fd)
    constructor() : super()

    fun connect(address: InetSocketAddress): Boolean {
        val size = when (address.address) {
            is Inet4Address -> sockaddr_in.size.toInt()
            is Inet6Address -> sockaddr_in6.size.toInt()
            else -> throw IllegalStateException("Only Inet4Address and Inet6Address are supported")
        }
        val server = address.toNative()
        create(server.reinterpret<sockaddr_in>().pointed.sin_family)

        try {
            val rc = connect(fd, server.reinterpret(), size)

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
