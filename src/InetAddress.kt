package kotlinx.native.nio

import netdb.*
import kotlinx.cinterop.*
import errno.*

class UnknownHostException(val host: String, val reason: String? = null) : Exception("host $host is unknown: $reason")

sealed class InetAddress(val hostname: String?) {
    companion object {
        fun getAllByName(name: String): Array<InetAddress> {
            if (name.isEmpty()) throw UnknownHostException(name)

            if (name[0] == ':') {
                val addr6 = tryParseIPv6(name)
                if (addr6 != null) return arrayOf(Inet6Address(name, addr6))
            } else if (name[0] == '[' && name.last() == ']') {
                val addr6 = tryParseIPv6(name.substring(1, name.length - 1))
                if (addr6 != null) return arrayOf(Inet6Address(name, addr6))
                
                throw UnknownHostException(name)
            } else {
                val addr = tryParseIPv4(name)
                if (addr != null) return arrayOf(Inet4Address(name, addr))
                    
                val addr6 = tryParseIPv6(name)
                if (addr6 != null) return arrayOf(Inet6Address(name, addr6))
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
    
    override fun toString(): String {
        val sb = StringBuilder((hostname?.length ?: 0) + 40)
        
        if (hostname != null) {
            sb.append(hostname)
            sb.append(' ')
        }
        
        val r = ShortArray(8)
        var ri = 0
        
        var ai = 0
        for (g in 0..7) {
            val v = ((addr[ai].toInt() and 0xff shl 8) or (addr[ai + 1].toInt() and 0xff)).toShort()
            ai += 2
            
            r[ri++] = v
        }
        
        // TODO support ip4v to ipv6 mapping
        
        var longestIndex = -1
        var longestStrideSize = 0
        
        var start = 0
        var strideSize = 0
        
        for (i in 0..7) {
            if (r[i] == 0.toShort()) {
                if (strideSize == 0) start = i
                strideSize ++
            } else if (strideSize > 0) {
                if (strideSize > longestStrideSize) {
                    longestIndex = start
                    longestStrideSize = strideSize
                }
                strideSize = 0
            }
        }
        
        if (strideSize > longestStrideSize) {
            longestIndex = start
            longestStrideSize = strideSize
        }
        
        val longestStrideEnd = longestIndex + longestStrideSize
        
        for (i in 0..7) {
            val inStride = i >= longestIndex && i < longestStrideEnd
            
            if (i == longestIndex || i == longestStrideEnd) sb.append(":")
            else if (i != 0 && !inStride) sb.append(":")
                
            if (!inStride) {
                sb.append((r[i].toInt() and 0xffff).toString(16))
            }
        }
        
        if (longestStrideEnd == 8) {
            sb.append(":")
        }
        
        return sb.toString()
    }
    
    fun toNative(out: CPointer<in6_addr>) {
        val p = out.pointed.__in6_u.__u6_addr8
        for (i in 0..15) {
            p[i] = addr[i]
        }
    }
    
    override fun equals(other: Any?): Boolean {
        if (other !is Inet6Address) return false

        return addr.contentEquals(other.addr)
    }

    override fun hashCode(): Int = addr.contentHashCode()
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

fun tryParseIPv6(s: String): ByteArray? {
    if (s.length < 2 || s.length > 39) return null
        
    val result = ByteArray(16)
    var ridx = 0
    
    var strideStart = -1
    var i = 0
    
    if (s[0] == ':') {
        if (s[1] != ':') return null
        i = 2
        strideStart = 0
    }
    
    while (i < s.length) {
        val ch = s[i++]
        val digit = hexToIntOrNeg1(ch)
        
        if (ch == ':') { // start zero stride
            if (strideStart != -1) return null
            strideStart = ridx
        } else if (digit != -1) {
            var n = digit
            while (i < s.length) {
                val ch2 = s[i++]
                if (ch2 == ':') break
                
                val dn = hexToIntOrNeg1(ch2)
                if (dn == -1) return null
                
                n = (n shl 4) or dn
                
                if (n > 0xffff) return null
            }
            
            result[ridx++] = (n shr 8).toByte()
            result[ridx++] = (n and 0xff).toByte()
        } else return null
    }
    
    if (ridx < 16 && strideStart == -1) return null
    
    if (strideStart != -1) {
        val shift = 16 - ridx
        for (j in 0..shift - 1) {
            result[ridx + j] = result[strideStart + j]
            result[strideStart + j] = 0.toByte()
        }
    }
    
    return result
}

@Suppress("NOTHING_TO_INLINE")
private inline fun hexToIntOrNeg1(ch: Char) = when {
            ch >= '0' && ch <= '9' -> ch.toInt() - 0x30
            ch >= 'a' && ch <= 'f' -> ch.toInt() - 'a'.toInt() + 0xa
            ch >= 'A' && ch <= 'F' -> ch.toInt() - 'A'.toInt() + 0xa
            else -> -1
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
                    AF_INET6 -> {
                        val name = addr.ai_canonname?.toKString() ?: host
                        var saddr = addr.ai_addr?.reinterpret<sockaddr_in6>()?.pointed?.sin6_addr?.__in6_u?.__u6_addr8
                        
                        if (saddr != null) {
                            val b = ByteArray(16)
                            for (i in 0..15) {
                                b[i] = saddr[i]
                            }
                            
                            results.add(Inet6Address(name, b))
                        }
                    }
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

