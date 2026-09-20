@file:Suppress("ClassName")

package dev.botta.trantor.web.server

import dev.botta.json.Json
import io.javalin.http.BadRequestResponse
import io.javalin.http.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ContextExtensionsTest {
    @Nested
    inner class `writing json` {
        @Test
        fun `sets the content type, so a client does not have to guess`() {
            val context = contextOf()

            context.jsonObj("id" to "order-7")

            verify { context.contentType("application/json") }
        }

        @Test
        fun `writes the pairs it was given`() {
            val context = contextOf()

            context.jsonObj("id" to "order-7", "total" to 150)

            val body = Json.parse(writtenTo(context)).asObject()!!
            assertThat(body["id"]?.asString()).isEqualTo("order-7")
            assertThat(body["total"]?.asInt()).isEqualTo(150)
        }

        @Test
        fun `a null value is written as null, not left out`() {
            val context = contextOf()

            context.jsonObj("note" to null)

            assertThat(Json.parse(writtenTo(context)).asObject()!!["note"]?.isNull).isTrue()
        }

        @Test
        fun `with nothing to say it writes an empty object`() {
            val context = contextOf()

            context.jsonValue()

            assertThat(writtenTo(context)).isEqualTo("{}")
        }
    }

    @Nested
    inner class `reading the body as json` {
        @Test
        fun `gives back the object that was sent`() {
            val context = contextOf(body = """{"id":"order-7"}""")

            assertThat(context.jsonBody()["id"]?.asString()).isEqualTo("order-7")
        }

        @Test
        fun `an empty body is an empty object, not a failure`() {
            assertThat(contextOf(body = "").jsonBody().isEmpty()).isTrue()
        }

        @Test
        fun `a body that is not an object is a bad request`() {
            assertThatThrownBy { contextOf(body = "[1, 2]").jsonBody() }
                .isInstanceOf(BadRequestResponse::class.java)
        }
    }

    @Nested
    inner class `writing an error` {
        @Test
        fun `names the exception and repeats its message`() {
            val context = contextOf()

            context.jsonError(OrderRejected("No hay stock"))

            val body = Json.parse(writtenTo(context)).asObject()!!
            assertThat(body["type"]?.asString()).isEqualTo("OrderRejected")
            assertThat(body["message"]?.asString()).isEqualTo("No hay stock")
        }

        @Test
        fun `an exception with no message still has the field`() {
            val context = contextOf()

            context.jsonError(OrderRejected(null))

            assertThat(Json.parse(writtenTo(context)).asObject()!!["message"]?.asString()).isEmpty()
        }

        @Test
        fun `the message can be replaced, for what a client should not see`() {
            val context = contextOf()

            context.jsonError(OrderRejected("Table 'orders' doesn't exist"), "Internal error")

            assertThat(Json.parse(writtenTo(context)).asObject()!!["message"]?.asString()).isEqualTo("Internal error")
        }

        @Test
        fun `the type can be given on its own, without an exception`() {
            val context = contextOf()

            context.jsonError("Exception", "Internal error")

            val body = Json.parse(writtenTo(context)).asObject()!!
            assertThat(body["type"]?.asString()).isEqualTo("Exception")
            assertThat(body["message"]?.asString()).isEqualTo("Internal error")
        }
    }

    private fun contextOf(body: String = "") = mockk<Context>(relaxed = true).also {
        every { it.body() } returns body
    }

    private fun writtenTo(context: Context): String {
        val written = slot<String>()
        verify { context.result(capture(written)) }

        return written.captured
    }

    private class OrderRejected(message: String?): Exception(message)
}
