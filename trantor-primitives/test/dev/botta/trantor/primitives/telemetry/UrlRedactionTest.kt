@file:Suppress("ClassName")

package dev.botta.trantor.primitives.telemetry

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class UrlRedactionTest {
    @Nested
    inner class `a query` {
        @Test
        fun `loses the values of its signatures and keeps the rest`() {
            assertThat(UrlRedaction.query("q=libros&sig=secret&page=2")).isEqualTo("q=libros&sig=REDACTED&page=2")
        }

        @Test
        fun `is matched by the exact name of the key`() {
            assertThat(UrlRedaction.query("X-Amz-Signature=abc&x-amz-signature=def"))
                .isEqualTo("X-Amz-Signature=REDACTED&x-amz-signature=def")
        }

        @Test
        fun `keeps a key without a value as it is`() {
            assertThat(UrlRedaction.query("sig&debug")).isEqualTo("sig&debug")
        }
    }

    @Nested
    inner class `a url` {
        @Test
        fun `loses the user and the password in it`() {
            assertThat(UrlRedaction.url("https://nico:s3cret@api.example.com/v1/orders"))
                .isEqualTo("https://REDACTED:REDACTED@api.example.com/v1/orders")
        }

        @Test
        fun `loses a user alone too`() {
            assertThat(UrlRedaction.url("https://token@api.example.com/"))
                .isEqualTo("https://REDACTED@api.example.com/")
        }

        @Test
        fun `loses the signatures of its query and keeps its fragment`() {
            assertThat(UrlRedaction.url("https://files.example.com/a.pdf?Signature=abc&v=1#page=2"))
                .isEqualTo("https://files.example.com/a.pdf?Signature=REDACTED&v=1#page=2")
        }

        @Test
        fun `without anything to hide is the same`() {
            assertThat(UrlRedaction.url("http://localhost:8080/orders/7?expand=items"))
                .isEqualTo("http://localhost:8080/orders/7?expand=items")
        }

        @Test
        fun `keeps an at sign that is in the path, not in the authority`() {
            assertThat(UrlRedaction.url("https://example.com/users/nico@example.com"))
                .isEqualTo("https://example.com/users/nico@example.com")
        }
    }
}
