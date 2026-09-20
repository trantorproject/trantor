@file:Suppress("ClassName")

package dev.botta.trantor.core

import dev.botta.trantor.config.Config
import dev.botta.trantor.config.ConfigManager
import dev.botta.trantor.config.providers.addMemoryCollection
import dev.botta.trantor.core.cache.CacheModule
import dev.botta.trantor.core.cache.DefaultInMemoryCacheFactory
import dev.botta.trantor.core.cache.InMemoryCacheFactory
import dev.botta.trantor.core.events.DefaultEventDispatcher
import dev.botta.trantor.core.events.EventsModule
import dev.botta.trantor.core.events.serialization.DefaultEventSerializer
import dev.botta.trantor.core.jobs.*
import dev.botta.trantor.core.jobs.serialization.DefaultJobSerializer
import dev.botta.trantor.core.jobs.serialization.JobSerializer
import dev.botta.trantor.core.queues.*
import dev.botta.trantor.core.tx.NullTransaction
import dev.botta.trantor.core.tx.NullTransactionManager
import dev.botta.trantor.core.tx.TransactionManager
import dev.botta.trantor.core.tx.TransactionsModule
import dev.botta.trantor.di.DefaultServiceProvider
import dev.botta.trantor.di.ServiceNotRegisteredError
import dev.botta.trantor.di.ServiceRegistry
import dev.botta.trantor.hosting.addModule
import dev.botta.trantor.primitives.events.Event
import dev.botta.trantor.primitives.events.EventDispatcher
import dev.botta.trantor.primitives.events.EventHandler
import dev.botta.trantor.primitives.events.EventListener
import dev.botta.trantor.primitives.events.serialization.EventSerializer
import dev.botta.trantor.primitives.serialization.JsonSerializer
import dev.botta.trantor.serialization.gson.GsonSerializer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ModulesTest {
    @Nested
    inner class `events` {
        @Test
        fun `brings a dispatcher and a serializer`() {
            registry.addTheModulesAnApplicationGets()

            assertThat(provider().get<EventDispatcher>()).isInstanceOf(DefaultEventDispatcher::class.java)
            assertThat(provider().get<EventSerializer>()).isInstanceOf(DefaultEventSerializer::class.java)
        }

        @Test
        fun `the dispatcher does not work with only its own module`() {
            // Unlike an addX() extension, a Module does not pull in what it depends on: DefaultEventDispatcher
            // needs the TransactionManager and the JobDispatcher that two other modules bring. Resolution is
            // lazy, so registration order does not matter, but registering only this one does not hold up
            registry.addModule(EventsModule())

            assertThatThrownBy { provider().get<EventDispatcher>() }
                .isInstanceOf(ServiceNotRegisteredError::class.java)
        }

        @Test
        fun `leaves alone what the application already registered`() {
            registry.addSingleton<EventDispatcher>(OwnDispatcher())

            registry.addModule(EventsModule())

            assertThat(provider().get<EventDispatcher>()).isInstanceOf(OwnDispatcher::class.java)
        }
    }

    @Nested
    inner class `jobs` {
        @Test
        fun `brings the registries, the dispatcher and the serializer`() {
            registry.addTheModulesAnApplicationGets()

            assertThat(provider().get<JobQueueRegistry>()).isNotNull()
            assertThat(provider().get<JobHandlerRegistry>()).isNotNull()
            assertThat(provider().get<JobDispatcher>()).isInstanceOf(DefaultJobDispatcher::class.java)
            assertThat(provider().get<JobSerializer>()).isInstanceOf(DefaultJobSerializer::class.java)
        }

        @Test
        fun `the queues come from the configuration when the host initializes it`() {
            config.addMemoryCollection("jobs.queues.emails.driver" to "fake")
            registry.addTheModulesAnApplicationGets()
            val services = provider()
            services.get<JobQueueRegistry>().addQueueDriver("fake", FakeQueueFactory())

            JobsModule().initialize(services, config)

            assertThat(services.get<JobQueueRegistry>().getQueue("emails")).isNotNull()
        }
    }

    @Nested
    inner class `transactions` {
        @Test
        fun `bring a manager that does nothing, so an application without a database still runs`() {
            registry.addModule(TransactionsModule())

            assertThat(provider().get<TransactionManager>()).isInstanceOf(NullTransactionManager::class.java)
        }

        @Test
        fun `and step aside once there is a real one`() {
            registry.addSingleton<TransactionManager>(OwnTransactionManager())

            registry.addModule(TransactionsModule())

            assertThat(provider().get<TransactionManager>()).isInstanceOf(OwnTransactionManager::class.java)
        }
    }

    @Nested
    inner class `cache` {
        @Test
        fun `brings a factory that builds caches around the transaction manager`() {
            registry.addModule(TransactionsModule())
            registry.addModule(CacheModule())

            val factory = provider().get<InMemoryCacheFactory>()

            assertThat(factory).isInstanceOf(DefaultInMemoryCacheFactory::class.java)
            assertThat(factory.create<String, String>()).isNotNull()
        }
    }

    @Nested
    inner class `several modules together` {
        @Test
        fun `each brings its own, and none gets in the way`() {
            registry.addTheModulesAnApplicationGets()

            val services = provider()

            assertThat(services.get<EventDispatcher>()).isNotNull()
            assertThat(services.get<JobDispatcher>()).isNotNull()
            assertThat(services.get<TransactionManager>()).isNotNull()
            assertThat(services.get<InMemoryCacheFactory>()).isNotNull()
        }
    }

    private fun provider() = DefaultServiceProvider(registry)

    /** What ApplicationBuilder registers, in its order. They only resolve as a set. */
    private fun ServiceRegistry.addTheModulesAnApplicationGets() {
        addModule(TransactionsModule())
        addModule(EventsModule())
        addModule(CacheModule())
        addModule(JobsModule())
    }

    private class OwnDispatcher: EventDispatcher {
        override fun publish(event: Event) {}

        override fun subscribe(handler: EventHandler) {}

        override fun defer(block: () -> Unit) {}

        override fun <T: Event> on(eventType: kotlin.reflect.KClass<T>, listener: EventListener<T>) {}
    }

    private class OwnTransactionManager: TransactionManager {
        override val activeTransaction = null

        override fun beginTransaction() = NullTransaction()
    }

    private class FakeQueueFactory: QueueFactory {
        override fun createFromConfig(name: String, config: Config) = FakeQueue(name)
    }

    private class FakeQueue(override val name: String): MessageQueue {
        override fun enqueue(message: Message, options: EnqueueOptions) {}

        override fun poll() = emptyList<ReceivedMessage>()

        override fun clear() {}

        override fun size() = 0

        override fun delete(message: ReceivedMessage) {}
    }

    private val config = ConfigManager()

    // The host registers it; the job and event serializers are built on it
    private val registry = ServiceRegistry(config).apply { addSingleton<JsonSerializer>(GsonSerializer()) }
}
