@file:Suppress("ClassName")

package dev.botta.trantor.core.jobs

import dev.botta.trantor.config.Config
import dev.botta.trantor.config.ConfigManager
import dev.botta.trantor.config.ConfigSection
import dev.botta.trantor.config.providers.addMemoryCollection
import dev.botta.trantor.core.queues.*
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class JobQueueRegistryTest {
    @Nested
    inner class `adding a queue` {
        @Test
        fun `it can be asked for by name`() {
            registry.addQueue("emails", FakeQueue("emails"))

            assertThat(registry.getQueue("emails").name).isEqualTo("emails")
        }

        @Test
        fun `the name is not case sensitive, like configuration`() {
            registry.addQueue("Emails", FakeQueue("emails"))

            assertThat(registry.getQueue("EMAILS").name).isEqualTo("emails")
        }

        @Test
        fun `adding the same name twice is a mistake worth stopping for`() {
            registry.addQueue("emails", FakeQueue("emails"))

            assertThatThrownBy { registry.addQueue("emails", FakeQueue("other")) }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("Queue emails already exists")
        }

        @Test
        fun `a queue nobody added says so instead of returning nothing`() {
            registry.addQueue("emails", FakeQueue("emails"))

            assertThatThrownBy { registry.getQueue("reports") }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("Queue reports does not exist")
        }

        @Test
        fun `asking before anything is registered says that, not that the queue is missing`() {
            assertThatThrownBy { registry.getQueue("emails") }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("There are no registered queues")
        }
    }

    @Nested
    inner class `the default queue` {
        @Test
        fun `is the first one added`() {
            registry.addQueue("emails", FakeQueue("emails"))
            registry.addQueue("reports", FakeQueue("reports"))

            assertThat(registry.getDefaultQueue().name).isEqualTo("emails")
        }

        @Test
        fun `is what you get when you ask for no queue in particular`() {
            registry.addQueue("emails", FakeQueue("emails"))

            assertThat(registry.getQueue(null).name).isEqualTo("emails")
        }
    }

    @Nested
    inner class `loading from configuration` {
        @Test
        fun `builds each queue with the driver it names`() {
            val config = configOf(
                "jobs.queues.emails.driver" to "fake",
                "jobs.queues.reports.driver" to "fake",
            )
            registry.addQueueDriver("fake", FakeQueueFactory())

            registry.loadFromConfig(config)

            assertThat(registry.getQueue("emails").name).isEqualTo("emails")
            assertThat(registry.getQueue("reports").name).isEqualTo("reports")
        }

        @Test
        fun `the key names the queue unless the configuration says otherwise`() {
            val config = configOf(
                "jobs.queues.emails.driver" to "fake",
                "jobs.queues.emails.name" to "app-emails-production",
            )
            registry.addQueueDriver("fake", FakeQueueFactory())

            registry.loadFromConfig(config)

            assertThat(registry.getQueue("emails").name).isEqualTo("app-emails-production")
        }

        @Test
        fun `gives the driver the section the queue is declared in, whatever the queue is called`() {
            val config = configOf(
                "jobs.queues.emails.driver" to "fake",
                "jobs.queues.emails.name" to "app-emails-production",
                "jobs.queues.emails.region" to "sa-east-1",
            )
            val factory = FakeQueueFactory()
            registry.addQueueDriver("fake", factory)

            registry.loadFromConfig(config)

            assertThat(factory.sections.single().path).isEqualTo("jobs.queues.emails")
            assertThat(factory.sections.single()["region"]).isEqualTo("sa-east-1")
        }

        @Test
        fun `reads the section it is told`() {
            val config = configOf("workers.queues.emails.driver" to "fake")
            val factory = FakeQueueFactory()
            registry.addQueueDriver("fake", factory)

            registry.loadFromConfig(config, "workers.queues")

            assertThat(factory.sections.single().path).isEqualTo("workers.queues.emails")
        }

        @Test
        fun `the default is the one the configuration points at`() {
            val config = configOf(
                "jobs.queues.default" to "reports",
                "jobs.queues.emails.driver" to "fake",
                "jobs.queues.reports.driver" to "fake",
            )
            registry.addQueueDriver("fake", FakeQueueFactory())

            registry.loadFromConfig(config)

            assertThat(registry.getDefaultQueue().name).isEqualTo("reports")
        }

        @Test
        fun `without a default the first queue keeps the job`() {
            val config = configOf(
                "jobs.queues.emails.driver" to "fake",
                "jobs.queues.reports.driver" to "fake",
            )
            registry.addQueueDriver("fake", FakeQueueFactory())

            registry.loadFromConfig(config)

            assertThat(registry.getDefaultQueue()).isNotNull()
        }

        @Test
        fun `a driver nobody registered says which one is missing`() {
            val config = configOf("jobs.queues.emails.driver" to "rabbitmq")

            assertThatThrownBy { registry.loadFromConfig(config) }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("Queue driver rabbitmq not registered")
        }

        @Test
        fun `a queue without a driver says which queue it is`() {
            val config = configOf("jobs.queues.emails.name" to "emails")

            assertThatThrownBy { registry.loadFromConfig(config) }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("missing driver for queue emails")
        }

        @Test
        fun `the driver name is not case sensitive either`() {
            val config = configOf("jobs.queues.emails.driver" to "FAKE")
            registry.addQueueDriver("fake", FakeQueueFactory())

            registry.loadFromConfig(config)

            assertThat(registry.getQueue("emails")).isNotNull()
        }

        @Test
        fun `a section that says nothing leaves the registry empty`() {
            registry.addQueueDriver("fake", FakeQueueFactory())

            registry.loadFromConfig(configOf())

            assertThatThrownBy { registry.getDefaultQueue() }
                .hasMessageContaining("There are no registered queues")
        }
    }

    private fun configOf(vararg pairs: Pair<String, String?>): Config =
        ConfigManager().apply { addMemoryCollection(*pairs) }

    private class FakeQueueFactory: QueueFactory {
        val sections = mutableListOf<ConfigSection>()

        override fun createFromConfig(name: String, section: ConfigSection): MessageQueue {
            sections += section
            return FakeQueue(name)
        }
    }

    private class FakeQueue(override val name: String): MessageQueue {
        override val system = "test_queue"

        override fun enqueue(message: Message, options: EnqueueOptions) {}

        override fun poll() = emptyList<ReceivedMessage>()

        override fun clear() {}

        override fun size() = 0

        override fun delete(message: ReceivedMessage) {}
    }

    private val registry = JobQueueRegistry()
}
