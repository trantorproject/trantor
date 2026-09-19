@file:Suppress("ClassName")

package dev.botta.trantor.web.client.okhttp

import dev.botta.trantor.web.client.HttpClientError
import dev.botta.trantor.web.client.HttpMethods
import dev.botta.trantor.web.client.HttpRequest
import dev.botta.trantor.web.client.StreamOptions
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.system.measureTimeMillis

class OkHttpHttpClientStreamTest {
    @Test
    fun `returns status, content type and headers before reading the body`() {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .setHeader("Content-Type", "text/event-stream")
                .setHeader("X-Request-Id", "123")
                .body(EVENTS)
                .build()
        )

        client.stream(HttpMethods.Get, HttpRequest(url("/events"))).use { response ->
            assertThat(response.status).isEqualTo(200)
            assertThat(response.contentType).isEqualTo("text/event-stream")
            assertThat(response.headers["X-Request-Id"]).isEqualTo("123")
        }
    }

    @Test
    fun `lines are returned as the server sends them`() {
        server.enqueue(MockResponse.Builder().code(200).body(EVENTS).throttleBody(10, 200, TimeUnit.MILLISECONDS).build())

        client.stream(HttpMethods.Get, HttpRequest(url("/events"))).use { response ->
            val lines = response.lines().iterator()

            val firstLineMs = measureTimeMillis { lines.next() }
            val restMs = measureTimeMillis { lines.forEachRemaining { } }

            assertThat(firstLineMs).isLessThan(restMs)
        }
    }

    @Test
    fun `lines returns every line of the body`() {
        server.enqueue(MockResponse(code = 200, body = EVENTS))

        val lines = client.stream(HttpMethods.Get, HttpRequest(url("/events"))).use { it.lines().toList() }

        assertThat(lines).containsExactly("data: one", "", "data: two")
    }

    @Test
    fun `body returns the whole content at once`() {
        server.enqueue(MockResponse(code = 400, body = """{"error":"bad request"}"""))

        val response = client.stream(HttpMethods.Get, HttpRequest(url("/events")))

        response.use {
            assertThat(it.status).isEqualTo(400)
            assertThat(it.body()).isEqualTo("""{"error":"bad request"}""")
        }
    }

    @Test
    fun `sends method, body and headers`() {
        server.enqueue(MockResponse(code = 200, body = EVENTS))

        val request = HttpRequest(url("/events"), """{"stream":true}""", mapOf("Authorization" to "Bearer token"))
        client.stream(HttpMethods.Post, request).use { it.lines().toList() }

        val recorded = server.takeRequest()
        assertThat(recorded.method).isEqualTo("POST")
        assertThat(recorded.body?.utf8()).isEqualTo("""{"stream":true}""")
        assertThat(recorded.headers["Authorization"]).isEqualTo("Bearer token")
    }

    @Test
    fun `cancel from another thread stops the stream`() {
        server.enqueue(MockResponse.Builder().code(200).body(EVENTS).throttleBody(1, 300, TimeUnit.MILLISECONDS).build())

        val response = client.stream(HttpMethods.Get, HttpRequest(url("/events")))

        val lines = mutableListOf<String>()
        val elapsedMs = measureTimeMillis {
            response.use {
                thread { it.cancel() }
                it.lines().forEach { line -> lines.add(line) }
            }
        }

        assertThat(lines).hasSizeLessThan(3)
        assertThat(elapsedMs).isLessThan(2_000)
    }

    @Test
    fun `silent server for longer than the read timeout throws http client error`() {
        server.enqueue(MockResponse.Builder().code(200).body(EVENTS).bodyDelay(2, TimeUnit.SECONDS).build())

        val response = client.stream(HttpMethods.Get, HttpRequest(url("/events")), StreamOptions(readTimeout = 300))

        assertThatThrownBy { response.use { it.lines().toList() } }
            .isInstanceOf(HttpClientError::class.java)
    }

    @Test
    fun `stream longer than the request timeout keeps going`() {
        server.enqueue(MockResponse.Builder().code(200).body(EVENTS).throttleBody(2, 100, TimeUnit.MILLISECONDS).build())
        val client = OkHttpHttpClient(OkHttpHttpClientConfig(idleTimeout = 1_000, requestTimeout = 300))

        val lines = client.stream(HttpMethods.Get, HttpRequest(url("/events"))).use { it.lines().toList() }

        assertThat(lines).containsExactly("data: one", "", "data: two")
    }

    @Test
    fun `stream is bounded when a total timeout is given`() {
        server.enqueue(MockResponse.Builder().code(200).body(EVENTS).throttleBody(2, 100, TimeUnit.MILLISECONDS).build())

        val response = client.stream(HttpMethods.Get, HttpRequest(url("/events")), StreamOptions(totalTimeout = 300))

        assertThatThrownBy { response.use { it.lines().toList() } }
            .isInstanceOf(HttpClientError::class.java)
    }

    @Test
    fun `connection failure throws http client error`() {
        val unusedPort = server.port
        server.close()

        assertThatThrownBy { client.stream(HttpMethods.Get, HttpRequest("http://localhost:$unusedPort/events")) }
            .isInstanceOf(HttpClientError::class.java)
    }

    @AfterEach
    fun tearDown() {
        server.close()
    }

    private fun url(path: String) = server.url(path).toString()

    private val server = MockWebServer().apply { start() }
    private val client = OkHttpHttpClient()

    companion object {
        private val EVENTS = """
            data: one

            data: two

        """.trimIndent()
    }
}
