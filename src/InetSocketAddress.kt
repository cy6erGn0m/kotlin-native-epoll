package kotlinx.native.nio

import kotlinx.cinterop.*
import netinet.*
import errno.*

data class InetSocketAddress(val address: InetAddress, val port: Int) {
    constructor(port: Int) : this(Inet4Address("localhost", byteArrayOf(127.toByte(), 0.toByte(), 0.toByte(), 1.toByte())), port) 
    constructor(hostname: String, port: Int) : this(InetAddress.getAllByName(hostname)[0], port)

    // TODO getLocalHostAddress
    // TODO support unresolved addresses

    fun toNative(): CPointer<*> {
        return when (address) {
            is Inet4Address -> {
                val sa = nativeHeap.alloc<sockaddr_in>()
                memset(sa.ptr, 0, sockaddr_in.size)
                
                address.toNative(sa.sin_addr.ptr.reinterpret())
                
                sa.sin_family = AF_INET.narrow()
                sa.sin_port = htons(port.toShort())

                sa.ptr
            }
            is Inet6Address -> {
                val sa = nativeHeap.alloc<sockaddr_in6>()
                memset(sa.ptr, 0, sockaddr_in6.size)
                
                address.toNative(sa.sin6_addr.ptr.reinterpret())
                
                sa.sin6_family = AF_INET6.narrow()
                sa.sin6_port = htons(port.toShort())

                sa.ptr
            }
            else -> throw IllegalArgumentException("Unsupported InetAddress implmenetation: $address")
        }
    }
}
