@file:Suppress("ClassName")

package dev.botta.trantor.web.application

import dev.botta.cqbus.ExecutionContext
import dev.botta.cqbus.identity.Identity
import dev.botta.cqbus.requests.Query
import dev.botta.cqbus.requests.handlers.ContextAwareRequestHandler
import dev.botta.cqbus.requests.handlers.RequestHandler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class WebApplicationExtensionsTest {
    @Nested
    inner class `addHandler` {
        @Test
        fun `with a class, has the container build it with what it depends on`() {
            app.addHandler<GreetHandler>()

            assertThat(app.execute(Greet("nico"))).isEqualTo("hello nico")
        }

        @Test
        fun `with an instance, uses it as it is`() {
            app.addHandler(RequestHandler<Greet, String> { request, _ -> "hi ${request.name}" })

            assertThat(app.execute(Greet("nico"))).isEqualTo("hi nico")
        }

        @Test
        fun `with a context aware instance, gives it the context`() {
            app.addHandler(ContextAwareRequestHandler<WhereAmI, String> { _, context -> "at ${context["place"]}" })

            assertThat(app.execute(WhereAmI, ExecutionContext().with("place", "home"))).isEqualTo("at home")
        }
    }

    private val app = WebApplication.builder { appName = "test" }
        .apply { services.addSingleton(Greeter("hello")) }
        .build()

    class Greet(val name: String): Query<String>

    object WhereAmI: Query<String>

    class Greeter(val greeting: String)

    class GreetHandler(private val greeter: Greeter): RequestHandler<Greet, String> {
        override fun execute(request: Greet, identity: Identity) = "${greeter.greeting} ${request.name}"
    }
}
