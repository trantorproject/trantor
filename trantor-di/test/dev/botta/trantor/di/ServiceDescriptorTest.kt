@file:Suppress("ClassName")

package dev.botta.trantor.di

import dev.botta.trantor.config.ConfigManager
import dev.botta.trantor.di.ServiceLifetimes.Singleton
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ServiceDescriptorTest {
    @Nested
    inner class `serviceId` {
        @Test
        fun `is the type when there is no key`() {
            assertThat(ServiceDescriptor.serviceId(Greeter::class.java)).isEqualTo(Greeter::class.java.name)
        }

        @Test
        fun `carries the key, so two registrations of one type are told apart`() {
            val one = ServiceDescriptor.serviceId(Greeter::class.java, "formal")
            val other = ServiceDescriptor.serviceId(Greeter::class.java, "casual")

            assertThat(one).isNotEqualTo(other)
            assertThat(one).isEqualTo("${Greeter::class.java.name}@formal")
        }
    }

    @Nested
    inner class `implementationId` {
        @Test
        fun `says what kind of implementation it is`() {
            assertThat(fromType().implementationId).startsWith("type:")
            assertThat(fromInstance().implementationId).startsWith("instance:")
            assertThat(fromFactory().implementationId).startsWith("factory:")
        }

        @Test
        fun `is the same for the same implementation type`() {
            assertThat(fromType().implementationId).isEqualTo(fromType().implementationId)
        }

        @Test
        fun `two factories are two implementations, even building the same class`() {
            val one = ServiceDescriptor.createWithImplementationFactory(Greeter::class.java, GreeterFactory())
            val other = ServiceDescriptor.createWithImplementationFactory(Greeter::class.java, GreeterFactory())

            assertThat(one.implementationId).isNotEqualTo(other.implementationId)
        }

        @Test
        fun `two instances are two implementations, even when they are equal`() {
            val one = ServiceDescriptor.createWithInstance(Greeter::class.java, SpanishGreeter())
            val other = ServiceDescriptor.createWithInstance(Greeter::class.java, SpanishGreeter())

            assertThat(one.implementationId).isNotEqualTo(other.implementationId)
        }

        @Test
        fun `carries the key, so a keyed singleton is not shared with an unkeyed one`() {
            val unkeyed = fromType()
            val keyed = fromType(key = "formal")

            assertThat(keyed.implementationId).isEqualTo("${unkeyed.implementationId}@formal")
        }

        @Test
        fun `a descriptor with nothing to build says so where it is declared`() {
            assertThatThrownBy { ServiceDescriptor.createWithInstance(Greeter::class.java, null) }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("no implementation defined")
        }
    }

    @Nested
    inner class `what the implementationId is for` {
        @Test
        fun `one implementation type registered for two services is one singleton`() {
            // The singleton cache is keyed by implementation, not by service, so a class that serves two
            // interfaces is instantiated once
            val registry = ServiceRegistry(ConfigManager())
            registry.addSingleton<Greeter, SpanishGreeter>()
            registry.addSingleton<Farewell, SpanishGreeter>()
            val provider = DefaultServiceProvider(registry)

            assertThat(provider.get<Greeter>()).isSameAs(provider.get<Farewell>())
        }

        @Test
        fun `the same type under two keys is two singletons`() {
            val registry = ServiceRegistry(ConfigManager())
            registry.addSingleton<Greeter, SpanishGreeter>("formal")
            registry.addSingleton<Greeter, SpanishGreeter>("casual")
            val provider = DefaultServiceProvider(registry)

            assertThat(provider.get<Greeter>("formal")).isNotSameAs(provider.get<Greeter>("casual"))
        }
    }

    private fun fromType(key: String? = null) =
        ServiceDescriptor.createWithImplementationType(Greeter::class.java, SpanishGreeter::class.java, Singleton, key)

    private fun fromInstance(key: String? = null) =
        ServiceDescriptor.createWithInstance(Greeter::class.java, SpanishGreeter(), Singleton, key)

    private fun fromFactory(key: String? = null) =
        ServiceDescriptor.createWithImplementationFactory(Greeter::class.java, { SpanishGreeter() }, Singleton, key)

    interface Greeter

    interface Farewell

    class SpanishGreeter: Greeter, Farewell {
        override fun equals(other: Any?) = other is SpanishGreeter

        override fun hashCode() = 1
    }

    class GreeterFactory: ImplementationFactory<Greeter> {
        override fun invoke(services: ServiceProvider): Greeter = SpanishGreeter()
    }
}
