package kotlinx.native.nio

import kotlinx.cinterop.*
import epoll.*
import errno.*

interface SelectionKey {
    val fd: Int
    val interestOps: Int
    val readyOps: Int
    var attachment: Any?
}

interface Selector {
    val selected: Set<SelectionKey>
    fun start()
    fun wakeup()
    fun interest(fd: Int, interest: Int): SelectionKey
    fun cancel(fd: Int)
    fun wait(timeoutMillis: Int): Int
    fun close()
}

fun Selector.cancel(sk: SelectionKey) {
    cancel(sk.fd)
}
