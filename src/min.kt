import epoll.*
import errno.*
import kotlinx.cinterop.*


interface SelectionKey {
    val fd: Int
    val interestOps: Int
    val readyOps: Int
    var attachment: Any?
}

private class SelectionKeyImpl(override val fd: Int) : SelectionKey {
    override var interestOps = 0
    override var readyOps = 0
    override var attachment: Any? = null
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

    fun cancel(fd: Int) {
        val sk = keys.remove(fd) ?: return
        sk.interestOps = 0
        sk.readyOps = 0

        _selected.remove(sk)

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

        keys.clear()
        _selected.clear()
    }
}

class Client(val fd: Int, val selector: EPollSelector) {
    val buffer = nativeHeap.allocArray<ByteVar>(8192)
    var index = 0L
    var size = 0L

    fun close() {
        size = 0L
        nativeHeap.free(buffer)
        selector.cancel(fd)
        close(fd)
    }
}

fun handleAccept(socket: Int, selector: EPollSelector) {
    val client = accept(socket, null, null).ensureUnixCallResult("accept") { it >= 0 }
    val flag = fcntl(client, F_GETFL, 0).ensureUnixCallResult("fcntl(GETFL)") { it >= 0 }
    fcntl(client, F_SETFL, flag or O_NONBLOCK)
    val sk = selector.interest(client, POLLIN)
    sk.attachment = Client(client, selector)
}

fun handleSelected(client: Client, k: SelectionKey, selector: EPollSelector) {
    if (k.readyOps and POLLIN != 0) {
        val rc = read(k.fd, client.buffer, 8192)
        
        if (rc == 0L) {
            client.close()
        } else if (rc > 0L) {
            client.index = 0
            client.size = rc
            selector.interest(k.fd, POLLOUT)
        } else if (rc == -1L) {
            @Suppress("DUPLICATE_LABEL_IN_WHEN")
            when (errno()) {
                EAGAIN, EWOULDBLOCK -> {}
                else -> {
                    client.close()
                }
            }
        }
    } else if (k.readyOps and POLLOUT != 0) {
        val rc = write(k.fd, client.buffer + client.index, client.size)
        if (rc == -1L) {
            client.close()
        } else {
            client.index += rc
            client.size -= rc
            if (client.size == 0L) {
                selector.interest(k.fd, POLLIN)
            }
        }
    }
}

tailrec fun selectLoop(selector: EPollSelector) {
    if (selector.wait(Int.MAX_VALUE) > 0) {
        for (k in selector.selected) {
            val client = k.attachment as? Client
            if (client == null) {
                handleAccept(k.fd, selector)
            } else {
                handleSelected(client, k, selector)
            }
        }
    } 
    selectLoop(selector)
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
        
        selectLoop(selector)
    } finally {
        close(socket)
        selector.close()
    }
}

fun errno(): Int {
    val errnoAddress = __errno_location()
    return if (errnoAddress != null) errnoAddress.pointed.value else 0
}

fun throwUnixError(name: String?): Nothing {
        val errorString = memScoped {
            val errorBuffer = allocArray<ByteVar>(8192)
            strerror_r(errno(), errorBuffer, 8192)
            errorBuffer.toKString()
        }

        throw Error("UNIX call ${name ?: ""} failed: $errorString")
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
