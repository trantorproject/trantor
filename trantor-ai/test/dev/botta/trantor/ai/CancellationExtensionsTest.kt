@file:Suppress("ClassName")

package dev.botta.trantor.ai

import dev.botta.trantor.ai.errors.CancelledError
import dev.botta.trantor.primitives.Cancellation
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class CancellationExtensionsTest {
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
