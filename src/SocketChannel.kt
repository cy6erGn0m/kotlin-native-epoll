package kotlinx.native.nio

import kotlinx.cinterop.*
import epoll.*
import errno.*

class SocketChannel : SocketChannelBase() {
    fun connect(address: InetSocketAddress): Boolean {
        val addr = address.address as Inet4Address
        val v = htonl((addr.addr[0].toInt() shl 24) or (addr.addr[1].toInt() shl 16) or (addr.addr[2].toInt() shl 8) or (addr.addr[3].toInt()))

        return memScoped {
            val server = alloc<sockaddr_in>() // TODO memset 0
            server.sin_addr.s_addr = v
            server.sin_family = AF_INET.toShort()
            server.sin_port = htons(address.port.toShort())

            val rc = connect(fd, server.ptr.reinterpret(), sockaddr_in.size.toInt())

            if (rc == 0) return@memScoped true
            val errno = errno()
            if (errno == EINPROGRESS) return@memScoped false

            throw Exception("connect() failed: ${errorMessage(errno)} ($errno)")
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
