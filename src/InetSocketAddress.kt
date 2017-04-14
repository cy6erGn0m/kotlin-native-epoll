package kotlinx.native.nio

import kotlinx.cinterop.*
import netinet.*
import errno.*

data class InetSocketAddress(val address: InetAddress, val port: Int) {
    constructor(port: Int) : this(Inet4Address("localhost", byteArrayOf(127.toByte(), 0.toByte(), 0.toByte(), 1.toByte())), port) 
    constructor(hostname: String, port: Int) : this(InetAddress.getAllByName(hostname)[0], port)

    // TODO getLocalHostAddress
    // TODO support unresolved addresses

    fun toNative(): CPointer<sockaddr_in> {
        val v4 = address as Inet4Address
        
        val sa = nativeHeap.alloc<sockaddr_in>()
        memset(sa.ptr, 0, sockaddr_in.size)
        
        v4.toNative(sa.sin_addr.ptr.reinterpret())
        
        sa.sin_family = AF_INET.narrow()
        sa.sin_port = htons(port.toShort())

        return sa.ptr
    }
}
