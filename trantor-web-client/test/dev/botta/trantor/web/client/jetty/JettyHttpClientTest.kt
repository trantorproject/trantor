@file:Suppress("ClassName")

package dev.botta.trantor.web.client.jetty

import dev.botta.trantor.web.client.HttpMethods
import dev.botta.trantor.web.client.HttpRequest
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class JettyHttpClientTest {
    @Test
    fun `stream is not supported`() {
        JettyHttpClient().use { client ->
            assertThatThrownBy { client.stream(HttpMethods.Get, HttpRequest("http://localhost/events")) }
                .isInstanceOf(UnsupportedOperationException::class.java)
        }
    }
}
