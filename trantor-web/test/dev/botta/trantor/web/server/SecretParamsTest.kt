@file:Suppress("ClassName")

package dev.botta.trantor.web.server

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SecretParamsTest {
    @Nested
    inner class `a path` {
        @Test
        fun `of a route with a secret param has its value redacted`() {
            val secrets = SecretParams(setOf("token")).apply { route("/mcp/{token}") }

            assertThat(secrets.path("/mcp/abc123")).isEqualTo("/mcp/REDACTED")
        }

        @Test
        fun `keeps the params that are not secret`() {
            val secrets = SecretParams(setOf("token")).apply { route("/users/{id}/keys/{token}") }

            assertThat(secrets.path("/users/7/keys/abc123")).isEqualTo("/users/7/keys/REDACTED")
        }

        @Test
        fun `of a route with no secret param stays as it is`() {
            val secrets = SecretParams(setOf("token")).apply { route("/orders/{id}") }

            assertThat(secrets.path("/orders/7")).isEqualTo("/orders/7")
        }

        @Test
        fun `that differs from the route in a fixed part is not of that route`() {
            val secrets = SecretParams(setOf("token")).apply { route("/mcp/{token}") }

            assertThat(secrets.path("/api/abc123")).isEqualTo("/api/abc123")
            assertThat(secrets.path("/mcp/abc123/more")).isEqualTo("/mcp/abc123/more")
        }

        @Test
        fun `with a secret param that takes slashes has everything from it on redacted`() {
            val secrets = SecretParams(setOf("token")).apply { route("/files/<token>") }

            assertThat(secrets.path("/files/abc/123")).isEqualTo("/files/REDACTED")
        }

        @Test
        fun `with a secret param after a wildcard has everything from the wildcard on redacted`() {
            val secrets = SecretParams(setOf("token")).apply { route("/hooks/*/{token}") }

            assertThat(secrets.path("/hooks/github/abc123")).isEqualTo("/hooks/REDACTED")
        }

        @Test
        fun `is left alone when no param is secret`() {
            val secrets = SecretParams(emptySet()).apply { route("/mcp/{token}") }

            assertThat(secrets.path("/mcp/abc123")).isEqualTo("/mcp/abc123")
        }
    }

    @Nested
    inner class `a query` {
        @Test
        fun `has the values of its secret keys redacted, besides the signatures the conventions name`() {
            val secrets = SecretParams(setOf("token"))

            assertThat(secrets.query("q=libros&token=abc123&sig=s1")).isEqualTo("q=libros&token=REDACTED&sig=REDACTED")
        }
    }

    @Test
    fun `a url has both redacted`() {
        val secrets = SecretParams(setOf("token")).apply { route("/mcp/{token}") }

        assertThat(secrets.url("https://api.example.com", "/mcp/abc123", "token=abc123&page=2"))
            .isEqualTo("https://api.example.com/mcp/REDACTED?token=REDACTED&page=2")
    }
}
