@file:Suppress("ClassName")

package dev.botta.trantor.ai.models.middleware

import dev.botta.trantor.ai.errors.*
import dev.botta.trantor.ai.models.CallOptions
import dev.botta.trantor.ai.models.ResponseInfo
import dev.botta.trantor.ai.models.chat.*
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RetryMiddlewareTest {
    @Nested
    inner class `generating` {
        @Test
        fun `tries again after a rate limit`() {
            val model = FlakyChatModel(failures = 2) { RateLimitError("openai") }

            val response = model.with(retry()).generate(ChatRequest("Hola"))

            assertThat(response.text).isEqualTo("ok")
            assertThat(model.attempts).isEqualTo(3)
        }

        @Test
        fun `gives up with the last error it saw`() {
            val model = FlakyChatModel(failures = 5) { ProviderUnavailableError("openai", "boom") }

            assertThatThrownBy { model.with(retry()).generate(ChatRequest("Hola")) }
                .isInstanceOf(ProviderUnavailableError::class.java)
                .hasMessageContaining("boom")

            assertThat(model.attempts).isEqualTo(3)
        }

        @Test
        fun `a bad api key is not worth trying again`() {
            val model = FlakyChatModel(failures = 5) { AuthenticationError("openai", "bad key") }

            assertThatThrownBy { model.with(retry()).generate(ChatRequest("Hola")) }
                .isInstanceOf(AuthenticationError::class.java)

            assertThat(model.attempts).isEqualTo(1)
        }

        @Test
        fun `a request the provider rejected is not worth trying again`() {
            val model = FlakyChatModel(failures = 5) { ProviderError("openai", "unsupported value", status = 400) }

            assertThatThrownBy { model.with(retry()).generate(ChatRequest("Hola")) }
                .isInstanceOf(ProviderError::class.java)

            assertThat(model.attempts).isEqualTo(1)
        }

        @Test
        fun `a cancelled call stays cancelled`() {
            val model = FlakyChatModel(failures = 5) { CancelledError() }

            assertThatThrownBy { model.with(retry()).generate(ChatRequest("Hola")) }
                .isInstanceOf(CancelledError::class.java)

            assertThat(model.attempts).isEqualTo(1)
        }

        @Test
        fun `a connection that broke is worth trying again`() {
            val model = FlakyChatModel(failures = 1) { java.io.IOException("connection reset") }

            model.with(retry()).generate(ChatRequest("Hola"))

            assertThat(model.attempts).isEqualTo(2)
        }

        @Test
        fun `a timeout is worth trying again`() {
            val model = FlakyChatModel(failures = 1) { TimeoutError() }

            model.with(retry()).generate(ChatRequest("Hola"))

            assertThat(model.attempts).isEqualTo(2)
        }
    }

    @Nested
    inner class `how long it waits` {
        @Test
        fun `backs off further on every attempt`() {
            val model = FlakyChatModel(failures = 2) { ProviderUnavailableError("openai") }

            model.with(retry()).generate(ChatRequest("Hola"))

            assertThat(waits).hasSize(2)
            assertThat(waits[1]).isGreaterThan(waits[0])
        }

        @Test
        fun `does what the provider asked instead of backing off`() {
            val model = FlakyChatModel(failures = 1) { RateLimitError("openai", retryAfter = 7.seconds) }

            model.with(retry()).generate(ChatRequest("Hola"))

            assertThat(waits).containsExactly(7.seconds)
        }

        @Test
        fun `never waits longer than the top`() {
            val model = FlakyChatModel(failures = 1) { RateLimitError("openai", retryAfter = 10.seconds) }

            model.with(retry(maxDelay = 2.seconds)).generate(ChatRequest("Hola"))

            assertThat(waits).containsExactly(2.seconds)
        }
    }

    @Nested
    inner class `streaming` {
        @Test
        fun `tries again when the provider would not open the stream`() {
            val model = FlakyChatModel(failures = 2) { RateLimitError("openai") }

            val parts = model.with(retry()).stream(ChatRequest("Hola")).use { it.asSequence().toList() }

            assertThat(parts).hasSize(2)
            assertThat(model.attempts).isEqualTo(3)
        }

        @Test
        fun `opens the stream right away, so a no is an error where the call was made`() {
            val model = FlakyChatModel(failures = 5) { AuthenticationError("openai", "bad key") }

            assertThatThrownBy { model.with(retry()).stream(ChatRequest("Hola")) }
                .isInstanceOf(AuthenticationError::class.java)
        }

        @Test
        fun `tries again when it fails before the first part`() {
            val model = FlakyChatModel(failAfterParts = 0, failures = 1) { ProviderUnavailableError("openai") }

            val parts = model.with(retry()).stream(ChatRequest("Hola")).use { it.asSequence().toList() }

            assertThat(parts.map { (it as StreamPart.TextDelta).text }).containsExactly("Ho", "la")
        }

        @Test
        fun `does not try again once a part reached the caller`() {
            val model = FlakyChatModel(failAfterParts = 1, failures = 5) { ProviderUnavailableError("openai") }

            assertThatThrownBy {
                model.with(retry()).stream(ChatRequest("Hola")).use { it.asSequence().toList() }
            }.isInstanceOf(ProviderUnavailableError::class.java)

            assertThat(model.attempts).isEqualTo(1)
        }

        @Test
        fun `reading the whole response still tries again, since nothing came out`() {
            val model = FlakyChatModel(failAfterParts = 0, failures = 1) { ProviderUnavailableError("openai") }

            val response = model.with(retry()).stream(ChatRequest("Hola")).use { it.response() }

            assertThat(response.text).isEqualTo("ok")
            assertThat(model.attempts).isEqualTo(2)
        }
    }

    private fun retry(maxDelay: Duration = 30.seconds) =
        RetryMiddleware(maxAttempts = 3, initialDelay = 10.milliseconds, maxDelay = maxDelay, jitter = 0.0) {
            waits.add(it)
        }

    /**
     * Fails the first [failures] attempts with [error]. With [failAfterParts] it opens the stream instead and
     * fails while it is being read, after handing out that many parts.
     */
    private class FlakyChatModel(
        private val failures: Int,
        private val failAfterParts: Int? = null,
        private val error: () -> Throwable,
    ): ChatModel {
        override val provider = "flaky"
        override val modelId = "flaky-model"

        var attempts = 0

        override fun generate(request: ChatRequest, options: CallOptions): ChatResponse {
            attempts++
            if (attempts <= failures) throw error()

            return response()
        }

        override fun stream(request: ChatRequest, options: CallOptions): ChatStream {
            attempts++
            if (failAfterParts == null && attempts <= failures) throw error()

            return FlakyStream(if (attempts <= failures) failAfterParts else null)
        }

        private fun response() = ChatResponse(
            content = listOf(TextPart("ok")),
            finishReason = FinishReasons.Stop,
            info = ResponseInfo(model = modelId, provider = provider, latency = 1.milliseconds),
        )

        private inner class FlakyStream(private val failAfter: Int?): ChatStream {
            private val parts = listOf(StreamPart.TextDelta("Ho"), StreamPart.TextDelta("la"))
            private var given = 0

            override fun hasNext(): Boolean {
                if (given == failAfter) throw error()
                return given < parts.size
            }

            override fun next(): StreamPart {
                if (given == failAfter) throw error()
                return parts[given++]
            }

            override fun response(): ChatResponse {
                while (hasNext()) next()
                return this@FlakyChatModel.response()
            }

            override fun close() {}
        }
    }

    private val waits = mutableListOf<Duration>()
}
