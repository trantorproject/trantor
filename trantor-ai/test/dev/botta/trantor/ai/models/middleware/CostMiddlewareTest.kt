@file:Suppress("ClassName")

package dev.botta.trantor.ai.models.middleware

import dev.botta.trantor.ai.models.Usage
import dev.botta.trantor.ai.models.catalog.ModelCatalog
import dev.botta.trantor.ai.models.chat.ChatRequest
import dev.botta.trantor.ai.models.chat.StreamPart
import dev.botta.trantor.ai.models.chat.TextPart
import dev.botta.trantor.ai.providers.anthropic.addAnthropicModels
import dev.botta.trantor.ai.providers.openai.OpenAIChatModel
import dev.botta.trantor.ai.providers.openai.OpenAIConfig
import dev.botta.trantor.ai.providers.openai.addOpenAIModels
import dev.botta.trantor.ai.testing.FakeChatModel
import dev.botta.trantor.ai.testing.FakeHttpClient
import dev.botta.trantor.domain.Money
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class CostMiddlewareTest {
    @Nested
    inner class `a generation` {
        @Test
        fun `comes back with what it probably cost`() {
            // The usage of the recorded stream-text answer, at the list price of Sonnet 4.5: 15 × 3 + 28 × 15
            val response = model.with(CostMiddleware(catalog)).generate(ChatRequest("Hola"))

            assertThat(response.info.estimatedCost?.total).isEqualTo(Money("0.000465"))
        }

        @Test
        fun `and is otherwise the one the model gave`() {
            val response = model.with(CostMiddleware(catalog)).generate(ChatRequest("Hola"))

            assertThat(response.copy(info = response.info.copy(estimatedCost = null)))
                .isEqualTo(model.generate(ChatRequest("Hola")))
        }

        @Test
        fun `of a model with no price comes back without one`() {
            // As an application adds a model the price list does not have yet
            val catalog = catalog.add("anthropic/claude-unpriced", like = "anthropic/claude-sonnet-4-5")
            val unpriced = FakeChatModel(modelId = "claude-unpriced", provider = "anthropic", usage = model.usage)

            val response = unpriced.with(CostMiddleware(catalog)).generate(ChatRequest("Hola"))

            assertThat(response.info.estimatedCost).isNull()
        }
    }

    @Nested
    inner class `a stream` {
        @Test
        fun `hands over its parts as they come`() {
            model.parts = listOf(StreamPart.TextDelta("Ho"), StreamPart.TextDelta("la"))

            val parts = model.with(CostMiddleware(catalog)).stream(ChatRequest("Hola")).use { it.asSequence().toList() }

            assertThat(parts).isEqualTo(model.parts)
        }

        @Test
        fun `and its response comes back with what it probably cost`() {
            val response = model.with(CostMiddleware(catalog)).stream(ChatRequest("Hola")).use { it.response() }

            assertThat(response.info.estimatedCost?.total).isEqualTo(Money("0.000465"))
            assertThat(response.content).containsExactly(TextPart("ok"))
        }

        @Test
        fun `closing it closes the call underneath`() {
            model.with(CostMiddleware(catalog)).stream(ChatRequest("Hola")).close()

            assertThat(model.streamClosed).isTrue()
        }
    }

    @Test
    fun `a recorded call is estimated at the list price, each part of its input at its own`() {
        // gpt-5.6-luna reading a cached prefix: 3 × 0.20 + 5125 × 0.02 read + 13 × 0.25 written + 15 × 1.20 out
        val http = FakeHttpClient().apply { body = javaClass.getResource("/openai/chat/cache-read.json")!!.readText() }
        val luna = OpenAIChatModel("gpt-5.6-luna", OpenAIConfig(apiKey = "sk-test"), http)

        val estimate = luna.with(CostMiddleware(ModelCatalog().addOpenAIModels()))
            .generate(ChatRequest("Hola")).info.estimatedCost!!

        assertThat(estimate.uncachedInput).isEqualTo(Money("0.0000006"))
        assertThat(estimate.cacheRead).isEqualTo(Money("0.0001025"))
        assertThat(estimate.cacheWrite).isEqualTo(Money("0.00000325"))
        assertThat(estimate.total).isEqualTo(Money("0.00012435"))
    }

    private val catalog = ModelCatalog().addAnthropicModels()
    private val model = FakeChatModel(
        modelId = "claude-sonnet-4-5",
        provider = "anthropic",
        usage = Usage(inputTokens = 15, outputTokens = 28),
    )
}
