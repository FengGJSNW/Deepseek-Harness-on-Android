package dev.deepseek.mobile.runtime

import android.util.Base64
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject

/** Outgoing TCP broker for one VM. No filesystem, App tools, Shell or Root entry points. */
internal class RuntimeNetwork(
    private val scope: CoroutineScope,
    private val allowed: () -> Boolean,
    private val send: (JSONObject) -> Unit,
    private val proxyPort: () -> Int?,
) {
    private data class Stream(val socket: Socket, val queue: Channel<ByteArray>, val job: Job)

    private val streams = ConcurrentHashMap<Int, Stream>()

    private fun frame(type: Int, id: Int, body: ByteArray) =
        ByteBuffer.allocate(5 + body.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(type.toByte())
            .putInt(id)
            .put(body)
            .array()

    private fun emit(bytes: ByteArray) =
        send(
            JSONObject()
                .put("type", "network")
                .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP)),
        )

    private fun credit(id: Int) =
        emit(frame(3, id, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(32).array()))

    fun open() {
        credit(0)
    }

    fun receive(encoded: String) {
        val bytes = Base64.decode(encoded, Base64.DEFAULT)
        require(bytes.size in 5..131072)
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val type = data.get().toInt() and 255
        val id = data.int
        if (!allowed()) {
            close(id, 0x48)
            return
        }
        when (type) {
            1 -> {
                if (
                    id <= 0 ||
                        streams.containsKey(id) ||
                        streams.size >= 32 ||
                        bytes.size < 9 ||
                        bytes[5].toInt() != 1
                ) {
                    close(id, 0x41)
                    return
                }
                data.get()
                val port = data.short.toInt() and 65535
                val host = String(bytes, 8, bytes.size - 8, Charsets.UTF_8)
                require(
                    host.length in 1..253 &&
                        host.none { it.isWhitespace() || it == '\u0000' } &&
                        port > 0,
                )
                val socket = Socket()
                val queue = Channel<ByteArray>(32)
                val job =
                    scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                        try {
                            check(allowed())
                            val destination =
                                if (
                                    host == dev.deepseek.mobile.proxy.MihomoManager.guestProxyHost
                                ) {
                                    check(proxyPort() == port) { "当前代理未启用" }
                                    "127.0.0.1"
                                } else host
                            socket.connect(InetSocketAddress(destination, port), 15000)
                            socket.tcpNoDelay = true
                            socket.soTimeout = 1000
                            coroutineScope {
                                val writer = launch {
                                    var received = 0
                                    for (chunk in queue) {
                                        check(allowed())
                                        socket.getOutputStream().write(chunk)
                                        if (++received % 16 == 0) credit(id)
                                    }
                                }
                                try {
                                    val buffer = ByteArray(16384)
                                    while (isActive && allowed()) {
                                        val n =
                                            try {
                                                socket.getInputStream().read(buffer)
                                            } catch (_: java.net.SocketTimeoutException) {
                                                continue
                                            }
                                        if (n < 0 || !allowed()) break
                                        emit(frame(2, id, buffer.copyOf(n)))
                                    }
                                } finally {
                                    writer.cancel()
                                    queue.close()
                                    socket.close()
                                }
                            }
                        } catch (_: Exception) {
                            /* CLOSE below is the protocol-visible error. */
                        } finally {
                            close(id, 3)
                        }
                    }
                streams[id] = Stream(socket, queue, job)
                job.start()
            }
            2 -> {
                val stream = streams[id] ?: return
                if (!stream.queue.trySend(bytes.copyOfRange(5, bytes.size)).isSuccess)
                    close(id, 0x49)
            }
            4 -> close(id, 2)
        }
    }

    suspend fun dns(encoded: String): String =
        withContext(Dispatchers.IO) {
            check(allowed()) { "Sandbox network is disabled" }
            val query = Base64.decode(encoded, Base64.DEFAULT)
            if (dev.deepseek.mobile.BuildConfig.DEBUG)
                android.util.Log.d("DSHA-Network", "Guest DNS query received (${query.size} bytes)")
            require(query.size in 17..4096)
            val input = java.nio.ByteBuffer.wrap(query).order(java.nio.ByteOrder.BIG_ENDIAN)
            val identifier = input.short
            val flags = input.short.toInt() and 65535
            require(flags and 0xF800 == 0 && input.short.toInt() == 1)
            input.position(12)
            val labels = mutableListOf<String>()
            while (true) {
                val n = input.get().toInt() and 255
                if (n == 0) break
                require(n in 1..63 && input.remaining() >= n + 5)
                val bytes = ByteArray(n)
                input.get(bytes)
                val label = String(bytes, Charsets.US_ASCII)
                require(label.all { it.isLetterOrDigit() || it == '-' || it == '_' })
                labels += label
            }
            val type = input.short.toInt() and 65535
            val klass = input.short.toInt() and 65535
            require(klass == 1)
            val end = input.position()
            val host = labels.joinToString(".")
            require(host.length in 1..253)
            var code = 0
            val addresses =
                try {
                    java.net.InetAddress.getAllByName(host)
                        .map { it.address }
                        .filter { (type == 1 && it.size == 4) || (type == 28 && it.size == 16) }
                        .take(16)
                } catch (_: java.net.UnknownHostException) {
                    code = 3
                    emptyList()
                }
            val bytes = java.io.ByteArrayOutputStream()
            java.io.DataOutputStream(bytes).use { out ->
                out.writeShort(identifier.toInt())
                out.writeShort(0x8080 or (flags and 0x0100) or code)
                out.writeShort(1)
                out.writeShort(addresses.size)
                out.writeShort(0)
                out.writeShort(0)
                out.write(query, 12, end - 12)
                addresses.forEach { address ->
                    out.writeShort(0xC00C)
                    out.writeShort(type)
                    out.writeShort(1)
                    out.writeInt(60)
                    out.writeShort(address.size)
                    out.write(address)
                }
            }
            Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
        }

    private fun close(id: Int, reason: Int) {
        streams.remove(id)?.let { stream ->
            stream.queue.close()
            runCatching { stream.socket.close() }
            stream.job.cancel()
        }
        emit(frame(4, id, byteArrayOf(reason.toByte())))
    }

    fun close() {
        streams.keys.toList().forEach { close(it, 2) }
    }
}
