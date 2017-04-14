package kotlinx.native.nio

data class InetSocketAddress(val address: InetAddress, val port: Int) {
    constructor(port: Int) : this(Inet4Address("localhost", byteArrayOf(127.toByte(), 0.toByte(), 0.toByte(), 1.toByte())), port) 
    constructor(hostname: String, port: Int) : this(InetAddress.getAllByName(hostname)[0], port)

    // TODO getLocalHostAddress
    // TODO support unresolved addresses
}
