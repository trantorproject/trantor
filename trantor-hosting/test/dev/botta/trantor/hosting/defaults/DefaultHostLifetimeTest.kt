@file:Suppress("ClassName")

package dev.botta.trantor.hosting.defaults

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class DefaultHostLifetimeTest {
    @Nested
    inner class `handlers registered before it happens` {
        @Test
        fun `are called when it does`() {
            lifetime.onStarted { calls.add("started") }
            lifetime.onStopping { calls.add("stopping") }
            lifetime.onStopped { calls.add("stopped") }

            lifetime.notifyStarted()
            lifetime.notifyStopping()
            lifetime.notifyStopped()

            assertThat(calls).containsExactly("started", "stopping", "stopped")
        }

        @Test
        fun `are all called, in the order they were added`() {
            lifetime.onStarted { calls.add("first") }
            lifetime.onStarted { calls.add("second") }

            lifetime.notifyStarted()

            assertThat(calls).containsExactly("first", "second")
        }

        @Test
        fun `are not called before it happens`() {
            lifetime.onStarted { calls.add("started") }

            assertThat(calls).isEmpty()
        }
    }

    @Nested
    inner class `handlers registered late` {
        @Test
        fun `run straight away, so nothing is missed by subscribing after the fact`() {
            lifetime.notifyStarted()

            lifetime.onStarted { calls.add("started") }

            assertThat(calls).containsExactly("started")
        }

        @Test
        fun `a stopping handler added while stopping runs too`() {
            lifetime.notifyStarted()
            lifetime.notifyStopping()

            lifetime.onStopping { calls.add("stopping") }

            assertThat(calls).containsExactly("stopping")
        }

        @Test
        fun `once stopped, every earlier stage counts as passed`() {
            lifetime.notifyStarted()
            lifetime.notifyStopping()
            lifetime.notifyStopped()

            lifetime.onStarted { calls.add("started") }
            lifetime.onStopping { calls.add("stopping") }
            lifetime.onStopped { calls.add("stopped") }

            assertThat(calls).containsExactly("started", "stopping", "stopped")
        }
    }

    @Nested
    inner class `the stages only happen once, and in order` {
        @Test
        fun `starting twice notifies once`() {
            lifetime.onStarted { calls.add("started") }

            lifetime.notifyStarted()
            lifetime.notifyStarted()

            assertThat(calls).containsExactly("started")
        }

        @Test
        fun `stopping before starting does nothing`() {
            lifetime.onStopping { calls.add("stopping") }

            lifetime.notifyStopping()

            assertThat(calls).isEmpty()
        }

        @Test
        fun `stopped before stopping does nothing`() {
            lifetime.onStopped { calls.add("stopped") }

            lifetime.notifyStarted()
            lifetime.notifyStopped()

            assertThat(calls).isEmpty()
        }
    }

    @Nested
    inner class `stopApplication` {
        @Test
        fun `is what asks the host to wind down`() {
            lifetime.onStopping { calls.add("stopping") }
            lifetime.notifyStarted()

            lifetime.stopApplication()

            assertThat(calls).containsExactly("stopping")
        }

        @Test
        fun `asking twice only winds down once`() {
            lifetime.onStopping { calls.add("stopping") }
            lifetime.notifyStarted()

            lifetime.stopApplication()
            lifetime.stopApplication()

            assertThat(calls).containsExactly("stopping")
        }
    }

    @Test
    fun `a handler that fails does not stop the rest, because shutdown has to finish`() {
        lifetime.onStopping { error("boom") }
        lifetime.onStopping { calls.add("second") }
        lifetime.notifyStarted()

        lifetime.notifyStopping()

        assertThat(calls).containsExactly("second")
    }

    private val calls = mutableListOf<String>()
    private val lifetime = DefaultHostLifetime()
}
