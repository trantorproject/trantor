@file:Suppress("ClassName")

package dev.botta.trantor.web.client.okhttp

import dev.botta.trantor.web.client.HttpClientError
import dev.botta.trantor.web.client.HttpMethods
import dev.botta.trantor.web.client.HttpRequest
import dev.botta.trantor.web.client.MultipartBody
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import java.util.concurrent.TimeUnit

class OkHttpHttpClientTest {
    @Test
    fun `get returns status and body`() {
        server.enqueue(MockResponse(code = 200, body = "hello"))

        val response = client.get(url("/greeting"))

        assertThat(response.status).isEqualTo(200)
        assertThat(response.body).isEqualTo("hello")
    }

    @Test
    fun `get sends method and path`() {
        server.enqueue(MockResponse(code = 200, body = ""))

        client.get(url("/greeting"))

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("GET")
        assertThat(request.target).isEqualTo("/greeting")
    }

    @Test
    fun `get sends request headers`() {
        server.enqueue(MockResponse(code = 200, body = ""))

        client.get(url("/greeting"), headers = mapOf("Authorization" to "Bearer token"))

        val request = server.takeRequest()
        assertThat(request.headers["Authorization"]).isEqualTo("Bearer token")
    }

    @Test
    fun `get returns response headers`() {
        server.enqueue(MockResponse.Builder().code(200).setHeader("X-Request-Id", "123").build())

        val response = client.get(url("/greeting"))

        assertThat(response.headers["X-Request-Id"]).isEqualTo("123")
    }

    @Test
    fun `get returns content type and encoding`() {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .setHeader("Content-Type", "application/json; charset=utf-8")
                .body("{}")
                .build()
        )

        val response = client.get(url("/greeting"))

        assertThat(response.contentType).isEqualTo("application/json; charset=utf-8")
        assertThat(response.encoding).isEqualTo("UTF-8")
    }

    @Test
    fun `post sends body as json by default`() {
        server.enqueue(MockResponse(code = 200, body = ""))

        client.post(url("/orders"), body = """{"id":1}""")

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.bodyText).isEqualTo("""{"id":1}""")
        assertThat(request.headers["Content-Type"]).isEqualTo("application/json; charset=utf-8")
    }

    @Test
    fun `post sends body with the given content type`() {
        server.enqueue(MockResponse(code = 200, body = ""))

        client.post(url("/orders"), body = "name=John", headers = mapOf("Content-Type" to "application/x-www-form-urlencoded"))

        val request = server.takeRequest()
        assertThat(request.bodyText).isEqualTo("name=John")
        assertThat(request.headers["Content-Type"]).isEqualTo("application/x-www-form-urlencoded; charset=utf-8")
    }

    @Test
    fun `post sends multipart body`() {
        server.enqueue(MockResponse(code = 200, body = ""))

        val body = MultipartBody()
            .addFieldPart("name", "John")
            .addFilePart("file", "notes.txt", "text/plain", "file content".byteInputStream())
        client.post(HttpRequest(url("/uploads"), body))

        val request = server.takeRequest()
        assertThat(request.headers["Content-Type"]).startsWith("multipart/form-data")
        assertThat(request.bodyText).contains("""name="name"""", "John")
        assertThat(request.bodyText).contains("""filename="notes.txt"""", "file content")
    }

    @Test
    fun `put sends body`() {
        server.enqueue(MockResponse(code = 200, body = ""))

        client.put(url("/orders/1"), body = """{"id":1}""")

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("PUT")
        assertThat(request.bodyText).isEqualTo("""{"id":1}""")
    }

    @Test
    fun `patch sends body`() {
        server.enqueue(MockResponse(code = 200, body = ""))

        client.patch(url("/orders/1"), body = """{"id":1}""")

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("PATCH")
        assertThat(request.bodyText).isEqualTo("""{"id":1}""")
    }

    @Test
    fun `delete sends request`() {
        server.enqueue(MockResponse(code = 204, body = ""))

        val response = client.delete(url("/orders/1"))

        assertThat(response.status).isEqualTo(204)
        assertThat(server.takeRequest().method).isEqualTo("DELETE")
    }

    @Test
    fun `error status returns response without throwing`() {
        server.enqueue(MockResponse(code = 500, body = "boom"))

        val response = client.get(url("/greeting"))

        assertThat(response.status).isEqualTo(500)
        assertThat(response.body).isEqualTo("boom")
    }

    @Test
    fun `connection failure throws http client error`() {
        val unusedPort = server.port
        server.close()

        assertThatThrownBy { client.get("http://localhost:$unusedPort/greeting") }
            .isInstanceOf(HttpClientError::class.java)
    }

    @Test
    fun `silent server for longer than the idle timeout throws http client error`() {
        server.enqueue(MockResponse.Builder().code(200).body("late").headersDelay(2, TimeUnit.SECONDS).build())
        val client = OkHttpHttpClient(OkHttpHttpClientConfig(idleTimeout = 300, requestTimeout = 10_000))

        assertThatThrownBy { client.get(url("/slow")) }
            .isInstanceOf(HttpClientError::class.java)
    }

    @Test
    fun `request that exceeds the request timeout throws http client error even if the server keeps sending`() {
        server.enqueue(slowButAliveResponse())
        val client = OkHttpHttpClient(OkHttpHttpClientConfig(idleTimeout = 1_000, requestTimeout = 500))

        assertThatThrownBy { client.get(url("/slow")) }
            .isInstanceOf(HttpClientError::class.java)
    }

    @Test
    fun `request timeout of zero disables the total limit`() {
        server.enqueue(slowButAliveResponse())
        val client = OkHttpHttpClient(OkHttpHttpClientConfig(idleTimeout = 1_000, requestTimeout = 0))

        val response = client.get(url("/slow"))

        assertThat(response.body).isEqualTo(SLOW_BODY)
    }

    @Test
    fun `unsupported body type throws unsupported operation`() {
        assertThatThrownBy { client.post(HttpRequest(url("/orders"), body = 42)) }
            .isInstanceOf(UnsupportedOperationException::class.java)
    }

    @Test
    fun `redirects are not followed by default`() {
        server.enqueue(MockResponse.Builder().code(302).setHeader("Location", "/moved").build())

        val response = client.get(url("/greeting"))

        assertThat(response.status).isEqualTo(302)
    }

    @Test
    fun `redirects are followed when configured`() {
        server.enqueue(MockResponse.Builder().code(302).setHeader("Location", "/moved").build())
        server.enqueue(MockResponse(code = 200, body = "moved body"))
        val client = OkHttpHttpClient(OkHttpHttpClientConfig(followRedirects = true))

        val response = client.get(url("/greeting"))

        assertThat(response.status).isEqualTo(200)
        assertThat(response.body).isEqualTo("moved body")
    }

    @Test
    fun `the correlation id of the logs goes as X-Request-Id, so the next service logs under the same one`() {
        server.enqueue(MockResponse(code = 200, body = ""))
        MDC.put("cid", "abc123")

        client.get(url("/greeting"))

        assertThat(server.takeRequest().headers["X-Request-Id"]).isEqualTo("abc123")
    }

    @Test
    fun `an X-Request-Id the caller set is the one that goes`() {
        server.enqueue(MockResponse(code = 200, body = ""))
        MDC.put("cid", "abc123")

        client.get(url("/greeting"), mapOf("X-Request-Id" to "from-the-caller"))

        assertThat(server.takeRequest().headers.values("X-Request-Id")).containsExactly("from-the-caller")
    }

    @Test
    fun `without a correlation id no X-Request-Id goes`() {
        server.enqueue(MockResponse(code = 200, body = ""))

        client.get(url("/greeting"))

        assertThat(server.takeRequest().headers["X-Request-Id"]).isNull()
    }

    @Test
    fun `a stream sends the correlation id too`() {
        server.enqueue(MockResponse(code = 200, body = "data: hola\n\n"))
        MDC.put("cid", "abc123")

        client.stream(HttpMethods.Get, HttpRequest(url("/events"))).use { }

        assertThat(server.takeRequest().headers["X-Request-Id"]).isEqualTo("abc123")
    }

    @AfterEach
    fun tearDown() {
        server.close()
        MDC.clear()
    }

    private fun url(path: String) = server.url(path).toString()

    // Takes 1.5s to send, but never stays silent for more than 150ms
    private fun slowButAliveResponse() = MockResponse.Builder()
        .code(200)
        .body(SLOW_BODY)
        .throttleBody(1, 150, TimeUnit.MILLISECONDS)
        .build()

    private val RecordedRequest.bodyText get() = body?.utf8()

    private val server = MockWebServer().apply { start() }
    private val client = OkHttpHttpClient()

    companion object {
        private const val SLOW_BODY = "0123456789"
    }
}
