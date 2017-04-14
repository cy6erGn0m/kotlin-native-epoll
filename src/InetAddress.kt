package kotlinx.native.nio

import netdb.*
import kotlinx.cinterop.*

class UnknownHostException(val host: String, val reason: String? = null) : Exception("host $host is unknown: $reason")

sealed class InetAddress(val hostname: String?) {
    companion object {
        fun getAllByName(name: String): Array<InetAddress> {
            if (name.isEmpty()) throw UnknownHostException(name)

            if (name[0].let { it >= '0' && it <= '9' }) {
                val addr = tryParseIPv4(name)
                if (addr != null) return arrayOf(Inet4Address(name, addr))
            } else if (name[0] == ':') {
                // TODO try parse IPv6
            } else if (name[0] == '[' && name.last() == ']') {
                // TODO force parse IPv6
                throw UnknownHostException(name)
            }

            return resolve(name)
        }
    }
}

class Inet4Address(hostname: String?, val addr: ByteArray) : InetAddress(hostname) {
    init {
        require(addr.size == 4) { "requires IPv4 address" }
    }

    override fun toString(): String {
        val sb = StringBuilder((hostname?.length ?: 0) + 16)
            if (hostname != null) {
                sb.append(hostname)
                sb.append(' ')
            }
            addr.joinTo(sb, separator = ".") { (it.toInt() and 0xff).toString() }
        return sb.toString()
    }

    fun toNative(out: CPointer<in_addr>) {
        val v = htonl((addr[0].toInt() shl 24) or (addr[1].toInt() shl 16) or (addr[2].toInt() shl 8) or (addr[3].toInt()))
        out.pointed.s_addr = v
    }

    override fun equals(other: Any?): Boolean {
        if (other !is Inet4Address) return false

        return addr.contentEquals(other.addr)
    }

    override fun hashCode(): Int = addr.contentHashCode()
}

class Inet6Address(hostname: String?, val addr: ByteArray) : InetAddress(hostname) {
    init {
        require(addr.size == 16) { "requires IPv6 address" }
    }
}

private fun tryParseIPv4(s: String): ByteArray? {
    if (s.length < 7 || s.length > 15) return null

    val result = ByteArray(4)
    var ridx = 0
    var n = -1
   
    for (ch in s) {
        if (ch == '.') {
            if (n == -1) return null
            result[ridx++] = n.toByte()
            if (ridx == 4) return null
            n = -1
        } else if (n == -1) {
            n = ch.toInt() - 0x30
        } else {
            n = n * 10 + ch.toInt() - 0x30 
        }

        if (n > 255) return null
    } 

    if (ridx != 2) return null
    result[3] = n.toByte()

    return result
}

private fun resolve(host: String): Array<InetAddress> {
    return memScoped {
        val ptrPtr = alloc<CPointerVar<addrinfo>>()

        val rc = getaddrinfo(host, null, null, ptrPtr.ptr)
        if (rc != 0) {
            throw UnknownHostException(host, gai_strerror(rc)?.toKString())
        }
        
        val results = mutableSetOf<InetAddress>()
        try {
            var ptr = ptrPtr.value

            while (ptr != null) {
                val addr = ptr.pointed 

                when (addr.ai_family) {
                    AF_INET -> {
                        val name = addr.ai_canonname?.toKString() ?: host
                        var saddr = addr.ai_addr?.reinterpret<sockaddr_in>()?.pointed?.sin_addr?.s_addr

                        if (saddr != null) {
                            saddr = ntohl(saddr)
                            val bytes = ByteArray(4)
                            bytes[0] = ((saddr shr 24) and 0xff).toByte()
                            bytes[1] = ((saddr shr 16) and 0xff).toByte()
                            bytes[2] = ((saddr shr 8)  and 0xff).toByte()
                            bytes[3] = (saddr and 0xff).toByte()

                            results.add(Inet4Address(name, bytes))
                        }
                    }
                    // TODO support IPv6
                }
                        
                ptr = addr.ai_next
            }
        } finally {
            freeaddrinfo(ptrPtr.value)
        }

        results.toTypedArray()
    }
}


private fun ByteArray.contentEquals(other: ByteArray): Boolean {
    if (size != other.size) return false

    for (i in 0..size - 1) {
        if (this[i] != other[i]) return false
    }

    return true
}

private fun ByteArray.contentHashCode(): Int {
    var result = 1
    
    for (i in 0..size - 1) {
        result = 31 * result + this[i]
    }

    return result
}

