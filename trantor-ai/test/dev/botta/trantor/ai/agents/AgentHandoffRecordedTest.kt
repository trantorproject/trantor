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
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Support handing a question over to sales, against what the providers really answered. Every request after the first
 * of each recording was accepted, so what goes on it is what the api took: the new agent's request carries the calls
 * and the reasoning of the agent before, to tools it does not have, and both providers took it.
 *
 * The recordings also show what the handoff does not solve yet: the new agent reads the turns of the one before as
 * its own (o4-mini's sales ends the run saying it will pass the question to sales). That is step 4b of the plan.
 */
class AgentHandoffRecordedTest {
    @Nested
    inner class `A declared handoff` {
        @Test
        fun `o4-mini takes the request of the new agent, with the calls and the reasoning of the one before`() {
            http.answers(*fixtures("openai/handoff", 3))

            val result = runner.run(support(openAI()).handoffs("sales").build(), question) { team(sales(openAI())) }

            assertThat(result.steps.map { it.agent.name }).containsExactly("support", "support", "sales")
            assertThat(openAIToolNames(0)).containsExactly("getWeather", "transfer_to_sales")
            assertThat(openAIToolNames(2)).containsExactly("getPrice")
            assertThat(openAISystem(2)).isEqualTo(SALES)
            assertThat(openAIItems(2, "function_call").map { it["name"]!!.asString() })
                .containsExactly("getWeather", "transfer_to_sales")
            assertThat(openAIItems(2, "reasoning")).hasSize(2)
        }

        @Test
        fun `the handoff tool goes with a closed schema without properties, which OpenAI took as strict`() {
            http.answers(*fixtures("openai/handoff", 3))

            runner.run(support(openAI()).handoffs("sales").build(), question) { team(sales(openAI())) }

            val transfer = sent(0)["tools"]!!.asArray()!!.map { it.asObject()!! }.single { it["name"]!!.asString() == "transfer_to_sales" }
            assertThat(transfer["strict"]!!.asBoolean()).isTrue()
            assertThat(transfer["parameters"]).isEqualTo(
                Json.obj("type" to "object", "properties" to Json.obj(), "additionalProperties" to false, "required" to Json.array()),
            )
        }

        @Test
        fun `Claude Sonnet 4-5 takes it too, with the thinking of the one before, which it does not tie`() {
            http.answers(*fixtures("anthropic/handoff", 3))

            val result = runner.run(support(sonnet()).handoffs("sales").build(), question) { team(sales(sonnet())) }

            assertThat(result.steps.map { it.agent.name }).containsExactly("support", "sales", "sales")
            assertThat(anthropicToolNames(1)).containsExactly("getPrice")
            assertThat(anthropicSystem(1)).isEqualTo(SALES)
            assertThat(toolUsesSent(1)).containsExactly("getWeather", "transfer_to_sales")
            assertThat(thinkingSent(1)).containsExactlyElementsOf(signaturesOf("anthropic/handoff-1"))
            assertThat(result.warnings).isEmpty()
        }
    }

    @Nested
    inner class `A tool of the application that hands over` {
        @Test
        fun `on o4-mini the new agent reads what the tool answered`() {
            http.answers(*fixtures("openai/handoff-tool", 3))

            val result = runner.run(support(openAI()).tools(AssignTool()).build(), question) { team(sales(openAI())) }

            assertThat(result.steps.map { it.agent.name }).containsExactly("support", "sales", "sales")
            assertThat(openAIToolNames(1)).containsExactly("getPrice")
            assertThat(openAIItems(1, "function_call_output").single()["output"]!!.asString()).isEqualTo("Asignada a ventas")
        }

        @Test
        fun `and on Claude Sonnet 4-5`() {
            http.answers(*fixtures("anthropic/handoff-tool", 3))

            val result = runner.run(support(sonnet()).tools(AssignTool()).build(), question) { team(sales(sonnet())) }

            assertThat(result.steps.map { it.agent.name }).containsExactly("support", "sales", "sales")
            assertThat(anthropicToolNames(1)).containsExactly("getPrice")
            assertThat(toolResultsSent(1)).contains("Asignada a ventas")
        }
    }

    /**
     * Opus 5.5 ties each thinking block to the system prompt and the tools it was produced under, and the recording
     * forced the api to refuse a block whose prefix changed instead of dropping it. Every request was accepted and no
     * answer came back with input transformations: the thinking of support was left out of the requests of sales,
     * and the thinking of sales stayed, on its next step and on the next turn.
     */
    @Nested
    inner class `On a model that ties its thinking` {
        @Test
        fun `the requests of the new agent leave out the thinking of the one before, and keep its own`() {
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
            assertThat(thinkingSent(2)).isEmpty()
            assertThat(thinkingSent(3)).containsExactlyElementsOf(signaturesOf("anthropic/bound-handoff-3"))
            assertThat(thinkingSent(4)).containsExactlyElementsOf(signaturesOf("anthropic/bound-handoff-3"))
            assertThat(first.warnings.map { it.message }).anyMatch { it.startsWith(LEFT_OUT) }
            assertThat(next.warnings.map { it.message }).anyMatch { it.startsWith(LEFT_OUT) }
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

    private fun anthropicToolNames(call: Int) = openAIToolNames(call)

    private fun anthropicSystem(call: Int): String {
        val system = sent(call)["system"]!!

        return system.asString() ?: system.asArray()!!.joinToString("") { it.asObject()!!["text"]!!.asString()!! }
    }

    private fun blocksSent(call: Int, type: String) = sent(call)["messages"]!!.asArray()!!
        .flatMap { it.asObject()!!["content"]?.asArray()?.map { block -> block.asObject()!! } ?: emptyList() }
        .filter { it["type"]!!.asString() == type }

    private fun toolUsesSent(call: Int) = blocksSent(call, "tool_use").map { it["name"]!!.asString() }

    private fun toolResultsSent(call: Int) = blocksSent(call, "tool_result").map { it["content"]!!.asString() }

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
        const val LEFT_OUT = "Thinking produced under another system prompt or other tools was left out"
    }

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

    class AssignTool: Tool<AssignTool.Args>(Args.serializer()) {
        override val name = "assignToSales"
        override val description = "Assigns the conversation to the sales team, for prices and purchases"

        override fun execute(args: Args, context: ToolContext) = ToolResult.text("Asignada a ventas").handoffTo("sales")

        @Serializable
        data class Args(val reason: String)
    }
}
