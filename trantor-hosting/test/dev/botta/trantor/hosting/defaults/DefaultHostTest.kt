@file:Suppress("ClassName")

package dev.botta.trantor.hosting.defaults

import dev.botta.trantor.config.ConfigManager
import dev.botta.trantor.di.DefaultServiceProvider
import dev.botta.trantor.di.ServiceRegistry
import dev.botta.trantor.hosting.HostEnvironment
import dev.botta.trantor.hosting.HostedService
import dev.botta.trantor.hosting.addHostedService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class DefaultHostTest {
    @Nested
    inner class `starting` {
        @Test
        fun `starts every hosted service, in the order they were registered`() {
            registry.addHostedService(RecordingService("first"))
            registry.addHostedService(RecordingService("second"))

            host().start()

            assertThat(calls).containsExactly("start first", "start second")
        }

        @Test
        fun `starting twice starts the services once`() {
            registry.addHostedService(RecordingService("only"))
            val host = host()

            host.start()
            host.start()

            assertThat(calls).containsExactly("start only")
        }

        @Test
        fun `with nothing registered it still starts`() {
            host().start()

            assertThat(calls).isEmpty()
        }

        @Test
        fun `tells whoever is waiting that it is up`() {
            val host = host()
            host.lifetime.onStarted { calls.add("notified") }

            host.start()

            assertThat(calls).containsExactly("notified")
        }
    }

    @Nested
    inner class `stopping` {
        @Test
        fun `stops in reverse order, so a service outlives what it depends on`() {
            registry.addHostedService(RecordingService("first"))
            registry.addHostedService(RecordingService("second"))
            val host = host()
            host.start()
            calls.clear()

            host.stop()

            assertThat(calls).containsExactly("stop second", "stop first")
        }

        @Test
        fun `passes on the timeout it was given`() {
            registry.addHostedService(RecordingService("only"))
            val host = host()
            host.start()

            host.stop(timeoutSeconds = 5)

            assertThat(timeouts).containsExactly(5)
        }

        @Test
        fun `one service failing does not leave the others running`() {
            registry.addHostedService(RecordingService("first"))
            registry.addHostedService(FailingService())
            registry.addHostedService(RecordingService("third"))
            val host = host()
            host.start()
            calls.clear()

            host.stop()

            assertThat(calls).containsExactly("stop third", "stop first")
        }

        @Test
        fun `says it is stopping, then that it stopped`() {
            val host = host()
            host.lifetime.onStopping { calls.add("stopping") }
            host.lifetime.onStopped { calls.add("stopped") }
            host.start()

            host.stop()

            assertThat(calls).containsExactly("stopping", "stopped")
        }

        @Test
        fun `stopping before starting stops nothing`() {
            registry.addHostedService(RecordingService("only"))

            host().stop()

            assertThat(calls).isEmpty()
        }
    }

    @Nested
    inner class `run with a body` {
        @Test
        fun `starts, runs it, and stops`() {
            registry.addHostedService(RecordingService("only"))

            host().run { calls.add("body") }

            assertThat(calls).containsExactly("start only", "body", "stop only")
        }
    }

    private fun host() = DefaultHost(
        DefaultServiceProvider(registry),
        config,
        HostEnvironment("test", "an-app"),
        DefaultHostLifetime(),
    )

    private inner class RecordingService(override val name: String): HostedService {
        override fun start() {
            calls.add("start $name")
        }

        override fun stop(timeoutSeconds: Int) {
            calls.add("stop $name")
            timeouts.add(timeoutSeconds)
        }
    }

    private inner class FailingService: HostedService {
        override val name = "failing"

        override fun start() {
            calls.add("start failing")
        }

        override fun stop(timeoutSeconds: Int) {
            error("this service cannot be stopped")
        }
    }

    private val calls = mutableListOf<String>()
    private val timeouts = mutableListOf<Int>()
    private val config = ConfigManager()
    private val registry = ServiceRegistry(config)
}
