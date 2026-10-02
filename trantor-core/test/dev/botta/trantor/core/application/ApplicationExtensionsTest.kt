@file:Suppress("ClassName")

package dev.botta.trantor.core.application

import dev.botta.cqbus.ExecutionContext
import dev.botta.cqbus.identity.Identity
import dev.botta.cqbus.requests.Command
import dev.botta.cqbus.requests.Query
import dev.botta.cqbus.requests.Request
import dev.botta.cqbus.requests.handlers.ContextAwareRequestHandler
import dev.botta.cqbus.requests.handlers.RequestHandler
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ApplicationExtensionsTest {
    @Nested
    inner class `a handler named by its class` {
        @Test
        fun `handles the request its declaration says`() {
            val app = appWith(Greeter("hello"))

            app.addHandler<GreetHandler>()

            assertThat(app.execute(Greet("nico"))).isEqualTo("hello nico")
        }

        @Test
        fun `is built by the container for every request, so it gets the scoped services of each`() {
            val app = appWith(Greeter("hello"))
            app.addHandler<CountingHandler>()
            val before = CountingHandler.built

            app.execute(Count)
            app.execute(Count)

            assertThat(CountingHandler.built).isEqualTo(before + 2)
        }

        @Test
        fun `can inherit the interface from a base class`() {
            val app = appWith(Greeter("hello"))

            app.addHandler<LoudGreetHandler>()

            assertThat(app.execute(Greet("nico"))).isEqualTo("HELLO NICO")
        }

        @Test
        fun `can be context aware, and then it gets the whole context`() {
            val app = appWith(Greeter("hello"))

            app.addHandler<WhereAmIHandler>()

            assertThat(app.execute(WhereAmI, ExecutionContext().with("place", "the office"))).isEqualTo("the office")
        }

        @Test
        fun `is refused when it handles nothing, saying which class it was`() {
            val app = appWith(Greeter("hello"))

            assertThatThrownBy { app.addHandler<Greeter>() }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("Greeter")
        }

        @Test
        fun `is refused when its request is a type parameter, since nothing says which request it is`() {
            val app = appWith(Greeter("hello"))

            assertThatThrownBy { app.addHandler<AnyGreetHandler<Greet>>() }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("AnyGreetHandler")
        }
    }

    @Nested
    inner class `a handler given as it is` {
        @Test
        fun `handles every request of its type`() {
            val app = appWith(Greeter("hello"))

            app.addHandler(RequestHandler<Greet, String> { request, _ -> "hi ${request.name}" })

            assertThat(app.execute(Greet("nico"))).isEqualTo("hi nico")
        }

        @Test
        fun `can be context aware`() {
            val app = appWith(Greeter("hello"))

            app.addHandler(ContextAwareRequestHandler<WhereAmI, String> { _, context -> "at ${context["place"]}" })

            assertThat(app.execute(WhereAmI, ExecutionContext().with("place", "home"))).isEqualTo("at home")
        }
    }

    private fun appWith(greeter: Greeter): Application {
        val builder = Application.builder { appName = "test" }
        builder.services.addSingleton(greeter)

        return builder.build()
    }

    class Greet(val name: String): Query<String>

    object Count: Command<Unit>

    object WhereAmI: Query<String>

    class Greeter(val greeting: String)

    class GreetHandler(private val greeter: Greeter): RequestHandler<Greet, String> {
        override fun execute(request: Greet, identity: Identity) = "${greeter.greeting} ${request.name}"
    }

    abstract class BaseGreetHandler<T: Request<String>>: RequestHandler<T, String>

    class LoudGreetHandler(private val greeter: Greeter): BaseGreetHandler<Greet>() {
        override fun execute(request: Greet, identity: Identity) = "${greeter.greeting} ${request.name}".uppercase()
    }

    class AnyGreetHandler<T: Request<String>>: RequestHandler<T, String> {
        override fun execute(request: T, identity: Identity) = "hello"
    }

    class CountingHandler: RequestHandler<Count, Unit> {
        init {
            built++
        }

        override fun execute(request: Count, identity: Identity) {}

        companion object {
            var built = 0
        }
    }

    class WhereAmIHandler: ContextAwareRequestHandler<WhereAmI, String> {
        override fun execute(request: WhereAmI, context: ExecutionContext) = context["place"] as String
    }
}
