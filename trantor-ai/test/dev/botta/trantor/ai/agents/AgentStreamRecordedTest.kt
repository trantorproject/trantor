package dev.botta.trantor.ai.agents

import dev.botta.json.Json
import dev.botta.trantor.ai.generation.RunEvent
import dev.botta.trantor.ai.models.ModelRegistry
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.ai.providers.anthropic.AnthropicChatModel
import dev.botta.trantor.ai.providers.anthropic.AnthropicConfig
import dev.botta.trantor.ai.providers.openai.OpenAIChatModel
import dev.botta.trantor.ai.providers.openai.OpenAIConfig
import dev.botta.trantor.ai.testing.FakeHttpClient
import dev.botta.trantor.ai.tools.Tool
import dev.botta.trantor.ai.tools.ToolContext
import dev.botta.trantor.ai.tools.ToolResult
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Support handing the question over to sales while the run is received, against the streams the providers really
 * sent. In both recordings support only called tools, so all the text is sales', and it comes after the handoff.
 */
class AgentStreamRecordedTest {
    @Test
    fun `on o4-mini the handoff comes between the steps of support and those of sales`() {
        http.answers(*fixtures("openai/agent-stream", 4))

        val (events, result) = stream(OpenAIChatModel("o4-mini", OpenAIConfig("sk-test"), http))

        assertThat(events.filter(::marksSteps)).containsExactly(
            RunEvent.StepStarted(1), RunEvent.StepFinished(1),
            RunEvent.StepStarted(2), RunEvent.StepFinished(2),
            RunEvent.Handoff("support", "sales"),
            RunEvent.StepStarted(3), RunEvent.StepFinished(3),
            RunEvent.StepStarted(4), RunEvent.StepFinished(4),
        )
        assertThat(textAfterTheHandoff(events)).isEqualTo(result.text)
        assertThat(result.steps.map { it.agent.name }).containsExactly("support", "support", "sales", "sales")
    }

    @Test
    fun `and on Claude Sonnet 4-5`() {
        http.answers(*fixtures("anthropic/agent-stream", 3))

        val (events, result) = stream(AnthropicChatModel("claude-sonnet-4-5", AnthropicConfig(apiKey = "sk-ant-test"), http))

        assertThat(events.filter(::marksSteps)).containsExactly(
            RunEvent.StepStarted(1), RunEvent.StepFinished(1),
            RunEvent.Handoff("support", "sales"),
            RunEvent.StepStarted(2), RunEvent.StepFinished(2),
            RunEvent.StepStarted(3), RunEvent.StepFinished(3),
        )
        assertThat(textAfterTheHandoff(events)).startsWith("¡Hola! Te ayudo").endsWith(result.text)
        assertThat(result.lastAgent.name).isEqualTo("sales")
    }

    private fun stream(model: ChatModel): Pair<List<RunEvent>, AgentRunResult> {
        val support = Agent("support").model(model)
            .instructions("Sos el soporte de una agencia de viajes. Para precios o compras, pasá la conversación a ventas.")
            .tools(WeatherTool()).handoffs("sales").build()
        val sales = Agent("sales").model(model)
            .instructions("Sos ventas de una agencia de viajes. Usá getPrice para los precios.")
            .tools(PriceTool()).build()

        return runner.stream(support, question) { team(sales) }.use { stream ->
            stream.asSequence().toList() to stream.result()
        }
    }

    private fun marksSteps(event: RunEvent) =
        event is RunEvent.StepStarted || event is RunEvent.StepFinished || event is RunEvent.Handoff

    private fun textAfterTheHandoff(events: List<RunEvent>): String {
        val handoff = events.indexOfFirst { it is RunEvent.Handoff }

        assertThat(textOf(events.take(handoff))).isEmpty()

        return textOf(events.drop(handoff))
    }

    private fun textOf(events: List<RunEvent>) = events.filterIsInstance<RunEvent.Model>()
        .mapNotNull { (it.part as? StreamPart.TextDelta)?.text }
        .joinToString("")

    private fun fixtures(name: String, count: Int) = (1..count).map { fixture("$name-$it") }.toTypedArray()

    private fun fixture(name: String) =
        javaClass.getResource("/$name.txt")?.readText() ?: error("Missing fixture $name")

    private val http = FakeHttpClient()
    private val runner = AgentRunner(ModelRegistry())
    private val question = Message.user("Cuánto sale un paquete de una semana a Bariloche? Y qué clima hay?")

    /** The tools the recordings were made with, answering what they answered then. */
    class WeatherTool: Tool<WeatherTool.Args>(Args.serializer()) {
        override val name = "getWeather"
        override val description = "The current weather of a city, in celsius"

        override fun execute(args: Args, context: ToolContext) = ToolResult.json(Json.obj("celsius" to 7))

        @Serializable
        data class Args(val city: String)
    }

    class PriceTool: Tool<PriceTool.Args>(Args.serializer()) {
        override val name = "getPrice"
        override val description = "The price of a one-week package to a city, per person, in dollars"

        override fun execute(args: Args, context: ToolContext) = ToolResult.json(Json.obj("dollars" to 900))

        @Serializable
        data class Args(val city: String)
    }
}
