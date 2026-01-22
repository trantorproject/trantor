package dev.botta.trantor.app.middlewares

import dev.botta.cqbus.ExecutionContext
import dev.botta.cqbus.identity.Identity
import dev.botta.cqbus.requests.PureCommand
import dev.botta.trantor.app.auth.*
import org.junit.jupiter.api.*

class AuthorizationMiddlewareTest {
    @Test
    fun `fail if required role is not present in authentication info`() {
        executionContext.identity = SomeIdentity(roles = listOf("not-admin"))

        assertThrows<UnauthorizedAccessError> {
            middleware.execute(SomeRestrictedToAdminCommand(), {}, executionContext)
        }
    }

    @Test
    fun `fail if requires roles and is not authenticated`() {
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
    private val middleware = RolesAuthorizationMiddleware()

    private class SomeIdentity(override val roles: List<String>): Identity {
        override val authenticationType: String? = null
        override val isAuthenticated = true
        override val name = "Some Identity"
        override val properties = mapOf<String, Any>()
    }

    private class SomePublicCommand: PureCommand

    @RolesAuthorization(roles = ["admin"])
    private class SomeRestrictedToAdminCommand: PureCommand
}
