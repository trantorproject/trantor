package dev.botta.trantor.ai.agents

import dev.botta.json.Json
import dev.botta.trantor.ai.models.ModelRegistry
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.ai.providers.RawOptions
import dev.botta.trantor.ai.providers.anthropic.AnthropicChatModel
import dev.botta.trantor.ai.providers.anthropic.AnthropicConfig
import dev.botta.trantor.ai.providers.openai.OpenAIChatModel
import dev.botta.trantor.ai.providers.openai.OpenAIConfig
import dev.botta.trantor.ai.testing.FakeHttpClient
import dev.botta.trantor.ai.tools.Tool
import dev.botta.trantor.ai.tools.ToolContext
import dev.botta.trantor.ai.tools.ToolResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Support handing a question over to sales, against what the providers really answered. Every request after the first
 * of each recording was accepted, so what goes on it is what the api took. The new agent reads the turns of support
 * told as context with its name ([OtherAgentsTurns]), so its request carries neither the calls nor the reasoning of
 * support.
 */
class AgentHandoffRecordedTest {
    @Nested
    inner class `A declared handoff` {
        @Test
        fun `o4-mini takes the request of the new agent, with the turns of the one before told as context`() {
            http.answers(*fixtures("openai/handoff", 4))

            val result = runner.run(support(openAI()).handoffs("sales").build(), question) { team(sales(openAI())) }

            assertThat(result.steps.map { it.agent.name }).containsExactly("support", "support", "sales", "sales")
            assertThat(openAIToolNames(0)).containsExactly("getWeather", "transfer_to_sales")
            assertThat(openAIToolNames(2)).containsExactly("getPrice")
            assertThat(openAISystem(2)).isEqualTo(SALES)
            assertThat(openAIItems(2, "function_call")).isEmpty()
            assertThat(openAIItems(2, "reasoning")).isEmpty()
            assertThat(openAIUserTexts(2)).contains(
                OtherAgentsTurns.PREAMBLE,
                "[support] got from getWeather: {\"celsius\":7}",
                "[support] called transfer_to_sales with {}",
            )
        }

        @Test
        fun `the handoff tool goes with a closed schema without properties, which OpenAI took as strict`() {
            http.answers(*fixtures("openai/handoff", 4))

            runner.run(support(openAI()).handoffs("sales").build(), question) { team(sales(openAI())) }

            val transfer = sent(0)["tools"]!!.asArray()!!.map { it.asObject()!! }.single { it["name"]!!.asString() == "transfer_to_sales" }
            assertThat(transfer["strict"]!!.asBoolean()).isTrue()
            assertThat(transfer["parameters"]).isEqualTo(
                Json.obj("type" to "object", "properties" to Json.obj(), "additionalProperties" to false, "required" to Json.array()),
            )
        }

        @Test
        fun `so does Claude Sonnet 4-5`() {
            http.answers(*fixtures("anthropic/handoff", 4))

            val result = runner.run(support(sonnet()).handoffs("sales").build(), question) { team(sales(sonnet())) }

            assertThat(result.steps.map { it.agent.name }).containsExactly("support", "support", "sales", "sales")
            assertThat(anthropicToolNames(2)).containsExactly("getPrice")
            assertThat(anthropicSystem(2)).isEqualTo(SALES)
            assertThat(toolUsesSent(2)).isEmpty()
            assertThat(thinkingSent(2)).isEmpty()
            assertThat(textsSent(2)).contains(OtherAgentsTurns.PREAMBLE, "[support] called transfer_to_sales with {}")
            assertThat(result.warnings).isEmpty()
        }
    }

    @Nested
    inner class `A tool of the application that hands over` {
        @Test
        fun `on o4-mini the new agent reads what the tool answered`() {
            http.answers(*fixtures("openai/handoff-tool", 4))

            val result = runner.run(support(openAI()).tools(AssignTool()).build(), question) { team(sales(openAI())) }

            assertThat(result.steps.map { it.agent.name }).containsExactly("support", "support", "sales", "sales")
            assertThat(openAIToolNames(2)).containsExactly("getPrice")
            assertThat(openAIUserTexts(2)).contains("[support] got from assignToSales: Asignada a ventas")
        }

        @Test
        fun `and on Claude Sonnet 4-5`() {
            http.answers(*fixtures("anthropic/handoff-tool", 3))

            val result = runner.run(support(sonnet()).tools(AssignTool()).build(), question) { team(sales(sonnet())) }

            assertThat(result.steps.map { it.agent.name }).containsExactly("support", "sales", "sales")
            assertThat(anthropicToolNames(1)).containsExactly("getPrice")
            assertThat(textsSent(1)).contains("[support] got from assignToSales: Asignada a ventas")
        }
    }

    /**
     * Opus 5.5 ties each thinking block to the system prompt, the tools and the messages before it, and the recording
     * forced the api to refuse a block whose prefix changed instead of dropping it. Every request was accepted and no
     * answer came back with input transformations: support keeps its thinking on its second step, and it does not
     * reach sales, whose requests tell the turns of support as context. Sales did not think in this recording; that
     * a model keeps its own thinking when the one before it is left out is in AnthropicChatModelPerModelTest.
     */
    @Nested
    inner class `On a model that ties its thinking` {
        @Test
        fun `the requests of the new agent go without the thinking of the one before`() {
            http.answers(*fixtures("anthropic/bound-handoff", 5))
            val support = support(opus()).handoffs("sales").settings { reasoning = Reasoning.effort(ReasoningEfforts.High) }
            val sales = sales(opus())

            val first = runner.run(support.build(), question) {
                team(sales)
                options(forced)
            }
            val next = runner.run(sales, listOf(question) + first.newMessages + Message.user("Y para dos personas?")) {
                options(forced)
            }

            assertThat(first.steps.map { it.agent.name }).containsExactly("support", "support", "sales", "sales")
            assertThat(thinkingSent(1)).containsExactlyElementsOf(signaturesOf("anthropic/bound-handoff-1"))
            assertThat((2..4).flatMap { thinkingSent(it) }).isEmpty()
            assertThat(textsSent(2)).contains("[support] called transfer_to_sales with {}")
            assertThat(first.warnings + next.warnings).isEmpty()
            assertThat(next.text).contains("1.800")
        }

        private val forced = RawOptions(
            "anthropic",
            Json.obj("thinking" to Json.obj("block_binding" to Json.obj("prefix_mismatch_behavior" to "error"))),
        )
    }

    private fun support(model: ChatModel) = Agent("support")
        .model(model)
        .instructions("Sos el soporte de una agencia de viajes. Para precios o compras, pasá la conversación a ventas.")
        .tools(WeatherTool())
        .settings { reasoning = Reasoning.effort(ReasoningEfforts.Low, ReasoningSummaries.Auto) }

    private fun sales(model: ChatModel) = Agent("sales")
        .model(model)
        .instructions(SALES)
        .tools(PriceTool())
        .settings { reasoning = Reasoning.effort(ReasoningEfforts.Low, ReasoningSummaries.Auto) }
        .build()

    private fun openAI() = OpenAIChatModel("o4-mini", OpenAIConfig("sk-test"), http)
    private fun sonnet() = AnthropicChatModel("claude-sonnet-4-5", AnthropicConfig(apiKey = "sk-ant-test"), http)
    private fun opus() = AnthropicChatModel(
        "claude-opus-5-5",
        AnthropicConfig(apiKey = "sk-ant-test", betas = listOf("thinking-binding-controls-2026-08-01")),
        http,
    )

    private fun sent(call: Int) = Json.parse(http.requests[call].body as String).asObject()!!

    private fun openAIToolNames(call: Int) = sent(call)["tools"]!!.asArray()!!.map { it.asObject()!!["name"]!!.asString() }

    private fun openAIItems(call: Int, type: String) =
        sent(call)["input"]!!.asArray()!!.map { it.asObject()!! }.filter { it["type"]?.asString() == type }

    private fun openAISystem(call: Int) =
        openAIItems(call, "message").first { it["role"]!!.asString() == "system" }["content"]!!.asString()

    private fun openAIUserTexts(call: Int) = openAIItems(call, "message")
        .filter { it["role"]!!.asString() == "user" }
        .flatMap { it["content"]!!.asArray()!!.map { part -> part.asObject()!!["text"]!!.asString() } }

    private fun anthropicToolNames(call: Int) = openAIToolNames(call)

    private fun anthropicSystem(call: Int): String {
        val system = sent(call)["system"]!!

        return system.asString() ?: system.asArray()!!.joinToString("") { it.asObject()!!["text"]!!.asString()!! }
    }

    private fun blocksSent(call: Int, type: String) = sent(call)["messages"]!!.asArray()!!
        .flatMap { it.asObject()!!["content"]?.asArray()?.map { block -> block.asObject()!! } ?: emptyList() }
        .filter { it["type"]!!.asString() == type }

    private fun toolUsesSent(call: Int) = blocksSent(call, "tool_use").map { it["name"]!!.asString() }

    private fun textsSent(call: Int) = blocksSent(call, "text").map { it["text"]!!.asString() }

    private fun thinkingSent(call: Int) = blocksSent(call, "thinking").map { it["signature"]!!.asString() }

    /** The signatures of the thinking blocks a recorded answer came with, in order. */
    private fun signaturesOf(name: String) = (Json.parse(fixture(name)).asObject()!!["content"]!!.asArray()!!)
        .map { it.asObject()!! }
        .filter { it["type"]!!.asString() == "thinking" }
        .map { it["signature"]!!.asString() }

    private fun fixtures(name: String, count: Int) = (1..count).map { fixture("$name-$it") }.toTypedArray()

    private fun fixture(name: String) =
        javaClass.getResource("/$name.json")?.readText() ?: error("Missing fixture $name")

    private val http = FakeHttpClient()
    private val runner = AgentRunner(ModelRegistry())
    private val question = Message.user("Cuánto sale un paquete de una semana a Bariloche? Y qué clima hay?")

    private companion object {
        const val SALES = "Sos ventas de una agencia de viajes. Usá getPrice para los precios."
    }

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

    class AssignTool: Tool<AssignTool.Args>() {
        override val name = "assignToSales"
        override val description = "Assigns the conversation to the sales team, for prices and purchases"

        override fun execute(args: Args, context: ToolContext) = ToolResult.text("Asignada a ventas").handoffTo("sales")

        data class Args(val reason: String)
    }
}
