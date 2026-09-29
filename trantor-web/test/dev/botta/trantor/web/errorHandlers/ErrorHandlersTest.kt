@file:Suppress("ClassName")

package dev.botta.trantor.web.errorHandlers

import dev.botta.json.Json
import dev.botta.trantor.web.server.HttpErrorHandler
import io.javalin.http.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.slf4j.Logger

class ErrorHandlersTest {
    @Nested
    inner class `the status each one answers with` {
        @Test
        fun `bad request is 400`() {
            assertThat(statusOf(BadRequestErrorHandler(OrderRejected::class))).isEqualTo(400)
        }

        @Test
        fun `not authenticated is 401`() {
            assertThat(statusOf(NotAuthenticatedErrorHandler(OrderRejected::class))).isEqualTo(401)
        }

        @Test
        fun `forbidden is 403`() {
            assertThat(statusOf(ForbiddenErrorHandler(OrderRejected::class))).isEqualTo(403)
        }

        @Test
        fun `not found is 404`() {
            assertThat(statusOf(NotFoundErrorHandler(OrderRejected::class))).isEqualTo(404)
        }

        @Test
        fun `conflict is 409`() {
            assertThat(statusOf(ConflictErrorHandler(OrderRejected::class))).isEqualTo(409)
        }

        @Test
        fun `internal is 500`() {
            assertThat(statusOf(InternalErrorHandler(OrderRejected::class))).isEqualTo(500)
        }
    }

    @Nested
    inner class `the body` {
        @Test
        fun `names the error and repeats its message`() {
            val body = bodyOf(BadRequestErrorHandler(OrderRejected::class), OrderRejected("No hay stock"))

            assertThat(body["type"]?.asString()).isEqualTo("OrderRejected")
            assertThat(body["message"]?.asString()).isEqualTo("No hay stock")
        }

        @Test
        fun `is json, so a client can read it without guessing`() {
            val context = handle(BadRequestErrorHandler(OrderRejected::class), OrderRejected("No hay stock"))

            verify { context.contentType("application/json") }
        }

        @Test
        fun `an error with no message still has the field`() {
            val body = bodyOf(NotFoundErrorHandler(OrderRejected::class), OrderRejected(null))

            assertThat(body["type"]?.asString()).isEqualTo("OrderRejected")
            assertThat(body["message"]?.asString()).isEmpty()
        }
    }

    @Nested
    inner class `the internal handler` {
        @Test
        fun `does not leak the message, because nobody meant it for a client`() {
            val error = OrderRejected("Table 'orders' doesn't exist in schema 'prod'")

            val body = bodyOf(InternalErrorHandler(OrderRejected::class), error)

            assertThat(body["type"]?.asString()).isEqualTo("Exception")
            assertThat(body["message"]?.asString()).isEqualTo("Internal error")
        }

        @Test
        fun `logs at error level, with the exception, because this one is a bug`() {
            val logger = mockk<Logger>(relaxed = true)
            val error = OrderRejected("boom")

            InternalErrorHandler(OrderRejected::class).handle(error, contextOf(), logger)

            verify { logger.error(match<String> { it.contains("OrderRejected") && it.contains("boom") }, error) }
        }
    }

    @Nested
    inner class `the expected ones` {
        @Test
        fun `log at info level, because they are not a failure of the application`() {
            val logger = mockk<Logger>(relaxed = true)
            val error = OrderRejected("No hay stock")

            BadRequestErrorHandler(OrderRejected::class).handle(error, contextOf(), logger)

            verify { logger.info("No hay stock", error) }
        }
    }

    @Nested
    inner class `the type it is registered for` {
        @Test
        fun `is the one it was built with`() {
            assertThat(BadRequestErrorHandler(OrderRejected::class).errorType).isEqualTo(OrderRejected::class.java)
        }

        @Test
        fun `can be given as a java class too, for a handler declared from java`() {
            assertThat(BadRequestErrorHandler(OrderRejected::class.java).errorType)
                .isEqualTo(OrderRejected::class.java)
        }
    }

    private fun statusOf(handler: HttpErrorHandler<OrderRejected>): Int {
        val context = handle(handler, OrderRejected("whatever"))
        val status = slot<Int>()
        verify { context.status(capture(status)) }

        return status.captured
    }

    private fun bodyOf(handler: HttpErrorHandler<OrderRejected>, error: OrderRejected) =
        Json.parse(writtenTo(handle(handler, error))).asObject()!!

    private fun handle(handler: HttpErrorHandler<OrderRejected>, error: OrderRejected) =
        contextOf().also { handler.handle(error, it, mockk(relaxed = true)) }

    private fun contextOf() = mockk<Context>(relaxed = true).also {
        every { it.body() } returns ""
    }

    private fun writtenTo(context: Context): String {
        val written = slot<String>()
        verify { context.result(capture(written)) }

        return written.captured
    }

    class OrderRejected(message: String?): Exception(message)
}
