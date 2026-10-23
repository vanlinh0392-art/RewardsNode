package com.agy.rewardsnode.core

import java.io.*
import java.net.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Native Loopback Proxy Bridge (100% Android SDK thuần, Zero External Dependencies).
 * Quản lý ServerSocket cục bộ trên 127.0.0.1, chuyển tiếp toàn bộ lưu lượng WebView
 * tới Upstream Residential Proxy qua HTTP CONNECT (với Proxy-Authorization Basic)
 * hoặc SOCKS5 Handshake (với User/Password RFC 1929 & Domain Name RFC 1928).
 * Khắc phục triệt để lỗ hổng Ghost Proxy (VULN-SEC-02).
 */
class LoopbackProxyBridge private constructor() {

    enum class UpstreamType { HTTP, SOCKS5 }

    data class UpstreamConfig(
        val type: UpstreamType,
        val host: String,
        val port: Int,
        val username: String? = null,
        val password: String? = null
    )

    private var serverSocket: ServerSocket? = null
    private var currentProxyUrl: String? = null
    private var currentConfig: UpstreamConfig? = null
    @Volatile private var isRunning = false

    private val executor: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "LoopbackProxyBridge-Worker").apply { isDaemon = true }
    }

    val localPort: Int
        get() = serverSocket?.localPort ?: 0

    val isAlive: Boolean
        get() = isRunning && serverSocket != null && !(serverSocket?.isClosed ?: true)

    val activeProxyUrl: String?
        get() = currentProxyUrl

    @Synchronized
    fun start(proxyUrl: String): Int {
        val sanitized = proxyUrl.trim()
        if (sanitized.isBlank()) {
            stop()
            return 0
        }

        if (isAlive && currentProxyUrl == sanitized && serverSocket != null) {
            return serverSocket!!.localPort
        }

        stop()

        val config = parseProxyUrl(sanitized) ?: throw IllegalArgumentException("Cấu hình Proxy không hợp lệ: $proxyUrl")
        currentProxyUrl = sanitized
        currentConfig = config

        val server = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
        serverSocket = server
        isRunning = true

        executor.submit {
            while (isRunning && !server.isClosed) {
                try {
                    val clientSocket = server.accept()
                    clientSocket.soTimeout = 30000
                    executor.submit {
                        handleClient(clientSocket, config)
                    }
                } catch (e: Exception) {
                    if (!isRunning) break
                }
            }
        }

        logInfo(TAG, "LoopbackProxyBridge đã lắng nghe trên 127.0.0.1:${server.localPort} -> Upstream ${config.type}://${config.host}:${config.port}")
        return server.localPort
    }

    @Synchronized
    fun restart(proxyUrl: String): Int {
        stop()
        return start(proxyUrl)
    }

    @Synchronized
    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        currentProxyUrl = null
        currentConfig = null
    }

    private fun handleClient(clientSocket: Socket, config: UpstreamConfig) {
        try {
            val clientIn = clientSocket.getInputStream()
            val clientOut = clientSocket.getOutputStream()

            val requestLine = readAsciiLine(clientIn)
            if (requestLine.isBlank()) {
                clientSocket.close()
                return
            }

            val parts = requestLine.split(" ")
            if (parts.size < 2) {
                clientSocket.close()
                return
            }

            val method = parts[0].uppercase()
            val uri = parts[1]

            // Đọc toàn bộ Header từ client đến khi gặp dòng trống
            val headers = mutableListOf<String>()
            while (true) {
                val line = readAsciiLine(clientIn)
                if (line.isEmpty()) break
                headers.add(line)
            }

            if (method == "CONNECT") {
                handleConnectMethod(clientSocket, clientIn, clientOut, uri, config)
            } else {
                handlePlainHttpMethod(clientSocket, clientIn, clientOut, requestLine, headers, uri, config)
            }
        } catch (e: Exception) {
            try { clientSocket.close() } catch (_: Exception) {}
        }
    }

    private fun handleConnectMethod(
        clientSocket: Socket,
        clientIn: InputStream,
        clientOut: OutputStream,
        uri: String,
        config: UpstreamConfig
    ) {
        val targetHost = if (uri.contains(":")) uri.substringBefore(":") else uri
        val targetPort = if (uri.contains(":")) uri.substringAfter(":").toIntOrNull() ?: 443 else 443

        var upstreamSocket: Socket? = null
        try {
            upstreamSocket = Socket()
            upstreamSocket.connect(InetSocketAddress(config.host, config.port), 15000)
            upstreamSocket.soTimeout = 30000

            val upIn = upstreamSocket.getInputStream()
            val upOut = upstreamSocket.getOutputStream()

            when (config.type) {
                UpstreamType.HTTP -> {
                    val req = StringBuilder()
                    req.append("CONNECT $targetHost:$targetPort HTTP/1.1\r\n")
                    req.append("Host: $targetHost:$targetPort\r\n")
                    if (!config.username.isNullOrEmpty() && !config.password.isNullOrEmpty()) {
                        val auth = encodeBase64("${config.username}:${config.password}".toByteArray(Charsets.UTF_8))
                        req.append("Proxy-Authorization: Basic $auth\r\n")
                    }
                    req.append("Proxy-Connection: Keep-Alive\r\n\r\n")
                    upOut.write(req.toString().toByteArray(Charsets.US_ASCII))
                    upOut.flush()

                    val statusLine = readAsciiLine(upIn)
                    if (!statusLine.contains(" 200")) {
                        throw IOException("Upstream HTTP Proxy từ chối CONNECT: $statusLine")
                    }
                    while (true) {
                        val line = readAsciiLine(upIn)
                        if (line.isEmpty()) break
                    }
                }
                UpstreamType.SOCKS5 -> {
                    performSocks5Handshake(upIn, upOut, config, targetHost, targetPort)
                }
            }

            clientOut.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.US_ASCII))
            clientOut.flush()

            pipeBidirectional(clientSocket, upstreamSocket, clientIn, clientOut, upIn, upOut)

        } catch (e: Exception) {
            try {
                val errorMsg = "HTTP/1.1 502 Bad Gateway\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\nLoopbackProxyBridge Error: ${e.message}\r\n"
                clientOut.write(errorMsg.toByteArray(Charsets.US_ASCII))
                clientOut.flush()
            } catch (_: Exception) {}
            try { upstreamSocket?.close() } catch (_: Exception) {}
            try { clientSocket.close() } catch (_: Exception) {}
        }
    }

    private fun handlePlainHttpMethod(
        clientSocket: Socket,
        clientIn: InputStream,
        clientOut: OutputStream,
        requestLine: String,
        headers: List<String>,
        uri: String,
        config: UpstreamConfig
    ) {
        var upstreamSocket: Socket? = null
        try {
            upstreamSocket = Socket()
            upstreamSocket.connect(InetSocketAddress(config.host, config.port), 15000)
            upstreamSocket.soTimeout = 30000

            val upIn = upstreamSocket.getInputStream()
            val upOut = upstreamSocket.getOutputStream()

            when (config.type) {
                UpstreamType.HTTP -> {
                    val req = StringBuilder()
                    req.append(requestLine).append("\r\n")
                    for (header in headers) {
                        if (!header.startsWith("Proxy-Authorization:", ignoreCase = true)) {
                            req.append(header).append("\r\n")
                        }
                    }
                    if (!config.username.isNullOrEmpty() && !config.password.isNullOrEmpty()) {
                        val auth = encodeBase64("${config.username}:${config.password}".toByteArray(Charsets.UTF_8))
                        req.append("Proxy-Authorization: Basic $auth\r\n")
                    }
                    req.append("\r\n")
                    upOut.write(req.toString().toByteArray(Charsets.US_ASCII))
                    upOut.flush()
                }
                UpstreamType.SOCKS5 -> {
                    var targetHost = ""
                    var targetPort = 80
                    val hostHeader = headers.firstOrNull { it.startsWith("Host:", ignoreCase = true) }
                    if (hostHeader != null) {
                        val hostVal = hostHeader.substringAfter(":").trim()
                        targetHost = if (hostVal.contains(":")) hostVal.substringBefore(":") else hostVal
                        targetPort = if (hostVal.contains(":")) hostVal.substringAfter(":").toIntOrNull() ?: 80 else 80
                    } else if (uri.startsWith("http://", ignoreCase = true)) {
                        val parsedUri = URI(uri)
                        targetHost = parsedUri.host ?: ""
                        targetPort = if (parsedUri.port > 0) parsedUri.port else 80
                    }

                    performSocks5Handshake(upIn, upOut, config, targetHost, targetPort)

                    val relativeUri = if (uri.startsWith("http://", ignoreCase = true)) {
                        val parsed = URI(uri)
                        val rawPath = parsed.rawPath.ifEmpty { "/" }
                        val rawQuery = if (parsed.rawQuery != null) "?${parsed.rawQuery}" else ""
                        "$rawPath$rawQuery"
                    } else uri

                    val parts = requestLine.split(" ")
                    val rewrittenLine = "${parts[0]} $relativeUri ${if (parts.size > 2) parts[2] else "HTTP/1.1"}"
                    val req = StringBuilder().append(rewrittenLine).append("\r\n")
                    for (h in headers) {
                        req.append(h).append("\r\n")
                    }
                    req.append("\r\n")
                    upOut.write(req.toString().toByteArray(Charsets.US_ASCII))
                    upOut.flush()
                }
            }

            pipeBidirectional(clientSocket, upstreamSocket, clientIn, clientOut, upIn, upOut)
        } catch (e: Exception) {
            try {
                val errorMsg = "HTTP/1.1 502 Bad Gateway\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\nLoopbackProxyBridge Error: ${e.message}\r\n"
                clientOut.write(errorMsg.toByteArray(Charsets.US_ASCII))
                clientOut.flush()
            } catch (_: Exception) {}
            try { upstreamSocket?.close() } catch (_: Exception) {}
            try { clientSocket.close() } catch (_: Exception) {}
        }
    }

    private fun performSocks5Handshake(
        upIn: InputStream,
        upOut: OutputStream,
        config: UpstreamConfig,
        targetHost: String,
        targetPort: Int
    ) {
        val hasAuth = !config.username.isNullOrEmpty()
        if (hasAuth) {
            upOut.write(byteArrayOf(0x05, 0x02, 0x00, 0x02))
        } else {
            upOut.write(byteArrayOf(0x05, 0x01, 0x00))
        }
        upOut.flush()

        val sVer = upIn.read()
        val sMethod = upIn.read()
        if (sVer != 0x05) throw IOException("SOCKS5 version không đúng: $sVer")

        if (sMethod == 0x02) {
            val userBytes = (config.username ?: "").toByteArray(Charsets.UTF_8)
            val passBytes = (config.password ?: "").toByteArray(Charsets.UTF_8)
            val authReq = ByteArray(3 + userBytes.size + passBytes.size)
            authReq[0] = 0x01
            authReq[1] = userBytes.size.toByte()
            System.arraycopy(userBytes, 0, authReq, 2, userBytes.size)
            val pOffset = 2 + userBytes.size
            authReq[pOffset] = passBytes.size.toByte()
            System.arraycopy(passBytes, 0, authReq, pOffset + 1, passBytes.size)
            upOut.write(authReq)
            upOut.flush()

            val aVer = upIn.read()
            val aStatus = upIn.read()
            if (aStatus != 0x00) throw IOException("SOCKS5 xác thực thất bại (status $aStatus)")
        } else if (sMethod != 0x00) {
            throw IOException("SOCKS5 phương thức xác thực không được hỗ trợ: $sMethod")
        }

        val isIpv4 = targetHost.matches(Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$"""))
        val baos = ByteArrayOutputStream()
        baos.write(0x05) // VER
        baos.write(0x01) // CMD: CONNECT
        baos.write(0x00) // RSV
        if (isIpv4) {
            baos.write(0x01) // ATYP: IPv4
            val ipParts = targetHost.split(".")
            for (p in ipParts) {
                baos.write(p.toInt() and 0xFF)
            }
        } else {
            baos.write(0x03) // ATYP: DOMAINNAME
            val domainBytes = targetHost.toByteArray(Charsets.US_ASCII)
            baos.write(domainBytes.size and 0xFF)
            baos.write(domainBytes)
        }
        baos.write((targetPort shr 8) and 0xFF)
        baos.write(targetPort and 0xFF)

        upOut.write(baos.toByteArray())
        upOut.flush()

        val repVer = upIn.read()
        val repCode = upIn.read()
        val repRsv = upIn.read()
        val repAtyp = upIn.read()
        if (repCode != 0x00) throw IOException("SOCKS5 connect thất bại với mã lỗi: $repCode")

        when (repAtyp) {
            0x01 -> {
                val b = ByteArray(4)
                readFully(upIn, b)
            }
            0x03 -> {
                val len = upIn.read()
                val b = ByteArray(len)
                readFully(upIn, b)
            }
            0x04 -> {
                val b = ByteArray(16)
                readFully(upIn, b)
            }
            else -> throw IOException("SOCKS5 ATYP không xác định: $repAtyp")
        }
        val p1 = upIn.read()
        val p2 = upIn.read()
    }

    private fun pipeBidirectional(
        clientSocket: Socket,
        upstreamSocket: Socket,
        clientIn: InputStream,
        clientOut: OutputStream,
        upIn: InputStream,
        upOut: OutputStream
    ) {
        val forwardFuture = executor.submit {
            try {
                val buf = ByteArray(16384)
                var len: Int
                while (clientIn.read(buf).also { len = it } != -1) {
                    upOut.write(buf, 0, len)
                    upOut.flush()
                }
            } catch (_: Exception) {
            } finally {
                try { upstreamSocket.shutdownOutput() } catch (_: Exception) {}
            }
        }

        try {
            val buf = ByteArray(16384)
            var len: Int
            while (upIn.read(buf).also { len = it } != -1) {
                clientOut.write(buf, 0, len)
                clientOut.flush()
            }
        } catch (_: Exception) {
        } finally {
            try { clientSocket.shutdownOutput() } catch (_: Exception) {}
        }

        forwardFuture.cancel(true)
        try { clientSocket.close() } catch (_: Exception) {}
        try { upstreamSocket.close() } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "LoopbackProxyBridge"

        @Volatile
        private var instance: LoopbackProxyBridge? = null

        fun getInstance(): LoopbackProxyBridge {
            return instance ?: synchronized(this) {
                instance ?: LoopbackProxyBridge().also { instance = it }
            }
        }

        fun parseProxyUrl(rawUrl: String): UpstreamConfig? {
            val trimmed = rawUrl.trim()
            if (trimmed.isEmpty()) return null

            var type = UpstreamType.HTTP
            var rest = trimmed

            if (rest.startsWith("socks5://", ignoreCase = true)) {
                type = UpstreamType.SOCKS5
                rest = rest.substring(9)
            } else if (rest.startsWith("socks://", ignoreCase = true)) {
                type = UpstreamType.SOCKS5
                rest = rest.substring(8)
            } else if (rest.startsWith("http://", ignoreCase = true)) {
                type = UpstreamType.HTTP
                rest = rest.substring(7)
            } else if (rest.startsWith("https://", ignoreCase = true)) {
                type = UpstreamType.HTTP
                rest = rest.substring(8)
            }

            rest = rest.trimEnd('/')

            var user: String? = null
            var pass: String? = null

            if (rest.contains("@")) {
                val authPart = rest.substringBeforeLast("@")
                rest = rest.substringAfterLast("@")
                if (authPart.contains(":")) {
                    user = try { URLDecoder.decode(authPart.substringBefore(":"), "UTF-8") } catch (_: Exception) { authPart.substringBefore(":") }
                    pass = try { URLDecoder.decode(authPart.substringAfter(":"), "UTF-8") } catch (_: Exception) { authPart.substringAfter(":") }
                } else {
                    user = try { URLDecoder.decode(authPart, "UTF-8") } catch (_: Exception) { authPart }
                }
            }

            val host = if (rest.contains(":")) rest.substringBefore(":") else rest
            val portStr = if (rest.contains(":")) rest.substringAfter(":") else ""
            val port = portStr.toIntOrNull() ?: if (type == UpstreamType.SOCKS5) 1080 else 8080

            if (host.isBlank()) return null
            return UpstreamConfig(type, host, port, user, pass)
        }

        private fun logInfo(tag: String, msg: String) {
            try {
                val logClass = Class.forName("android.util.Log")
                val iMethod = logClass.getMethod("i", String::class.java, String::class.java)
                iMethod.invoke(null, tag, msg)
            } catch (_: Throwable) {
                // Host test fallback
            }
        }

        private fun encodeBase64(bytes: ByteArray): String {
            return try {
                val clazz = Class.forName("android.util.Base64")
                val method = clazz.getMethod("encodeToString", ByteArray::class.java, Int::class.javaPrimitiveType)
                method.invoke(null, bytes, 2) as String // 2 = NO_WRAP
            } catch (_: Throwable) {
                // Java 8+ Stdlib fallback
                java.util.Base64.getEncoder().encodeToString(bytes)
            }
        }

        private fun readAsciiLine(input: InputStream): String {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b == -1) break
                if (b == '\n'.code) {
                    if (sb.isNotEmpty() && sb.last() == '\r') {
                        sb.setLength(sb.length - 1)
                    }
                    break
                }
                sb.append(b.toChar())
                if (sb.length > 8192) throw IOException("Request line vượt quá 8KB")
            }
            return sb.toString()
        }

        private fun readFully(input: InputStream, buffer: ByteArray) {
            var total = 0
            while (total < buffer.size) {
                val r = input.read(buffer, total, buffer.size - total)
                if (r < 0) throw EOFException("Unexpected EOF khi đọc socket SOCKS5")
                total += r
            }
        }
    }
}
