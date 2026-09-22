@file:Suppress("ClassName")

package dev.botta.trantor.ai.generation

import dev.botta.json.Json
import dev.botta.trantor.ai.models.ModelWarning
import dev.botta.trantor.ai.models.ResponseInfo
import dev.botta.trantor.ai.models.Usage
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.ai.models.cost.CostEstimate
import dev.botta.trantor.domain.Money
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

class RunResultTest {
    @Test
    fun `the answer is the one of the last step`() {
        val result = RunResult(listOf(step(content = listOf(toolCall)), step(content = listOf(TextPart("7")))))

        assertThat(result.text).isEqualTo("7")
        assertThat(result.finishReason).isEqualTo(FinishReasons.Stop)
        assertThat(result.response).isSameAs(result.steps.last().response)
    }

    @Nested
    inner class usage {
        @Test
        fun `adds up every step`() {
            val result = RunResult(
                listOf(
                    step(usage = Usage(inputTokens = 100, outputTokens = 20, cacheReadTokens = 80)),
                    step(usage = Usage(inputTokens = 130, outputTokens = 10, cacheReadTokens = 100)),
                ),
            )

            assertThat(result.usage)
                .isEqualTo(Usage(inputTokens = 230, outputTokens = 30, cacheReadTokens = 180))
        }

        @Test
        fun `what no step reported stays unknown`() {
            val result = RunResult(listOf(step(usage = Usage.Unknown), step(usage = Usage.Unknown)))

            assertThat(result.usage.inputTokens).isNull()
            assertThat(result.usage.totalTokens).isNull()
        }

        @Test
        fun `keeps no raw usage, which belongs to a single call`() {
            val result = RunResult(listOf(step(usage = Usage(inputTokens = 1, raw = Json.obj("x" to 1)))))

            assertThat(result.usage.raw).isNull()
            assertThat(result.usage.inputTokens).isEqualTo(1)
        }
    }

    @Nested
    inner class `estimated cost` {
        @Test
        fun `adds up every step`() {
            val result = RunResult(
                listOf(step(cost = cost("0.001", "0.004")), step(cost = cost("0.002", "0.001"))),
            )

            assertThat(result.estimatedCost?.input).isEqualTo(Money("0.003"))
            assertThat(result.estimatedCost?.output).isEqualTo(Money("0.005"))
        }

        @Test
        fun `is unknown when a step has none, since a partial sum would pass for the whole`() {
            val result = RunResult(listOf(step(cost = cost("0.001", "0.004")), step(cost = null)))

            assertThat(result.estimatedCost).isNull()
        }
    }

    @Test
    fun `the warnings of every step`() {
        val result = RunResult(
            listOf(step(warnings = listOf(ModelWarning("a"))), step(warnings = listOf(ModelWarning("b")))),
        )

        assertThat(result.warnings.map { it.message }).containsExactly("a", "b")
    }

    @Test
    fun `the tool failures of every step`() {
        val failure = ToolFailure("call_1", "getWeather", IllegalStateException("boom"))

        val result = RunResult(listOf(step(failures = listOf(failure)), step()))

        assertThat(result.toolFailures).containsExactly(failure)
    }

    private fun step(
        content: List<Part> = listOf(TextPart("ok")),
        usage: Usage = Usage.Unknown,
        cost: CostEstimate? = null,
        warnings: List<ModelWarning> = emptyList(),
        failures: List<ToolFailure> = emptyList(),
    ) = Step(
        ChatResponse(
            content = content,
            finishReason = if (content.any { it is ToolCallPart }) FinishReasons.ToolCalls else FinishReasons.Stop,
            info = ResponseInfo(model = "m", provider = "p", latency = 1.milliseconds, estimatedCost = cost),
            usage = usage,
            warnings = warnings,
        ),
        toolFailures = failures,
    )

    private fun cost(input: String, output: String) =
        CostEstimate(Money(input), Money(0), Money(0), Money(output), Money(0))

    private val toolCall = ToolCallPart("call_1", "getWeather", Json.obj("city" to "Bariloche"))
}
