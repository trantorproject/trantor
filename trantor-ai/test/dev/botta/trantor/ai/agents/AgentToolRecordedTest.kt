package dev.botta.trantor.ai.agents

import dev.botta.json.Json
import dev.botta.trantor.ai.models.ModelRegistry
import dev.botta.trantor.ai.models.chat.ChatModel
import dev.botta.trantor.ai.models.chat.Message
import dev.botta.trantor.ai.providers.anthropic.AnthropicChatModel
import dev.botta.trantor.ai.providers.anthropic.AnthropicConfig
import dev.botta.trantor.ai.providers.openai.OpenAIChatModel
import dev.botta.trantor.ai.providers.openai.OpenAIConfig
import dev.botta.trantor.ai.testing.FakeHttpClient
import dev.botta.trantor.ai.tools.Tool
import dev.botta.trantor.ai.tools.ToolContext
import dev.botta.trantor.ai.tools.ToolResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A writer that uses a researcher as a tool, against what the providers really answered. Both agents call the same
 * model, so the recording has their calls in the order they happened: the writer asks, the researcher runs its steps,
 * and the writer answers.
 */
class AgentToolRecordedTest {
    @Test
    fun `on o4-mini the researcher gets only the task, and the run counts what it spent`() {
        http.answers(*fixtures("openai/agents/agent-tool", 5))

        val result = run(OpenAIChatModel("o4-mini", OpenAIConfig("sk-test"), http))

        val task = "Obtener el clima actual y el precio de un paquete de viaje a Bariloche"
        assertThat(bodyOf(1)).contains(task).doesNotContain(QUESTION)
        assertThat(bodyOf(4)).contains("900 USD")
        assertThat(result.steps[0].step.toolRuns.values.single().steps).hasSize(3)
        // 130 and 431 of the writer, 132, 591 and 651 of the researcher
        assertThat(result.usage.inputTokens).isEqualTo(1935)
        assertThat(result.text).contains("900 USD")
    }

    @Test
    fun `and on Claude Sonnet 4-5`() {
        http.answers(*fixtures("anthropic/agents/agent-tool", 4))

        val result = run(AnthropicChatModel("claude-sonnet-4-5", AnthropicConfig(apiKey = "sk-ant-test"), http))

        assertThat(bodyOf(1)).contains("El clima actual en Bariloche").doesNotContain(QUESTION)
        assertThat(bodyOf(3)).contains("USD 900")
        assertThat(result.steps[0].step.toolRuns.values.single().steps).hasSize(2)
        // 667 and 828 of the writer, 743 and 912 of the researcher
        assertThat(result.usage.inputTokens).isEqualTo(3150)
        assertThat(result.text).contains("USD 900")
    }

    private fun run(model: ChatModel): AgentRunResult {
        val researcher = Agent("researcher")
            .model(model)
            .instructions(
                "Sos investigador de una agencia de viajes. Usá getWeather y getPrice. Respondé solo con los datos.",
            )
            .tools(WeatherTool(), PriceTool())
            .build()
        val writer = Agent("writer")
            .model(model)
            .instructions(
                "Sos redactor de una agencia de viajes. Escribís notas de tres oraciones para el newsletter. " +
                    "Los datos de clima y precios se los pedís al investigador; no inventes ninguno.",
            )
            .tools(researcher.asTool(agents, "Averigua el clima actual y el precio del paquete de un destino"))
            .build()

        return agents.run(writer, Message.user(QUESTION))
    }

    private fun bodyOf(call: Int) = http.requests[call].body as String

    private fun fixtures(name: String, count: Int) = (1..count).map { fixture("$name-$it") }.toTypedArray()

    private fun fixture(name: String) =
        javaClass.getResource("/$name.json")?.readText() ?: error("Missing fixture $name")

    private val http = FakeHttpClient()
    private val agents = AgentRunner(ModelRegistry())

    /** The tools the recordings were made with, answering what they answered then. */
    class WeatherTool: Tool<WeatherTool.Args>() {
        override val name = "getWeather"
        override val description = "The current weather of a city, in celsius"

        override fun execute(args: Args, context: ToolContext) = ToolResult.json(Json.obj("celsius" to 7))

        data class Args(val city: String)
    }

    class PriceTool: Tool<PriceTool.Args>() {
        override val name = "getPrice"
        override val description = "The price of a one-week package to a city, per person, in dollars"

        override fun execute(args: Args, context: ToolContext) = ToolResult.json(Json.obj("dollars" to 900))

        data class Args(val city: String)
    }

    private companion object {
        const val QUESTION = "Escribí la nota de esta semana sobre Bariloche."
    }
}
