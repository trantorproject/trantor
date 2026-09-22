@file:Suppress("ClassName")

package dev.botta.trantor.ai.models.cost

import dev.botta.trantor.ai.models.ResponseInfo
import dev.botta.trantor.ai.models.Usage
import dev.botta.trantor.ai.models.catalog.ModelCapabilities
import dev.botta.trantor.ai.models.catalog.ModelCatalog
import dev.botta.trantor.ai.models.catalog.ModelPricing
import dev.botta.trantor.ai.models.chat.ChatResponse
import dev.botta.trantor.ai.models.chat.FinishReasons
import dev.botta.trantor.ai.models.chat.TextPart
import dev.botta.trantor.domain.Money
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

class CostCalculatorTest {
    @Nested
    inner class `the estimate of a call` {
        @Test
        fun `prices each token at the price per million of its kind`() {
            // The usage of the recorded stream-text answer
            val estimate = estimate(Usage(inputTokens = 15, outputTokens = 28))!!

            assertThat(estimate.uncachedInput).isEqualTo(Money("0.000045"))
            assertThat(estimate.output).isEqualTo(Money("0.00042"))
            assertThat(estimate.total).isEqualTo(Money("0.000465"))
        }

        @Test
        fun `splits the input by what the cache did with it`() {
            val usage = Usage(inputTokens = 1000, outputTokens = 0, cacheReadTokens = 800, cacheWriteTokens = 150)

            val estimate = estimate(usage)!!

            assertThat(estimate.uncachedInput).isEqualTo(Money("0.00015"))
            assertThat(estimate.cacheRead).isEqualTo(Money("0.00024"))
            assertThat(estimate.cacheWrite).isEqualTo(Money("0.0005625"))
            assertThat(estimate.input).isEqualTo(Money("0.0009525"))
        }

        @Test
        fun `and charges a token read from the cache once, at the price of a read`() {
            // Charging the whole input at the plain price and the reads besides is the easy mistake, and the dear one
            val estimate = estimate(Usage(inputTokens = 1000, outputTokens = 0, cacheReadTokens = 1000))!!

            assertThat(estimate.input).isEqualTo(Money("0.0003"))
        }

        @Test
        fun `reasoning is told apart inside the output, not added to it`() {
            // The usage of the recorded stream-thinking answer
            val estimate = estimate(Usage(inputTokens = 0, outputTokens = 742, reasoningTokens = 456))!!

            assertThat(estimate.output).isEqualTo(Money("0.01113"))
            assertThat(estimate.reasoning).isEqualTo(Money("0.00684"))
            assertThat(estimate.total).isEqualTo(Money("0.01113"))
        }

        @Test
        fun `a cache the model has no price for is charged as plain input`() {
            catalog.add("openai/gpt-plain", ModelCapabilities(), ModelPricing(input = "2", output = "8"))

            val estimate = calculator.estimate(
                Usage(inputTokens = 1000, outputTokens = 0, cacheReadTokens = 800, cacheWriteTokens = 100),
                "openai",
                "gpt-plain",
            )!!

            assertThat(estimate.cacheRead).isEqualTo(Money("0.0016"))
            assertThat(estimate.cacheWrite).isEqualTo(Money("0.0002"))
            assertThat(estimate.input).isEqualTo(Money("0.002"))
        }

        @Test
        fun `of a model whose capabilities are a guess but whose price somebody wrote is made all the same`() {
            catalog.setLatest("anthropic", "anthropic/claude-sonnet-4-5")
            catalog.price("anthropic/claude-9", ModelPricing(input = "3", output = "15"))

            val estimate = estimateOf("claude-9")

            assertThat(estimate?.total).isEqualTo(Money("0.000465"))
        }

        @Test
        fun `of a model the catalog knows only the price of is made too, since a price is all it takes`() {
            val onlyPrices = ModelCatalog().price("anthropic/claude-9", ModelPricing(input = "3", output = "15"))
            val usage = Usage(inputTokens = 15, outputTokens = 28)

            val estimate = CostCalculator(onlyPrices).estimate(usage, "anthropic", "claude-9")

            assertThat(estimate?.total).isEqualTo(Money("0.000465"))
        }

        @Test
        fun `of a response is the one of the model that answered`() {
            // Providers answer with the dated snapshot, which is priced as its family
            val response = ChatResponse(
                content = listOf(TextPart("Hola")),
                finishReason = FinishReasons.Stop,
                usage = Usage(inputTokens = 15, outputTokens = 28),
                info = ResponseInfo(
                    model = "claude-sonnet-4-5-20250929",
                    provider = "anthropic",
                    latency = 10.milliseconds,
                ),
            )

            assertThat(calculator.estimate(response)?.total).isEqualTo(Money("0.000465"))
        }
    }

    @Nested
    inner class `there is no estimate` {
        @Test
        fun `for a model the catalog does not know`() {
            assertThat(estimateOf("claude-9")).isNull()
        }

        @Test
        fun `for one it knows without a price`() {
            catalog.add("anthropic/claude-free", ModelCapabilities())

            assertThat(estimateOf("claude-free")).isNull()
        }

        @Test
        fun `for one that stands in for the newest, whose capabilities are worth guessing and its price is not`() {
            // Being off by a tier is an estimate; being off by a whole model is a wrong number that looks right
            catalog.setLatest("anthropic", "anthropic/claude-sonnet-4-5")

            assertThat(estimateOf("claude-9")).isNull()
        }

        @Test
        fun `for one described as another, which takes what the other takes and not what it costs`() {
            catalog.add("anthropic/claude-sonnet-4-6", like = "anthropic/claude-sonnet-4-5")

            assertThat(estimateOf("claude-sonnet-4-6")).isNull()
        }

        @Test
        fun `for a usage whose input or output is unknown, since a part of the bill would pass for all of it`() {
            assertThat(estimate(Usage(outputTokens = 28))).isNull()
            assertThat(estimate(Usage(inputTokens = 15))).isNull()
        }
    }

    @Test
    fun `estimates of several calls add up part by part`() {
        val first = estimate(
            Usage(inputTokens = 1000, outputTokens = 742, cacheReadTokens = 800, reasoningTokens = 456)
        )!!
        val second = estimate(Usage(inputTokens = 15, outputTokens = 28))!!

        val total = first + second

        assertThat(total.uncachedInput).isEqualTo(first.uncachedInput + second.uncachedInput)
        assertThat(total.cacheRead).isEqualTo(first.cacheRead)
        assertThat(total.reasoning).isEqualTo(first.reasoning)
        assertThat(total.total).isEqualTo(first.total + second.total)
    }

    private fun estimate(usage: Usage) = calculator.estimate(usage, "anthropic", "claude-sonnet-4-5")

    /** The usage of the recorded stream-text answer, as if another model had given it. */
    private fun estimateOf(modelId: String) =
        calculator.estimate(Usage(inputTokens = 15, outputTokens = 28), "anthropic", modelId)

    // Numbers for the arithmetic, not the price list of any model
    private val catalog = ModelCatalog().add(
        "anthropic/claude-sonnet-4-5",
        ModelCapabilities(),
        ModelPricing(input = "3", output = "15", cacheRead = "0.3", cacheWrite = "3.75"),
    )
    private val calculator = CostCalculator(catalog)
}
