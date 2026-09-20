@file:Suppress("ClassName")

package dev.botta.trantor.web.auth

import dev.botta.cqbus.ExecutionContext
import dev.botta.cqbus.requests.Request
import io.javalin.http.Context
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SessionTokenFromHeadersMiddlewareTest {
    @Nested
    inner class `a bearer token` {
        @Test
        fun `ends up on the execution context, where a use case can read it`() {
            val context = contextWith("Bearer abc123")

            execute(context)

            assertThat(context["session_token"]).isEqualTo("abc123")
        }

        @Test
        fun `keeps whatever the token itself contains`() {
            val context = contextWith("Bearer eyJhbGci.OiJIUzI1.NiIsInR5")

            execute(context)

            assertThat(context["session_token"]).isEqualTo("eyJhbGci.OiJIUzI1.NiIsInR5")
        }
    }

    @Nested
    inner class `anything else leaves the context alone` {
        @Test
        fun `no authorization header at all`() {
            val context = contextWith(null)

            execute(context)

            assertThat(context.has("session_token")).isFalse()
        }

        @Test
        fun `another scheme`() {
            val context = contextWith("Basic bmljbzpzZWNyZXQ=")

            execute(context)

            assertThat(context.has("session_token")).isFalse()
        }

        @Test
        fun `a bearer with nothing after it`() {
            val context = contextWith("Bearer ")

            execute(context)

            assertThat(context.has("session_token")).isFalse()
        }

        @Test
        fun `a bearer with only whitespace`() {
            val context = contextWith("Bearer    ")

            execute(context)

            assertThat(context.has("session_token")).isFalse()
        }

        @Test
        fun `the scheme is case sensitive, as the standard writes it`() {
            val context = contextWith("bearer abc123")

            execute(context)

            assertThat(context.has("session_token")).isFalse()
        }

        @Test
        fun `a call that did not come through http`() {
            val context = ExecutionContext()

            execute(context)

            assertThat(context.has("session_token")).isFalse()
        }
    }

    @Nested
    inner class `the request` {
        @Test
        fun `goes through either way, because this middleware only reads`() {
            val request = GetOrder()

            val seen = middleware.execute(request, { it }, contextWith(null))

            assertThat(seen).isSameAs(request)
        }
    }

    private fun execute(context: ExecutionContext) {
        middleware.execute(GetOrder(), { it }, context)
    }

    private fun contextWith(authorization: String?) = ExecutionContext().apply {
        this["javalin_context"] = mockk<Context>(relaxed = true).also {
            every { it.header("Authorization") } returns authorization
        }
    }

    private class GetOrder: Request<GetOrder>

    private val middleware = SessionTokenFromHeadersMiddleware()
}
