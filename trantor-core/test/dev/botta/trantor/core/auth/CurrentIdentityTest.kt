@file:Suppress("ClassName")

package dev.botta.trantor.core.auth

import dev.botta.cqbus.ExecutionContext
import dev.botta.cqbus.Middleware
import dev.botta.cqbus.identity.AnonymousIdentity
import dev.botta.cqbus.identity.Identity
import dev.botta.cqbus.requests.Request
import dev.botta.trantor.core.application.Application
import dev.botta.trantor.core.application.identityOf
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CurrentIdentityTest {
    @Test
    fun `is who the middlewares of the application say is asking`() {
        val context = ExecutionContext().with("token", "seller-token")

        assertThat(app.identityOf(context).name).isEqualTo("nico")
    }

    @Test
    fun `is anonymous when nobody says who asks`() {
        assertThat(app.identityOf()).isInstanceOf(AnonymousIdentity::class.java)
    }

    private val app = Application.builder { appName = "test" }.build().apply {
        registerMiddleware(TokenMiddleware())
    }

    /** Who asks by a token, as an application tells it. */
    class TokenMiddleware: Middleware {
        override fun <T: Request<R>, R> execute(request: T, next: (T) -> R, context: ExecutionContext): R {
            if (context["token"] == "seller-token") context.identity = Seller()
            return next(request)
        }
    }

    class Seller: Identity {
        override val name = "nico"
        override val isAuthenticated = true
        override val authenticationType = "token"
        override val roles = listOf("seller")
        override val properties = emptyMap<String, Any>()
    }
}
