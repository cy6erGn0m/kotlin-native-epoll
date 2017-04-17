package kotlinx.native.nio

import kotlinx.cinterop.*
import netinet.*
import errno.*

class ServerSocketChannel : SocketChannelBase() {
    fun bind(port: Int) {
        val sa = InetSocketAddress(port)
        val a = sa.toNative()
        
        when (sa.address) {
            is Inet4Address -> (a.reinterpret<sockaddr_in>()).pointed.sin_addr.s_addr = INADDR_ANY
            is Inet6Address -> memset((a.reinterpret<sockaddr_in6>()).pointed.sin6_addr.__in6_u.__u6_addr8, 0, 16)
            else -> throw IllegalStateException("Only Inet4Address and Inet6Address are supported")
        }
        
        create(a.reinterpret<sockaddr_in>().pointed.sin_family)
        
        try {
            bind(fd, a.reinterpret(), sockaddr_in.size.toInt()).ensureUnixCallResult("bind()") { it == 0 }
            listen(fd, 1024).ensureUnixCallResult("listen()") { it == 0 }
        } finally {
            nativeHeap.free(a)
        }
    }

    fun accept(): SocketChannel? {
        val rc = accept(fd, null, null)
        if (rc >= 0) return SocketChannel(rc) // TODO mark connected

        val errno = errno()
        if (errno == EAGAIN || errno == EWOULDBLOCK) return null

        throw Exception("accept() failed: ${errorMessage(errno)} ($errno)")
    }
}
