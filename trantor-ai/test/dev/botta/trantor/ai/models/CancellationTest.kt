@file:Suppress("ClassName")

package dev.botta.trantor.ai.models

import dev.botta.trantor.ai.errors.CancelledError
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class CancellationTest {
    @Test
    fun `starts not cancelled`() {
        assertThat(cancellation.isCancelled).isFalse()
    }

    @Test
    fun `runs the callbacks when cancelled`() {
        var calls = 0
        cancellation.onCancel { calls++ }

        cancellation.cancel()

        assertThat(cancellation.isCancelled).isTrue()
        assertThat(calls).isEqualTo(1)
    }

    @Test
    fun `runs every callback`() {
        val calls = mutableListOf<String>()
        cancellation.onCancel { calls.add("first") }
        cancellation.onCancel { calls.add("second") }

        cancellation.cancel()

        assertThat(calls).containsExactly("first", "second")
    }

    @Test
    fun `cancelling twice runs the callbacks once`() {
        var calls = 0
        cancellation.onCancel { calls++ }

        cancellation.cancel()
        cancellation.cancel()

        assertThat(calls).isEqualTo(1)
    }

    @Test
    fun `does not run a callback that was unregistered`() {
        var calls = 0
        val registration = cancellation.onCancel { calls++ }

        registration.close()
        cancellation.cancel()

        assertThat(calls).isZero()
    }

    @Test
    fun `runs a callback registered after cancelling right away`() {
        var calls = 0
        cancellation.cancel()

        cancellation.onCancel { calls++ }

        assertThat(calls).isEqualTo(1)
    }

    @Test
    fun `throws when cancelled`() {
        cancellation.cancel()

        assertThatThrownBy { cancellation.throwIfCancelled() }.isInstanceOf(CancelledError::class.java)
    }

    @Test
    fun `does not throw when not cancelled`() {
        cancellation.throwIfCancelled()
    }

    private val cancellation = Cancellation()
}
