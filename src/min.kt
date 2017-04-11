import epoll.*
import errno.*
import kotlinx.cinterop.*

class SelectionKey(val fd: Int) {
    var readyOps = 0
}

class EPollSelector {
    private var epfd: Int = -1
    private var wakeupFd: Int = -1
    private val buffer = nativeHeap.allocArray<epoll_event>(8192)
    private val keys = mutableMapOf<Int, SelectionKey>()
    private val _selected = mutableSetOf<SelectionKey>()

    val selected: Set<SelectionKey>
        get() = _selected

    fun start() {
        epfd = epoll_create(8192).ensureUnixCallResult("epoll_create") { it != -1 }
        wakeupFd = eventfd(0, EFD_NONBLOCK).ensureUnixCallResult("eventfd") { it >= 0 }

        buffer[0].events = POLLIN
        buffer[0].data.fd = wakeupFd
        epoll_ctl(epfd, EPOLL_CTL_ADD, wakeupFd, buffer[0].ptr).ensureUnixCallResult("epoll_ctl(eventfd)") { it == 0 }
    }

    fun wakeup() {
        memScoped {
            val i = alloc<LongVar>()
            i.value = 1
            write(wakeupFd, i.ptr, 8).ensureUnixCallResult("write(eventfd)") { it == 8L }
        }
    }

    fun register(fd: Int, interest: Int) {
        val sk = keys.getOrPut(fd) { SelectionKey(fd) }
        sk.readyOps = 0

        buffer[0].events = interest
        buffer[0].data.fd = fd
        epoll_ctl(epfd, EPOLL_CTL_ADD, fd, buffer[0].ptr).ensureUnixCallResult("epoll_ctl") { it == 0 }
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
            } else if (ev.data.fd == wakeupFd) {
                memScoped {
                    val b = alloc<LongVar>()
                    read(wakeupFd, b.ptr, 8)
                }
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

        if (wakeupFd != -1) {
            close(wakeupFd)
            wakeupFd = -1
        }
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
        selector.register(socket, POLLIN)
        
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
