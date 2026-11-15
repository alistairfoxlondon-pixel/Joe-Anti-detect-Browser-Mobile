package com.joebrowser.app.proxy

import android.util.Base64
import com.joebrowser.app.data.ProxyConfig
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Local CONNECT relay: 127.0.0.1:<port> -> the user's proxy (HTTP/HTTPS/SOCKS5, with auth).
 * WebView cannot attach credentials to a proxy override; the relay injects them, so proxy
 * passwords never touch the WebView command line or shared surfaces. Same design as the
 * desktop build's relay.
 */
class LocalProxyRelay private constructor(private val upstream: ProxyConfig) : Closeable {

    private val server = ServerSocket(0, 32, InetAddress.getLoopbackAddress())
    @Volatile private var running = true
    private val executor = Executors.newCachedThreadPool()

    val port: Int get() = server.localPort

    fun start(): LocalProxyRelay {
        Thread({
            while (running) {
                val client = try {
                    server.accept()
                } catch (e: IOException) {
                    break
                }
                executor.execute { handleSafely(client) }
            }
        }, "joe-relay-accept").apply { isDaemon = true }.start()
        return this
    }

    override fun close() {
        running = false
        try { server.close() } catch (_: Exception) {}
        executor.shutdownNow()
    }

    private fun handleSafely(client: Socket) {
        try {
            client.soTimeout = 45_000
            handle(client)
        } catch (_: Exception) {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun handle(client: Socket) {
        val input = BufferedInputStream(client.getInputStream())
        val output = BufferedOutputStream(client.getOutputStream())
        val requestLine = readHeaderLine(input) ?: return client.close()

        if (requestLine.startsWith("CONNECT ", ignoreCase = true)) {
            val target = requestLine.split(Regex("\\s+")).getOrNull(1) ?: return respond(output, 400, "Bad Request")
            readUntilBlankLine(input)
            val upstreamSocket = connectViaUpstream(target) ?: return respond(output, 502, "Bad Gateway")
            try {
                output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                output.flush()
                splice(client, upstreamSocket)
            } catch (e: Exception) {
                try { upstreamSocket.close() } catch (_: Exception) {}
                client.close()
            }
            return
        }

        // Plain HTTP proxying in absolute-form: forward to the proxy with credentials.
        val parts = requestLine.split(Regex("\\s+"))
        if (parts.size < 3) return respond(output, 400, "Bad Request")
        val method = parts[0]
        val uri = parts[1]
        val version = parts.subList(2, parts.size).joinToString(" ")
        val headers = readHeaders(input)

        if (!uri.startsWith("http://", ignoreCase = true)) {
            return respond(output, 405, "Method Not Allowed")
        }
        val withoutScheme = uri.substring("http://".length)
        val slash = withoutScheme.indexOf('/')
        val authority = if (slash >= 0) withoutScheme.substring(0, slash) else withoutScheme
        val path = if (slash >= 0) withoutScheme.substring(slash) else "/"
        val upstreamSocket = openUpstreamPlain() ?: return respond(output, 502, "Bad Gateway")
        try {
            val up = BufferedOutputStream(upstreamSocket.getOutputStream())
            val sb = StringBuilder()
            sb.append("$method $path $version\r\n")
            sb.append("Host: $authority\r\n")
            addProxyAuthorization(sb)
            for ((k, v) in headers) {
                if (k.equals("proxy-authorization", true) || k.equals("proxy-connection", true) || k.equals("connection", true)) continue
                if (k.equals("host", true)) continue
                sb.append("$k: $v\r\n")
            }
            sb.append("Connection: close\r\n\r\n")
            up.write(sb.toString().toByteArray(Charsets.UTF_8))
            up.flush()
            splice(client, upstreamSocket)
        } catch (e: Exception) {
            try { upstreamSocket.close() } catch (_: Exception) {}
            client.close()
        }
    }

    private fun addProxyAuthorization(sb: StringBuilder) {
        if (upstream.user.isNotBlank() || upstream.password.isNotEmpty()) {
            val token = Base64.encodeToString("${upstream.user}:${upstream.password}".toByteArray(), Base64.NO_WRAP)
            sb.append("Proxy-Authorization: Basic $token\r\n")
        }
    }

    private fun openUpstreamPlain(): Socket? = try {
        Socket().apply {
            connect(java.net.InetSocketAddress(upstream.host, upstream.port), 8_000)
            soTimeout = 45_000
        }
    } catch (e: Exception) {
        null
    }

    private fun connectViaUpstream(target: String): Socket? {
        val (host, port) = parseTarget(target) ?: return null
        return if (upstream.type == "socks5") connectViaSocks5(host, port) else connectViaHttpConnect(host, port)
    }

    private fun connectViaHttpConnect(host: String, port: Int): Socket? = try {
        val socket = Socket()
        socket.connect(java.net.InetSocketAddress(upstream.host, upstream.port), 8_000)
        socket.soTimeout = 45_000
        val out = socket.getOutputStream()
        val inp = BufferedInputStream(socket.getInputStream())
        val sb = StringBuilder()
        sb.append("CONNECT $host:$port HTTP/1.1\r\n")
        sb.append("Host: $host:$port\r\n")
        addProxyAuthorization(sb)
        sb.append("User-Agent: JoeBrowser/1.0\r\n")
        sb.append("Connection: Keep-Alive\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
        out.flush()
        val statusLine = readHeaderLine(inp) ?: throw IOException("no response")
        val code = statusLine.split(Regex("\\s+")).getOrNull(1)?.toIntOrNull() ?: 0
        readUntilBlankLine(inp)
        if (code in 200..299) socket else { socket.close(); null }
    } catch (e: Exception) {
        null
    }

    private fun connectViaSocks5(host: String, port: Int): Socket? = try {
        val socket = Socket()
        socket.connect(java.net.InetSocketAddress(upstream.host, upstream.port), 8_000)
        socket.soTimeout = 45_000
        val out = socket.getOutputStream()
        val inp = socket.getInputStream()

        val needsAuth = upstream.user.isNotBlank() || upstream.password.isNotEmpty()
        // Greeting: offer no-auth and user/pass
        out.write(if (needsAuth) byteArrayOf(0x05, 0x02, 0x00, 0x02) else byteArrayOf(0x05, 0x01, 0x00))
        out.flush()
        val greet = ByteArray(2)
        if (!readFully(inp, greet)) throw IOException("greeting failed")
        if (greet[0] != 0x05.toByte()) throw IOException("bad socks version")
        when (greet[1].toInt() and 0xFF) {
            0x00 -> Unit // no auth
            0x02 -> {
                val u = upstream.user.toByteArray(Charsets.UTF_8)
                val p = upstream.password.toByteArray(Charsets.UTF_8)
                val req = ByteArray(3 + u.size + p.size)
                req[0] = 0x01; req[1] = u.size.toByte()
                System.arraycopy(u, 0, req, 2, u.size)
                req[2 + u.size] = p.size.toByte()
                System.arraycopy(p, 0, req, 3 + u.size, p.size)
                out.write(req)
                out.flush()
                val authResp = ByteArray(2)
                if (!readFully(inp, authResp) || authResp[1] != 0x00.toByte()) throw IOException("socks auth rejected")
            }
            else -> throw IOException("no acceptable socks auth method")
        }
        // CONNECT host:port
        val hostBytes = host.toByteArray(Charsets.UTF_8)
        val req = ByteArray(7 + hostBytes.size) // ver cmd rsv atyp len host port(2)
        req[0] = 0x05; req[1] = 0x01; req[2] = 0x00; req[3] = 0x03
        req[4] = hostBytes.size.toByte()
        System.arraycopy(hostBytes, 0, req, 5, hostBytes.size)
        req[5 + hostBytes.size] = ((port shr 8) and 0xFF).toByte()
        req[6 + hostBytes.size] = (port and 0xFF).toByte()
        out.write(req)
        out.flush()
        val head = ByteArray(4)
        if (!readFully(inp, head)) throw IOException("socks connect refused")
        if (head[1] != 0x00.toByte()) throw IOException("socks connect error ${head[1]}")
        // drain bound addr
        val alen = when (head[3].toInt() and 0xff) {
            0x01 -> 4; 0x04 -> 16; 0x03 -> { val l = ByteArray(1); if (!readFully(inp, l)) throw IOException(); l[0].toInt() and 0xff }
            else -> 4
        }
        if (!readFully(inp, ByteArray(alen + 2))) throw IOException()
        socket
    } catch (e: Exception) {
        null
    }

    private fun splice(a: Socket, b: Socket) {
        val t1 = Thread({ pump(a.getInputStream(), b.getOutputStream(), b) }, "joe-relay-a")
        val t2 = Thread({ pump(b.getInputStream(), a.getOutputStream(), a) }, "joe-relay-b")
        t1.isDaemon = true; t2.isDaemon = true
        t1.start(); t2.start()
        t1.join(TimeUnit.MINUTES.toMillis(5))
        closeQuietly(a); closeQuietly(b)
    }

    private fun pump(input: InputStream, output: OutputStream, closeAfter: Socket) {
        val buffer = ByteArray(16 * 1024)
        try {
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                output.write(buffer, 0, n)
                output.flush()
            }
        } catch (_: Exception) {
        } finally {
            try { output.flush() } catch (_: Exception) {}
            try { closeAfter.shutdownOutput() } catch (_: Exception) {}
        }
    }

    private fun readHeaderLine(input: InputStream): String? {
        val sb = StringBuilder()
        var prev = -1
        while (true) {
            val c = input.read()
            if (c == -1) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code && prev == '\r'.code) { sb.deleteCharAt(sb.length - 1); return sb.toString() }
            sb.append(c.toChar())
            prev = c
            if (sb.length > 16 * 1024) return sb.toString()
        }
    }

    private fun readUntilBlankLine(input: InputStream) {
        while (true) {
            val line = readHeaderLine(input) ?: return
            if (line.isEmpty()) return
        }
    }

    private fun readHeaders(input: InputStream): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        while (true) {
            val line = readHeaderLine(input) ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) out.add(line.substring(0, idx).trim() to line.substring(idx + 1).trim())
        }
        return out
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var off = 0
        while (off < buffer.size) {
            val n = input.read(buffer, off, buffer.size - off)
            if (n <= 0) return false
            off += n
        }
        return true
    }

    private fun parseTarget(target: String): Pair<String, Int>? {
        val idx = target.lastIndexOf(':')
        if (idx <= 0) return null
        val port = target.substring(idx + 1).toIntOrNull() ?: return null
        return target.substring(0, idx) to port
    }

    private fun respond(output: BufferedOutputStream, code: Int, message: String) {
        try {
            output.write("HTTP/1.1 $code $message\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            output.flush()
        } catch (_: Exception) {}
    }

    private fun closeQuietly(s: Socket) {
        try { s.close() } catch (_: Exception) {}
    }

    companion object {
        fun start(upstream: ProxyConfig): LocalProxyRelay = LocalProxyRelay(upstream).start()
    }
}
