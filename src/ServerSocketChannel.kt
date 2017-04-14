package kotlinx.native.nio

import kotlinx.cinterop.*
import netinet.*
import errno.*

class ServerSocketChannel : SocketChannelBase() {
    fun bind(port: Int) {
        val a = InetSocketAddress(port).toNative()
        a.pointed.sin_addr.s_addr = INADDR_ANY
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
