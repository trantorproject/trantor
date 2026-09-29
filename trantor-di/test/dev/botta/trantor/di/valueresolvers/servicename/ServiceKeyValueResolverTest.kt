@file:Suppress("ClassName")

package dev.botta.trantor.di.valueresolvers.servicename

import dev.botta.trantor.config.ConfigManager
import dev.botta.trantor.di.DefaultServiceProvider
import dev.botta.trantor.di.ServiceNotRegisteredError
import dev.botta.trantor.di.ServiceRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ServiceKeyValueResolverTest {
    @Nested
    inner class `picking one of several registrations` {
        @Test
        fun `each parameter gets the one it named`() {
            registry.addSingleton<Queue>(Queue("emails"), "emails")
            registry.addSingleton<Queue>(Queue("reports"), "reports")

            val worker = provider.create<Worker>()

            assertThat(worker.emails.name).isEqualTo("emails")
            assertThat(worker.reports.name).isEqualTo("reports")
        }

        @Test
        fun `the key is what distinguishes them, not the order`() {
            registry.addSingleton<Queue>(Queue("reports"), "reports")
            registry.addSingleton<Queue>(Queue("emails"), "emails")

            assertThat(provider.create<Worker>().emails.name).isEqualTo("emails")
        }

        @Test
        fun `an unkeyed registration is not what a keyed parameter asked for`() {
            registry.addSingleton<Queue>(Queue("default"))

            assertThatThrownBy { provider.create<WantsEmails>() }
                .isInstanceOf(ServiceNotRegisteredError::class.java)
        }
    }

    @Nested
    inner class `when nothing is registered under that key` {
        @Test
        fun `a parameter with a default keeps it`() {
            val worker = provider.create<WantsOptionalEmails>()

            assertThat(worker.emails).isNull()
        }

        @Test
        fun `a parameter without one says the type and the key`() {
            assertThatThrownBy { provider.create<WantsEmails>() }
                .isInstanceOf(ServiceNotRegisteredError::class.java)
                .hasMessageContaining(Queue::class.java.name)
                .hasMessageContaining("emails")
        }
    }

    class Queue(val name: String)

    class Worker(
        @ServiceKey("emails") val emails: Queue,
        @ServiceKey("reports") val reports: Queue,
    )

    class WantsEmails(@ServiceKey("emails") val emails: Queue)

    class WantsOptionalEmails(@ServiceKey("emails") val emails: Queue? = null)

    private val config = ConfigManager()
    private val registry = ServiceRegistry(config)
    private val provider = DefaultServiceProvider(registry)
}
