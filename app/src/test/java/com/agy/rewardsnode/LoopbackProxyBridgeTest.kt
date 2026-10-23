package com.agy.rewardsnode

import com.agy.rewardsnode.core.LoopbackProxyBridge
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket

class LoopbackProxyBridgeTest {

    @Test
    fun testParseProxyUrlHttpWithAuth() {
        val config = LoopbackProxyBridge.parseProxyUrl("http://user123:secret456@192.168.1.100:8080")
        assertNotNull(config)
        assertEquals(LoopbackProxyBridge.UpstreamType.HTTP, config!!.type)
        assertEquals("192.168.1.100", config.host)
        assertEquals(8080, config.port)
        assertEquals("user123", config.username)
        assertEquals("secret456", config.password)
    }

    @Test
    fun testParseProxyUrlSocks5WithAuth() {
        val config = LoopbackProxyBridge.parseProxyUrl("socks5://admin:pass%40word@proxy.example.com:10808")
        assertNotNull(config)
        assertEquals(LoopbackProxyBridge.UpstreamType.SOCKS5, config!!.type)
        assertEquals("proxy.example.com", config.host)
        assertEquals(10808, config.port)
        assertEquals("admin", config.username)
        assertEquals("pass@word", config.password)
    }

    @Test
    fun testParseProxyUrlPlainHostPort() {
        val config = LoopbackProxyBridge.parseProxyUrl("10.0.0.5:3128")
        assertNotNull(config)
        assertEquals(LoopbackProxyBridge.UpstreamType.HTTP, config!!.type)
        assertEquals("10.0.0.5", config.host)
        assertEquals(3128, config.port)
        assertNull(config.username)
        assertNull(config.password)
    }

    @Test
    fun testParseProxyUrlSocksSchemeDefaultPort() {
        val config = LoopbackProxyBridge.parseProxyUrl("socks://127.0.0.1")
        assertNotNull(config)
        assertEquals(LoopbackProxyBridge.UpstreamType.SOCKS5, config!!.type)
        assertEquals("127.0.0.1", config.host)
        assertEquals(1080, config.port)
    }

    @Test
    fun testParseProxyUrlInvalid() {
        assertNull(LoopbackProxyBridge.parseProxyUrl(""))
        assertNull(LoopbackProxyBridge.parseProxyUrl("   "))
    }

    @Test
    fun testLoopbackProxyBridgeStartAndStop() {
        val bridge = LoopbackProxyBridge.getInstance()
        val port = bridge.start("http://127.0.0.1:8888")
        assertTrue("Port must be positive", port > 0)
        assertTrue("Bridge must be alive", bridge.isAlive)
        assertEquals(port, bridge.localPort)

        // Starting with same proxyUrl should return same port
        val port2 = bridge.start("http://127.0.0.1:8888")
        assertEquals(port, port2)

        bridge.stop()
        assertFalse("Bridge should not be alive after stop", bridge.isAlive)
        assertEquals(0, bridge.localPort)
    }

    @Test
    fun testLoopbackProxyBridgeRestart() {
        val bridge = LoopbackProxyBridge.getInstance()
        val port1 = bridge.start("http://127.0.0.1:8888")
        assertTrue("Port 1 must be positive", port1 > 0)

        val port2 = bridge.restart("http://127.0.0.1:8888")
        assertTrue("Restarted port must be positive", port2 > 0)
        assertTrue("Bridge must be alive after restart", bridge.isAlive)

        bridge.stop()
        assertFalse("Bridge must be stopped", bridge.isAlive)
    }

    @Test
    fun testLoopbackProxyBridgeConnectsToLocalUpstream() {
        // Start a mock upstream HTTP server
        val mockUpstream = ServerSocket(0)
        val mockPort = mockUpstream.localPort

        val bridge = LoopbackProxyBridge.getInstance()
        val bridgePort = bridge.start("http://127.0.0.1:$mockPort")

        val clientThread = Thread {
            try {
                val clientSocket = Socket("127.0.0.1", bridgePort)
                val out = clientSocket.getOutputStream()
                out.write("CONNECT www.bing.com:443 HTTP/1.1\r\nHost: www.bing.com:443\r\n\r\n".toByteArray())
                out.flush()

                val line = clientSocket.getInputStream().bufferedReader().readLine()
                assertEquals("HTTP/1.1 200 Connection Established", line)
                clientSocket.close()
            } catch (e: Exception) {
                fail("Client connection failed: ${e.message}")
            }
        }

        val upstreamThread = Thread {
            try {
                val socket = mockUpstream.accept()
                val reader = socket.getInputStream().bufferedReader()
                val firstLine = reader.readLine()
                assertTrue(firstLine.startsWith("CONNECT www.bing.com:443"))

                // Reply 200 OK from upstream
                val out = socket.getOutputStream()
                out.write("HTTP/1.1 200 OK\r\n\r\n".toByteArray())
                out.flush()
                socket.close()
            } catch (e: Exception) {
                // ignore
            }
        }

        upstreamThread.start()
        clientThread.start()

        clientThread.join(5000)
        upstreamThread.join(5000)

        mockUpstream.close()
        bridge.stop()
    }
}
