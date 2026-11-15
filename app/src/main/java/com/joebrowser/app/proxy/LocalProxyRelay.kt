package com.joebrowser.app.proxy

import android.util.Base64
import android.util.Log
import com.joebrowser.app.data.ProxyConfig
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Loopback CONNECT relay: `127.0.0.1:<port>` -> the user's proxy (HTTP/HTTPS/SOCKS5, with auth).
 *
 * Why a relay exists at all: Android's WebView can be pointed at a proxy, but it cannot be
 * given proxy credentials. The relay accepts plain loopback connections from the WebView,
 * injects `Proxy-Authorization` (or the SOCKS5 user/pass sub-negotiation) upstream, and
 * splices bytes in both directions. Credentials therefore never appear on the WebView's
 * command line, in a shared Authenticator, or in a proxy URL the page could observe.
 *
 * Correctness guarantees:
 *  - One relay instance per profile/browser session; [close] tears down the listener, every
 *    accepted socket and both pump threads.
 *  - Idle connections time out (45 s) so a stalled upstream cannot pin a thread forever.
 *  - Every socket operation is individually guarded; a failure closes that connection only.
 */
class LocalProxyRelay private constructor(private val upstream: ProxyConfig) : Closeable {

    private val server = ServerSocket(0, 32, InetAddress.getLoopbackAddress())
    private val running = AtomicBoolean(true)
    private val acceptExecutor = Executors.newSingleThreadExecutor()
    private val clientExecutor = Executors.newCachedThreadPool()
    private val openSockets = java.util.Collections.synchronizedSet(mutableSetOf<Socket>())
    private val connectionCount = AtomicLong(0)

    val port: Int get() = server.localPort
    val connectionsHandled: Long get() = connectionCount.get()
    val isRunning: Boolean get() = running.get()

    fun start(): LocalProxyRelay {
        acceptExecutor.execute {
            while (running.get()) {
                val client = try {
                    server.accept()
                } catch (t: Throwable) {
                    if (running.get()) Log.w(TAG, "accept failed", t)
                    break
                }
                clientExecutor.execute { handleSafely(client) }
            }
        }
        return this
    }

    fun loopbackHost(): String = "127.0.0.1"

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        runCatching { server.close() }
        acceptExecutor.shutdownNow()
        synchronized(openSockets) {
            openSockets.toList().forEach { closeQuietly(it) }
            openSockets.clear()
        }
        clientExecutor.shutdownNow()
        runCatching { clientExecutor.awaitTermination(1500, TimeUnit.MILLISECONDS) }
    }

    // ------------------------------------------------------------- connections

    private fun handleSafely(client: Socket) {
        openSockets.add(client)
        try {
            client.soTimeout = SOCKET_TIMEOUT_MS
            client.tcpNoDelay = true
            handle(client)
        } catch (t: Throwable) {
            // A single broken connection is expected (page navigations, cancellations).
        } finally {
            openSockets.remove(client)
            closeQuietly(client)
        }
    }

    private fun handle(client: Socket) {
        val input = BufferedInputStream(client.getInputStream())
        val output = BufferedOutputStream(client.getOutputStream())
        val requestLine = readLine(input) ?: return
        connectionCount.incrementAndGet()

        if (requestLine.startsWith("CONNECT ", ignoreCase = true)) {
            val target = requestLine.split(Regex("\\s+")).getOrNull(1)
            drainHeaders(input)
            if (target == null) return respond(output, 400, "Bad Request")
            val upstreamSocket = connectUpstream(target)
                ?: return respond(output, 502, "Bad Gateway")
            openSockets.add(upstreamSocket)
            try {
                output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                output.flush()
                splice(client, upstreamSocket)
            } finally {
                openSockets.remove(upstreamSocket)
                closeQuietly(upstreamSocket)
            }
            return
        }

        // Plain HTTP in absolute form: rewrite to origin form and forward with credentials.
        val parts = requestLine.split(Regex("\\s+"))
        if (parts.size < 3) return respond(output, 400, "Bad Request")
        val method = parts[0]
        val absoluteUri = parts[1]
        val version = parts.subList(2, parts.size).joinToString(" ")
        val headers = readHeaders(input)

        if (!absoluteUri.startsWith("http://", ignoreCase = true)) {
            return respond(output, 405, "Method Not Allowed")
        }
        val withoutScheme = absoluteUri.substring("http://".length)
        val slash = withoutScheme.indexOf('/')
        val authority = if (slash >= 0) withoutScheme.substring(0, slash) else withoutScheme
        val path = if (slash >= 0) withoutScheme.substring(slash) else "/"

        val upstreamSocket = openUpstreamSocket() ?: return respond(output, 502, "Bad Gateway")
        openSockets.add(upstreamSocket)
        try {
            val upstreamOut = BufferedOutputStream(upstreamSocket.getOutputStream())
            upstreamOut.write(buildPlainRequest(method, path, version, authority, headers).toByteArray(Charsets.UTF_8))
            upstreamOut.flush()
            splice(client, upstreamSocket)
        } finally {
            openSockets.remove(upstreamSocket)
            closeQuietly(upstreamSocket)
        }
    }

    private fun buildPlainRequest(
        method: String,
        path: String,
        version: String,
        authority: String,
        headers: List<Pair<String, String>>
    ): String {
        val sb = StringBuilder()
        sb.append(method).append(' ').append(path).append(' ').append(version).append("\r\n")
        sb.append("Host: ").append(authority).append("\r\n")
        appendProxyAuthorization(sb)
        headers.forEach { (name, value) ->
            val lower = name.lowercase()
            if (lower == "proxy-authorization" || lower == "proxy-connection" || lower == "connection" || lower == "host") return@forEach
            sb.append(name).append(": ").append(value).append("\r\n")
        }
        sb.append("Connection: close\r\n\r\n")
        return sb.toString()
    }

    private fun appendProxyAuthorization(sb: StringBuilder) {
        if (upstream.user.isNotBlank() || upstream.password.isNotEmpty()) {
            val token = Base64.encodeToString(
                "${upstream.user}:${upstream.password}".toByteArray(Charsets.UTF_8), Base64.NO_WRAP
            )
            sb.append("Proxy-Authorization: Basic ").append(token).append("\r\n")
        }
    }

    private fun connectUpstream(target: String): Socket? {
        val (host, port) = parseTarget(target) ?: return null
        return if (upstream.type == "socks5") connectViaSocks5(host, port) else connectViaConnect(host, port)
    }

    private fun openUpstreamSocket(): Socket? = try {
        Socket().apply {
            connect(InetSocketAddress(upstream.host, upstream.port), CONNECT_TIMEOUT_MS)
            soTimeout = SOCKET_TIMEOUT_MS
            tcpNoDelay = true
        }
    } catch (t: Throwable) {
        null
    }

    private fun connectViaConnect(host: String, port: Int): Socket? {
        val socket = openUpstreamSocket() ?: return null
        return try {
            val out = socket.getOutputStream()
            val input = BufferedInputStream(socket.getInputStream())
            val sb = StringBuilder()
            sb.append("CONNECT ").append(host).append(':').append(port).append(" HTTP/1.1\r\n")
            sb.append("Host: ").append(host).append(':').append(port).append("\r\n")
            appendProxyAuthorization(sb)
            sb.append("User-Agent: JoeBrowser/2 (proxy-tunnel)\r\n")
            sb.append("Connection: Keep-Alive\r\n\r\n")
            out.write(sb.toString().toByteArray(Charsets.UTF_8))
            out.flush()
            val status = readLine(input) ?: throw IOException("no CONNECT response")
            val code = status.split(Regex("\\s+")).getOrNull(1)?.toIntOrNull() ?: 0
            drainHeaders(input)
            if (code in 200..299) socket else { closeQuietly(socket); null }
        } catch (t: Throwable) {
            closeQuietly(socket)
            null
        }
    }

    private fun connectViaSocks5(host: String, port: Int): Socket? {
        val socket = openUpstreamSocket() ?: return null
        return try {
            val out = socket.getOutputStream()
            val input = socket.getInputStream()
            val needsAuth = upstream.user.isNotBlank() || upstream.password.isNotEmpty()

            out.write(if (needsAuth) byteArrayOf(0x05, 0x02, 0x00, 0x02) else byteArrayOf(0x05, 0x01, 0x00))
            out.flush()
            val greeting = ByteArray(2)
            if (!readFully(input, greeting)) throw IOException("SOCKS greeting failed")
            if (greeting[0] != 0x05.toByte()) throw IOException("not a SOCKS5 endpoint")

            when (greeting[1].toInt() and 0xFF) {
                0x00 -> Unit
                0x02 -> {
                    val user = upstream.user.toByteArray(Charsets.UTF_8)
                    val pass = upstream.password.toByteArray(Charsets.UTF_8)
                    if (user.size > 255 || pass.size > 255) throw IOException("SOCKS credential too long")
                    val request = ByteArray(3 + user.size + pass.size)
                    request[0] = 0x01
                    request[1] = user.size.toByte()
                    System.arraycopy(user, 0, request, 2, user.size)
                    request[2 + user.size] = pass.size.toByte()
                    System.arraycopy(pass, 0, request, 3 + user.size, pass.size)
                    out.write(request)
                    out.flush()
                    val authReply = ByteArray(2)
                    if (!readFully(input, authReply) || authReply[1] != 0x00.toByte()) {
                        throw IOException("SOCKS authentication rejected")
                    }
                }
                else -> throw IOException("SOCKS: no acceptable auth method")
            }

            val hostBytes = host.toByteArray(Charsets.UTF_8)
            if (hostBytes.size > 255) throw IOException("SOCKS host too long")
            val connect = ByteArray(7 + hostBytes.size)
            connect[0] = 0x05
            connect[1] = 0x01
            connect[2] = 0x00
            connect[3] = 0x03
            connect[4] = hostBytes.size.toByte()
            System.arraycopy(hostBytes, 0, connect, 5, hostBytes.size)
            connect[5 + hostBytes.size] = ((port shr 8) and 0xFF).toByte()
            connect[6 + hostBytes.size] = (port and 0xFF).toByte()
            out.write(connect)
            out.flush()

            val head = ByteArray(4)
            if (!readFully(input, head)) throw IOException("SOCKS connect: no reply")
            if (head[1] != 0x00.toByte()) throw IOException("SOCKS refused (%d)".format(head[1].toInt()))
            val addressLength = when (head[3].toInt() and 0xFF) {
                0x01 -> 4
                0x04 -> 16
                0x03 -> {
                    val length = ByteArray(1)
                    if (!readFully(input, length)) throw IOException("SOCKS: truncated reply")
                    length[0].toInt() and 0xFF
                }
                else -> 4
            }
            if (!readFully(input, ByteArray(addressLength + 2))) throw IOException("SOCKS: truncated bound address")
            socket
        } catch (t: Throwable) {
            closeQuietly(socket)
            null
        }
    }

    /** Copies bytes in both directions until either side closes. */
    private fun splice(a: Socket, b: Socket) {
        val first = Thread({ pump(a, b) }, "joe-relay-down")
        val second = Thread({ pump(b, a) }, "joe-relay-up")
        first.isDaemon = true
        second.isDaemon = true
        first.start()
        second.start()
        runCatching { first.join(SPLICE_LIFETIME_MS) }
        closeQuietly(a)
        closeQuietly(b)
        second.interrupt()
    }

    private fun pump(from: Socket, to: Socket) {
        val buffer = ByteArray(16 * 1024)
        try {
            val input: InputStream = from.getInputStream()
            val output: OutputStream = BufferedOutputStream(to.getOutputStream())
            while (running.get()) {
                val read = input.read(buffer)
                if (read <= 0) break
                output.write(buffer, 0, read)
                output.flush()
            }
        } catch (t: Throwable) {
            // Normal termination path for HTTP/1.0 or a cancelled navigation.
        } finally {
            runCatching { to.shutdownOutput() }
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun respond(output: BufferedOutputStream, code: Int, message: String) {
        runCatching {
            output.write("HTTP/1.1 $code $message\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            output.flush()
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        var previous = -1
        while (true) {
            val current = try {
                input.read()
            } catch (t: Throwable) {
                return null
            }
            if (current == -1) return if (sb.isEmpty()) null else sb.toString()
            if (previous == '\r'.code && current == '\n'.code) {
                sb.setLength(sb.length - 1)
                return sb.toString()
            }
            if (current != '\r'.code) sb.append(current.toChar())
            previous = current
            if (sb.length > MAX_HEADER_BYTES) return null
        }
    }

    private fun drainHeaders(input: InputStream) {
        var guard = 0
        while (guard++ < 200) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) return
        }
    }

    private fun readHeaders(input: InputStream): List<Pair<String, String>> {
        val headers = mutableListOf<Pair<String, String>>()
        var guard = 0
        while (guard++ < 200) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers.add(line.substring(0, colon).trim() to line.substring(colon + 1).trim())
        }
        return headers
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read <= 0) return false
            offset += read
        }
        return true
    }

    private fun parseTarget(target: String): Pair<String, Int>? {
        if (target.startsWith("[")) {
            val end = target.indexOf(']')
            if (end <= 0) return null
            val host = target.substring(1, end)
            val port = target.substring(end + 1).removePrefix(":").toIntOrNull() ?: return null
            return if (port in 1..65535) host to port else null
        }
        val index = target.lastIndexOf(':')
        if (index <= 0) return null
        val port = target.substring(index + 1).toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        return target.substring(0, index) to port
    }

    private fun closeQuietly(socket: Socket?) {
        if (socket == null) return
        runCatching { socket.close() }
    }

    companion object {
        private const val TAG = "JoeRelay"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val SOCKET_TIMEOUT_MS = 45_000
        private const val SPLICE_LIFETIME_MS = 5 * 60_000L
        private const val MAX_HEADER_BYTES = 16 * 1024

        fun start(upstream: ProxyConfig): LocalProxyRelay = LocalProxyRelay(upstream).start()

        /** True when [config] is usable as an upstream proxy. */
        fun canRelay(config: ProxyConfig): Boolean = config.enabled
    }
}
