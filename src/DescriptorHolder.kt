package kotlinx.native.nio

import netinet.*

abstract class DescriptorHolder : Closeable {
    private var _fd: Int = -1
    val fd: Int
        get() = if (_fd == -1) throw IllegalStateException("No descriptor") else _fd

    val hasDescriptor: Boolean get() = _fd != -1

    protected fun fd(provider: () -> Int): Int {
        if (_fd == -1) _fd = provider()

        return fd
    }

    protected fun closeDescriptor() {
        if (_fd != -1) {
            close(_fd)
            _fd = -1
        }
    }

    override fun close() {
        closeDescriptor()
    }
}
