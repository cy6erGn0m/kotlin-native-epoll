package kotlinx.native.nio

import kotlinx.cinterop.*
import epoll.*
import errno.*
import netinet.*

class EPollSelector : Selector {
    private var epfd: Int = -1
    private val wakeup = EventFd()
    private val buffer = nativeHeap.allocArray<epoll_event>(8192)

    private val keys = mutableMapOf<Int, SelectionKeyImpl>()
    private val _selected = mutableSetOf<SelectionKeyImpl>()

    override val selected: Set<SelectionKey>
        get() = _selected

    override fun start() {
        epfd = epoll_create(8192).ensureUnixCallResult("epoll_create") { it != -1 }

        buffer[0].events = POLLIN
        buffer[0].data.fd = wakeup.fd
        epoll_ctl(epfd, EPOLL_CTL_ADD, wakeup.fd, buffer[0].ptr).ensureUnixCallResult("epoll_ctl(eventfd)") { it == 0 }
    }

    override fun wakeup() {
        wakeup.signal()
    }

    override fun interest(fd: Int, interest: Int): SelectionKey {
        var add = false
        val sk = keys[fd] ?: run { add = true; SelectionKeyImpl(fd) } 

        sk.readyOps = 0

        buffer[0].events = interest
        buffer[0].data.fd = fd
        epoll_ctl(epfd, if (add) EPOLL_CTL_ADD else EPOLL_CTL_MOD, fd, buffer[0].ptr).ensureUnixCallResult("epoll_ctl") { it == 0 }

        sk.interestOps = interest

        if (add) {
            keys[fd] = sk
        }

        return sk
    }

    override fun cancel(fd: Int) {
        val sk = keys.remove(fd) ?: return
        sk.interestOps = 0
        sk.readyOps = 0

        _selected.remove(sk)

        buffer[0].events = 0
        buffer[0].data.fd = fd
        epoll_ctl(epfd, EPOLL_CTL_DEL, fd, buffer[0].ptr).ensureUnixCallResult("epoll_ctl") { it == 0 }
    }

    override fun wait(timeoutMillis: Int): Int {
        _selected.clear()
        val rc = epoll_wait(epfd, buffer, 8192, timeoutMillis).ensureUnixCallResult("epoll_wait") { it != -1 }
        
        for (i in 0..rc - 1) {
            val ev = buffer[i]

            val key = keys[ev.data.fd]
            if (key != null) {
                key.readyOps = key.readyOps or ev.events
                _selected.add(key)
            } else if (ev.data.fd == wakeup.fd) {
                wakeup.get()
            } else {
                println("No key found for fd ${ev.data.fd}")
            }
        }

        return rc
    }

    override fun close() {
        wakeup()

        // TODO wakeup and wait
        nativeHeap.free(buffer)
        if (epfd != -1) {
            close(epfd)
            epfd = -1
        }

        wakeup.close()

        keys.clear()
        _selected.clear()
    }
    
    private class SelectionKeyImpl(override val fd: Int) : SelectionKey {
        override var interestOps = 0
        override var readyOps = 0
        override var attachment: Any? = null
    }
}
