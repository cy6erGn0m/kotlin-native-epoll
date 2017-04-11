import epoll.*
import errno.*
import kotlinx.cinterop.*


interface SelectionKey {
    val fd: Int
    val interestOps: Int
    val readyOps: Int
}

private class SelectionKeyImpl(override val fd: Int) : SelectionKey {
    override var interestOps = 0
    override var readyOps = 0
}

class EventFd(val flags: Int = EFD_NONBLOCK) {
    val fd = eventfd(0, flags).ensureUnixCallResult("eventfd()") { it >= 0 }
    private val buffer = nativeHeap.allocArray<LongVar>(2)
    private var released = false

    fun signal() {
        if (released) throw IllegalStateException("already released")

        buffer[0] = 1L
        write(fd, buffer, 8).ensureUnixCallResult("write(eventfd)") { it == 8L }
    }

    fun get(): Boolean {
        buffer[1] = 0L
        read(fd, buffer + 1, 8).ensureUnixCallResult("read(eventfd)") { it == 8L }
        return buffer[1] > 0L
    }

    fun close() {
        if (!released) {
            released = true
            close(fd)
            nativeHeap.free(buffer)
        }
    }
}

class EPollSelector {
    private var epfd: Int = -1
    private val wakeup = EventFd()
    private val buffer = nativeHeap.allocArray<epoll_event>(8192)

    private val keys = mutableMapOf<Int, SelectionKeyImpl>()
    private val _selected = mutableSetOf<SelectionKeyImpl>()

    val selected: Set<SelectionKey>
        get() = _selected

    fun start() {
        epfd = epoll_create(8192).ensureUnixCallResult("epoll_create") { it != -1 }

        buffer[0].events = POLLIN
        buffer[0].data.fd = wakeup.fd
        epoll_ctl(epfd, EPOLL_CTL_ADD, wakeup.fd, buffer[0].ptr).ensureUnixCallResult("epoll_ctl(eventfd)") { it == 0 }
    }

    fun wakeup() {
        wakeup.signal()
    }

    fun interest(fd: Int, interest: Int): SelectionKey {
        var add = false
        val sk = keys[fd] ?: run { add = true; SelectionKeyImpl(fd).apply { keys[fd] = this } } 

        sk.interestOps = interest
        sk.readyOps = 0

        buffer[0].events = interest
        buffer[0].data.fd = fd
        epoll_ctl(epfd, if (add) EPOLL_CTL_ADD else EPOLL_CTL_MOD, fd, buffer[0].ptr).ensureUnixCallResult("epoll_ctl") { it == 0 }

        return sk
    }

    fun cancel(fd: Int) {
        val sk = keys.remove(fd) ?: return
        sk.interestOps = 0
        sk.readyOps = 0

        buffer[0].events = 0
        buffer[0].data.fd = fd
        epoll_ctl(epfd, EPOLL_CTL_DEL, fd, buffer[0].ptr).ensureUnixCallResult("epoll_ctl") { it == 0 }
    }

    fun wait(timeoutMillis: Int): Int {
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

    fun close() {
        wakeup()

        // TODO wakeup and wait
        nativeHeap.free(buffer)
        if (epfd != -1) {
            close(epfd)
            epfd = -1
        }

        wakeup.close()
    }
}

fun main(args: Array<String>) {
    val port: Short = 9094
    val selector = EPollSelector()
    val socket = socket(AF_INET, SOCK_STREAM or SOCK_NONBLOCK, 0).ensureUnixCallResult("socket") { it >= 0 }

    memScoped {
        val serverAddr = alloc<sockaddr_in>()
        with(serverAddr) {
            memset(this.ptr, 0, sockaddr_in.size)
            sin_family = AF_INET.narrow()
            sin_addr.s_addr = htons(0).toInt()
            sin_port = htons(port)
        }
        bind(socket, serverAddr.ptr.reinterpret(), sockaddr_in.size.toInt()).ensureUnixCallResult("bind") { it == 0 }
        listen(socket, 100).ensureUnixCallResult("listen") { it == 0 }
    }

    try {
        selector.start()
        selector.interest(socket, POLLIN)
        
        while (true) {
            if (selector.wait(9000) > 0) {
                val client = accept(socket, null, null).ensureUnixCallResult("accept") { it >= 0 }

                println("Got client")
                close(client)
            } else {
                println("timeout")
                break
            }
        }

        selector.cancel(socket)
    } finally {
        close(socket)
        selector.close()
    }
}

fun throwUnixError(name: String?): Nothing {
    val errnoAddress = __errno_location()
    if (errnoAddress != null) {
        val errorString = memScoped {
            val errorBuffer = allocArray<ByteVar>(8192)
            strerror_r(errnoAddress.pointed.value, errorBuffer, 8192)
            errorBuffer.toKString()
        }

        throw Error("UNIX call ${name ?: ""} failed: $errorString")
    }

    perror(null)
    throw Error("UNIX call failed")
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
