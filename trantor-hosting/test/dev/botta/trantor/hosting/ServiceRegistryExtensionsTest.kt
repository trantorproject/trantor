@file:Suppress("ClassName")

package dev.botta.trantor.hosting

import dev.botta.trantor.config.Config
import dev.botta.trantor.config.ConfigManager
import dev.botta.trantor.di.DefaultServiceProvider
import dev.botta.trantor.di.ServiceProvider
import dev.botta.trantor.di.ServiceRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ServiceRegistryExtensionsTest {
    @Nested
    inner class `addHostedService` {
        @Test
        fun `takes an instance`() {
            registry.addHostedService(Sweeper())

            assertThat(hostedServices()).hasSize(1)
        }

        @Test
        fun `takes a type, and builds it out of the container`() {
            registry.addSingleton(Greeting("hola"))
            registry.addHostedService<Greeter>()

            assertThat(hostedServices().filterIsInstance<Greeter>().single().greeting.text).isEqualTo("hola")
        }

        @Test
        fun `takes a factory, for what the container cannot build on its own`() {
            registry.addHostedService { Sweeper() }

            assertThat(hostedServices()).hasSize(1)
        }

        @Test
        fun `several of them all come back, in the order they were added`() {
            registry.addHostedService(Sweeper("first"))
            registry.addHostedService(Sweeper("second"))

            assertThat(hostedServices().map { it.name }).containsExactly("first", "second")
        }

        @Test
        fun `a keyed one can be asked for on its own`() {
            registry.addHostedService(Sweeper("sessions"), "sessions")

            assertThat(provider().get<HostedService>("sessions").name).isEqualTo("sessions")
        }

        @Test
        fun `a service is named after its class unless it says otherwise`() {
            registry.addHostedService(Greeter(Greeting("hola")))

            assertThat(hostedServices().single().name).isEqualTo("Greeter")
        }
    }

    @Nested
    inner class `addModule` {
        @Test
        fun `composes it right away, so what it registers is available`() {
            registry.addModule(GreetingModule())

            assertThat(provider().get<Greeting>().text).isEqualTo("hola")
        }

        @Test
        fun `registers the module itself, so the host can initialize it later`() {
            registry.addModule(GreetingModule())

            assertThat(provider().getAll<Module>()).hasSize(1)
        }

        @Test
        fun `adding it twice composes it once`() {
            registry.addModule(GreetingModule())
            registry.addModule(GreetingModule())

            assertThat(provider().getAll<Module>()).hasSize(1)
        }

        @Test
        fun `it can be given as a type instead of an instance`() {
            registry.addModule<GreetingModule>()

            assertThat(provider().get<Greeting>().text).isEqualTo("hola")
        }

        @Test
        fun `two different modules both go in`() {
            registry.addModule(GreetingModule())
            registry.addModule(SweeperModule())

            assertThat(provider().getAll<Module>()).hasSize(2)
        }
    }

    private fun provider() = DefaultServiceProvider(registry)

    private fun hostedServices() = provider().getAll<HostedService>()

    data class Greeting(val text: String)

    class Greeter(val greeting: Greeting): HostedService {
        override fun start() {}

        override fun stop(timeoutSeconds: Int) {}
    }

    private class Sweeper(override val name: String = "sweeper"): HostedService {
        override fun start() {}

        override fun stop(timeoutSeconds: Int) {}
    }

    class GreetingModule: Module {
        override fun compose(services: ServiceRegistry, config: ConfigManager) {
            services.addSingleton(Greeting("hola"))
        }

        override fun initialize(services: ServiceProvider, config: Config) {}
    }

    class SweeperModule: Module {
        override fun compose(services: ServiceRegistry, config: ConfigManager) {}

        override fun initialize(services: ServiceProvider, config: Config) {}
    }

    private val config = ConfigManager()
    private val registry = ServiceRegistry(config)
}
