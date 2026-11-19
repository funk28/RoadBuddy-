package com.roadbuddy.core.network

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DetectionApiFactoryTest {
    private lateinit var server: HttpServer
    private val authorizations = mutableListOf<String?>()

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            synchronized(authorizations) {
                authorizations += exchange.requestHeaders.getFirst("Authorization")
            }
            val bytes = """{"detection_id": "d_1", "undone": true}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @After
    fun stopServer() {
        server.stop(0)
    }

    private val baseUrl get() = "http://127.0.0.1:${server.address.port}/v1"

    @Test
    fun blankBaseUrlUsesTheOfflineStub() {
        assertTrue(createDetectionApi(baseUrl = "", devToken = "dev:demo") is StubDetectionApi)
        assertTrue(createDetectionApi(baseUrl = "  ", devToken = "dev:demo") is StubDetectionApi)
    }

    @Test
    fun baseUrlUsesHttpWithTheDevToken() = runBlocking {
        val api = createDetectionApi(baseUrl, devToken = "dev:demo")

        assertTrue(api is HttpDetectionApi)
        api.undoDetection("d_1")
        assertEquals("Bearer dev:demo", authorizations.single())
    }

    @Test
    fun blankDevTokenSendsNoAuthorizationHeader() = runBlocking {
        createDetectionApi(baseUrl, devToken = "").undoDetection("d_1")

        assertNull(authorizations.single())
    }
}
