import epoll.*
import errno.*
import kotlinx.cinterop.*

import kotlinx.native.nio.*
import netinet.*
import stdlib.*

fun main(args: Array<String>) {
    if (args.size < 3) {
        println("Usage: ./Test.kexe (client | server) (echo | generate) port")
        return
    }
    
    val port = args[2].toInt()
    
    val selector = EPollSelector()
    try {
        selector.start()
        
        val behaviour = when (args[1]) {
            "echo" -> Behaviour.Echo(selector)
            "generate" -> Behaviour.Gen(selector)
            else -> {
                println("Wrong behaviour ${args[1]}, should be echo or generate")
                return
            }
        }
        
        val connector = when (args[0]) {
            "server" -> Connector.Server(port, selector, behaviour)
            "client" -> Connector.Client(port, selector, behaviour)
            else -> {
                println("Wrong connector ${args[0]}, should be client or server")
                return
            }
        }
        
        try {
            try {
                connector.setup()
                
                loop(selector, connector, behaviour)
            } finally {
                behaviour.close()
            }
        } finally {
            connector.close()
        }
    } catch (t: Throwable) {
        t.printStackTrace()
    } finally {
        selector.close()
    }
}

private tailrec fun loop(selector: Selector, connector: Connector, behaviour: Behaviour) {
    selector.wait(Int.MAX_VALUE)
    
    if (connector.loop() or behaviour.loop()) {
        loop(selector, connector, behaviour)
    }
}

class Connection(val s: SocketChannel, val sk: SelectionKey) {
    val buffer = ByteBuffer.allocateDirect(4096)
    var state: Int = 0
    
    fun close() {
        buffer.close()
        s.close()
    }
}

sealed class Behaviour(val selector: Selector) : Closeable {
    abstract fun started(client: SocketChannel, key: SelectionKey)
    abstract fun loop(): Boolean
    
    class Echo(selector: Selector) : Behaviour(selector) {
        private val allKeys = mutableSetOf<SelectionKey>()
        
        override fun started(client: SocketChannel, key: SelectionKey) {
            val sk = selector.interest(client.fd, POLLIN)
            val connection = Connection(client, sk)
            sk.attachment = connection
            allKeys.add(sk)
        }
        
        override fun loop(): Boolean {
            val toBeCancelled = mutableListOf<Connection>()
            
            
            for (k in selector.selected) {
                val connection = k.attachment as? Connection ?: continue                
                
                when (connection.state) {
                    0 -> { // reading
                        val rc = connection.s.read(connection.buffer)
                        
                        if (rc == -1) {
                            toBeCancelled.add(connection)
                            connection.state = 2
                        } else if (rc > 0) {
                            connection.buffer.flip()
                            connection.state = 1
                            selector.interest(k.fd, POLLOUT)
                        }
                    }
                    1 -> {  // writing
                        try {
                            val rc = connection.s.write(connection.buffer)
                            
                            if (rc > 0 && !connection.buffer.hasRemaining()) {                                                               
                                connection.buffer.clear()
                                connection.state = 0
                                selector.interest(k.fd, POLLIN)
                            }
                        } catch (t: Throwable) {
                            t.printStackTrace()
                            toBeCancelled.add(connection)
                            connection.state = 2
                        }
                    }
                    2 -> {
                        toBeCancelled.add(connection)
                    }
                }
            }
            
            for (c in toBeCancelled) {
                selector.cancel(c.sk)
                allKeys.remove(c.sk)
                
                c.close()
            }
            
            return allKeys.isNotEmpty()
        }
        
        override fun close() {
            for (k in allKeys) {
                val c = k.attachment as? Connection ?: continue
                selector.cancel(k)
                c.state = 2
                c.close()
            }
            allKeys.clear()
        }
    }
    
    class Gen(selector: Selector) : Behaviour(selector) {
        private val allKeys = mutableSetOf<SelectionKey>()
        private val charset: ByteArray = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-=_+!@#$%^&*;:".
                                            iterator().
                                            asSequence().
                                            map { it.toInt().toByte() }.
                                            toList().
                                            toByteArray() 
        
        private fun generateTo(bb: ByteBuffer) {
            val l = charset.size - 1
            val size = bb.remaining()
            
            for (i in 0..size - 1) {
                bb.put(charset[rand() % l])
            }
        }
        
        override fun started(client: SocketChannel, key: SelectionKey) {
            val sk = selector.interest(client.fd, POLLOUT)
            val connection = Connection(client, sk)
            sk.attachment = connection
            allKeys.add(sk)
            
            generateTo(connection.buffer)
        }
        
        override fun loop(): Boolean {
            val toBeCancelled = mutableListOf<Connection>()
            
            for (k in selector.selected) {
                val c = k.attachment as? Connection ?: continue
                val s = c.s
                
                try {
                    c.buffer.clear()
                    c.buffer.position(rand() % (c.buffer.limit() - 1))
                    
                    while (c.buffer.hasRemaining()) {
                        if (s.write(c.buffer) == 0) break
                    }
                } catch (t: Throwable) {
                    toBeCancelled.add(c)
                }
            }
            
            for (c in toBeCancelled) {
                selector.cancel(c.sk)
                allKeys.remove(c.sk)
                
                c.close()
            }
            
            return allKeys.isNotEmpty()
        }
        
        override fun close() {
            for (k in allKeys) {
                val c = k.attachment as? Connection ?: continue
                selector.cancel(k)
                c.state = 2
                c.close()
            }
            allKeys.clear()
        }
    }
}

sealed class Connector(val port: Int, val selector: Selector, val behaviour: Behaviour) : Closeable {
    abstract fun setup(): Unit
    abstract fun loop(): Boolean

    class Server(port: Int, selector: Selector, behaviour: Behaviour) : Connector(port, selector, behaviour) {
        private val s = ServerSocketChannel()
        private lateinit var sk: SelectionKey
        
        override fun setup() {
            s.configureBlocking(false)
            s.bind(port)
            sk = selector.interest(s.fd, POLLIN)
            println("Bound on port $port")
        }
        
        override fun loop(): Boolean {
            if (sk in selector.selected) {
                do {
                    val client = s.accept()
                    if (client == null) break
                    
                    client.configureBlocking(false)
                    behaviour.started(client, sk)
                } while (true)
            }
            
            return true
        }
        
        override fun close() {
            selector.cancel(sk)
            s.close()
        }
    }
    
    class Client(port: Int, selector: Selector, behaviour: Behaviour) : Connector(port, selector, behaviour) {
        private val s = SocketChannel()
        private lateinit var sk: SelectionKey
        private var connected = false
        
        override fun setup() {
            s.configureBlocking(false)
            connected = s.connect(InetSocketAddress(port))
            sk = selector.interest(s.fd, POLLIN)
        }
        
        override fun loop(): Boolean {
            if (!connected && sk in selector.selected) {
                connected = s.finishConnect()
                if (connected) {
                    behaviour.started(s, sk)
                }
            }
            
            return !connected
        }
        
        override fun close() {
            connected = true
            selector.cancel(sk)
            s.close()
        }
    }
}

