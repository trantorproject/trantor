@file:Suppress("ClassName")

package dev.botta.trantor.web.client

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class HttpResponseTest {
    @Nested
    inner class `the body` {
        @Test
        fun `is read as utf8 when nothing says otherwise`() {
            val response = responseOf("¿Llueve en Bariloche?".toByteArray(Charsets.UTF_8))

            assertThat(response.body).isEqualTo("¿Llueve en Bariloche?")
        }

        @Test
        fun `is read with the encoding the server declared`() {
            val response = responseOf("año".toByteArray(Charsets.ISO_8859_1), encoding = "ISO-8859-1")

            assertThat(response.body).isEqualTo("año")
        }

        @Test
        fun `read with the wrong encoding is mojibake, which is why the header matters`() {
            val response = responseOf("año".toByteArray(Charsets.UTF_8), encoding = "ISO-8859-1")

            assertThat(response.body).isNotEqualTo("año")
        }

        @Test
        fun `an empty body is an empty string`() {
            assertThat(responseOf(ByteArray(0)).body).isEmpty()
        }

        @Test
        fun `the bytes are still there for whoever wants them raw`() {
            val bytes = byteArrayOf(1, 2, 3)

            assertThat(responseOf(bytes).bodyBytes).isEqualTo(bytes)
        }

        @Test
        fun `is only decoded once, however many times it is read`() {
            val response = responseOf("hola".toByteArray())

            assertThat(response.body).isSameAs(response.body)
        }
    }

    @Nested
    inner class `what came with it` {
        @Test
        fun `the status is whatever the server said`() {
            assertThat(responseOf(ByteArray(0), status = 404).status).isEqualTo(404)
        }

        @Test
        fun `headers default to none, not to null`() {
            assertThat(responseOf(ByteArray(0)).headers).isEmpty()
        }

        @Test
        fun `a content type is kept as it came`() {
            val response = HttpResponse(200, ByteArray(0), contentType = "application/json")

            assertThat(response.contentType).isEqualTo("application/json")
        }
    }

    private fun responseOf(bytes: ByteArray, status: Int = 200, encoding: String? = null) =
        HttpResponse(status, bytes, encoding = encoding)
}
