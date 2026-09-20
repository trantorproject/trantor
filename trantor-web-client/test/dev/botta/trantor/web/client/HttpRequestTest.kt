@file:Suppress("ClassName")

package dev.botta.trantor.web.client

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class HttpRequestTest {
    @Nested
    inner class `headers` {
        @Test
        fun `start empty when nobody gave any`() {
            assertThat(HttpRequest("http://localhost").headers).isEmpty()
        }

        @Test
        fun `can be added one at a time`() {
            val request = HttpRequest("http://localhost")

            request.setHeader("Accept", "application/json")

            assertThat(request.headers).containsEntry("Accept", "application/json")
        }

        @Test
        fun `setting one twice keeps the last value`() {
            val request = HttpRequest("http://localhost")

            request.setHeader("Accept", "text/plain")
            request.setHeader("Accept", "application/json")

            assertThat(request.headers).containsExactly(java.util.Map.entry("Accept", "application/json"))
        }

        @Test
        fun `the map it was built with is copied, not held on to`() {
            val given = mutableMapOf("Accept" to "application/json")
            val request = HttpRequest("http://localhost", headers = given)

            given["Authorization"] = "Bearer abc"

            assertThat(request.headers).doesNotContainKey("Authorization")
        }
    }

    @Nested
    inner class `equality` {
        @Test
        fun `is the url, the body and the headers`() {
            val one = HttpRequest("http://localhost", "body", mapOf("Accept" to "application/json"))
            val other = HttpRequest("http://localhost", "body", mapOf("Accept" to "application/json"))

            assertThat(one).isEqualTo(other)
            assertThat(one.hashCode()).isEqualTo(other.hashCode())
        }

        @Test
        fun `a different url is a different request`() {
            assertThat(HttpRequest("http://a")).isNotEqualTo(HttpRequest("http://b"))
        }

        @Test
        fun `a different body is a different request`() {
            assertThat(HttpRequest("http://a", "one")).isNotEqualTo(HttpRequest("http://a", "other"))
        }

        @Test
        fun `a different header is a different request`() {
            val one = HttpRequest("http://a", headers = mapOf("Accept" to "application/json"))

            assertThat(one).isNotEqualTo(HttpRequest("http://a"))
        }

        @Test
        fun `no body is not the same as an empty one`() {
            assertThat(HttpRequest("http://a")).isNotEqualTo(HttpRequest("http://a", ""))
        }
    }

    @Test
    fun `reads as the call it is, for a log line`() {
        val request = HttpRequest("http://localhost/orders", "hola")

        assertThat(request.toString())
            .startsWith("HttpRequest(")
            .contains("url=http://localhost/orders")
            .contains("body=hola")
    }
}
