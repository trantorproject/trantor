package dev.botta.trantor.core.application.middlewares

import dev.botta.cqbus.ExecutionContext
import dev.botta.cqbus.identity.Identity
import dev.botta.cqbus.requests.PureCommand
import dev.botta.trantor.core.auth.*
import dev.botta.trantor.core.auth.middlewares.GrantsAuthorizationMiddleware
import org.junit.jupiter.api.*

class AuthorizationMiddlewareTest {
    @Test
    fun `fail if a required grant is not present in authentication info`() {
        executionContext.identity = SomeIdentity(grants = listOf("not-admin"))

        assertThrows<UnauthorizedAccessError> {
            middleware.execute(SomeRestrictedToAdminCommand(), {}, executionContext)
        }
    }

    @Test
    fun `fail if requires grants and is not authenticated`() {
        assertThrows<UnauthorizedAccessError> {
            middleware.execute(SomeRestrictedToAdminCommand(), {}, executionContext)
        }
    }

    @Test
    fun `don't fail if not authentication is required`() {
        assertDoesNotThrow {
            middleware.execute(SomePublicCommand(), {}, executionContext)
        }
    }

    private val executionContext = ExecutionContext()
    private val middleware = GrantsAuthorizationMiddleware()

    private class SomeIdentity(override val grants: List<String>): Identity {
        override val authenticationType: String? = null
        override val isAuthenticated = true
        override val name = "Some Identity"
        override val properties = mapOf<String, Any>()
    }

    private class SomePublicCommand: PureCommand

    @GrantsAuthorization(grants = ["admin"])
    private class SomeRestrictedToAdminCommand: PureCommand
}
