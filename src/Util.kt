package kotlinx.native.nio

import errno.*
import kotlinx.cinterop.*

fun errno(): Int {
    val errnoAddress = __errno_location()
    return if (errnoAddress != null) errnoAddress.pointed.value else 0
}

fun errorMessage(errno: Int): String {
    return memScoped {
            val errorBuffer = allocArray<ByteVar>(8192)
            strerror_r(errno, errorBuffer, 8192)
            errorBuffer.toKString()
        }
}

fun throwUnixError(name: String?): Nothing {
        throw Error("UNIX call ${name ?: ""} failed: ${errorMessage(errno)}")
}

inline fun Int.ensureUnixCallResult(name: String? = null, predicate: (Int) -> Boolean): Int {
    if (!predicate(this)) {
        throwUnixError(name)
    }
    return this
}

inline fun Long.ensureUnixCallResult(name: String? = null, predicate: (Long) -> Boolean): Long {
    if (!predicate(this)) {
        throwUnixError(name)
    }
    return this
}

