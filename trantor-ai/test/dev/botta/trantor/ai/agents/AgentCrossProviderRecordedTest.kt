package dev.botta.trantor.ai.agents

import dev.botta.json.Json
import dev.botta.trantor.ai.agents.AgentHandoffRecordedTest.PriceTool
import dev.botta.trantor.ai.agents.AgentHandoffRecordedTest.WeatherTool
import dev.botta.trantor.ai.history.InMemorySession
import dev.botta.trantor.ai.models.ModelRegistry
import dev.botta.trantor.ai.models.catalog.ModelCatalog
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.ai.models.middleware.CostMiddleware
import dev.botta.trantor.ai.models.middleware.with
import dev.botta.trantor.ai.providers.anthropic.AnthropicChatModel
import dev.botta.trantor.ai.providers.anthropic.AnthropicConfig
import dev.botta.trantor.ai.providers.anthropic.addAnthropicModels
import dev.botta.trantor.ai.providers.openai.OpenAIChatModel
import dev.botta.trantor.ai.providers.openai.OpenAIConfig
import dev.botta.trantor.ai.providers.openai.addOpenAIModels
import dev.botta.trantor.ai.testing.FakeHttpClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Support on one provider handing the conversation over to sales on the other, and a second turn through a session,
 * which starts with sales, against what the providers really answered. Every request after the first of each
 * recording was accepted. The turns of support reach sales told as context ([OtherAgentsTurns]), so no id, reasoning
 * nor signature of the other provider travels, and there is nothing foreign for the adapters to drop.
 */
class AgentCrossProviderRecordedTest {
    @Test
    fun `support on o4-mini hands over to sales on Claude Sonnet 4-5`() {
        http.answers(*fixtures("openai/cross-handoff", 2), *fixtures("anthropic/cross-handoff", 3))

        val (first, next) = twoTurns(support = openAI(), sales = sonnet())

        assertThat(first.steps.map { it.agent.name }).containsExactly("support", "support", "sales", "sales")
        assertThat(bodyOf(2)).doesNotContain(idsOf("openai/cross-handoff", 2))
        assertThat(anthropicTexts(2)).contains(OtherAgentsTurns.PREAMBLE, WEATHER_TOLD)
        assertThat(anthropicToolUses(4)).containsExactly("getPrice")
        assertThat(anthropicTexts(4)).contains(WEATHER_TOLD)
        assertThat(first.warnings + next.warnings).isEmpty()
        // 112 and 338 of support, 754 and 1053 of sales
        assertThat(first.usage.inputTokens).isEqualTo(2257)
        assertThat(first.estimatedCost).isNotNull()
        assertThat(next.text).contains("1,800")
    }

    @Test
    fun `and support on Claude Sonnet 4-5 hands over to sales on o4-mini`() {
        http.answers(*fixtures("anthropic/cross-handoff-back", 2), *fixtures("openai/cross-handoff-back", 3))

        val (first, next) = twoTurns(support = sonnet(), sales = openAI())

        assertThat(first.steps.map { it.agent.name }).containsExactly("support", "support", "sales", "sales")
        assertThat(bodyOf(2)).doesNotContain(idsOf("anthropic/cross-handoff-back", 2))
        assertThat(openAIUserTexts(2)).contains(OtherAgentsTurns.PREAMBLE, WEATHER_TOLD)
        assertThat(openAIItems(4, "function_call").map { it["name"]!!.asString() }).containsExactly("getPrice")
        assertThat(openAIUserTexts(4)).contains(WEATHER_TOLD)
        assertThat(first.warnings + next.warnings).isEmpty()
        // 718 and 1013 of support, 273 and 505 of sales
        assertThat(first.usage.inputTokens).isEqualTo(2509)
        assertThat(first.estimatedCost).isNotNull()
        assertThat(next.text).contains("1.800")
    }

    /** The recorded conversation: the question, the handoff, and a second turn with whoever ended the first. */
    private fun twoTurns(support: ChatModel, sales: ChatModel): Pair<AgentRunResult, AgentRunResult> {
        val session = InMemorySession()
        val salesAgent = Agent("sales")
            .model(sales)
            .instructions("Sos ventas de una agencia de viajes. Usá getPrice para los precios.")
            .tools(PriceTool())
            .settings { reasoning = Reasoning.effort(ReasoningEfforts.Low, ReasoningSummaries.Auto) }
            .build()
        val supportAgent = Agent("support")
            .model(support)
            .instructions(
                "Sos el soporte de una agencia de viajes. Para precios o compras, pasá la conversación a ventas.",
            )
            .tools(WeatherTool())
            .settings { reasoning = Reasoning.effort(ReasoningEfforts.Low, ReasoningSummaries.Auto) }
            .handoffs("sales")
            .build()

        val first = runner.run(supportAgent, QUESTION) {
            team(salesAgent)
            session(session)
        }
        val next = runner.run(first.lastAgent, Message.user("Y para dos personas?")) { session(session) }

        return first to next
    }

    private fun openAI() = OpenAIChatModel("o4-mini", OpenAIConfig("sk-test"), http).with(CostMiddleware(catalog))

    private fun sonnet() = AnthropicChatModel("claude-sonnet-4-5", AnthropicConfig(apiKey = "sk-ant-test"), http)
        .with(CostMiddleware(catalog))

    private fun bodyOf(call: Int) = http.requests[call].body as String

    private fun sent(call: Int) = Json.parse(bodyOf(call)).asObject()!!

    private fun openAIItems(call: Int, type: String) =
        sent(call)["input"]!!.asArray()!!.map { it.asObject()!! }.filter { it["type"]?.asString() == type }

    private fun openAIUserTexts(call: Int) = openAIItems(call, "message")
        .filter { it["role"]!!.asString() == "user" }
        .flatMap { it["content"]!!.asArray()!!.map { part -> part.asObject()!!["text"]!!.asString() } }

    private fun anthropicBlocks(call: Int, type: String) = sent(call)["messages"]!!.asArray()!!
        .flatMap { it.asObject()!!["content"]?.asArray()?.map { block -> block.asObject()!! } ?: emptyList() }
        .filter { it["type"]!!.asString() == type }

    private fun anthropicToolUses(call: Int) = anthropicBlocks(call, "tool_use").map { it["name"]!!.asString() }

    private fun anthropicTexts(call: Int) = anthropicBlocks(call, "text").map { it["text"]!!.asString() }

    /**
     * What identifies the answers of a recording to its own provider: the ids of its items and calls, and the
     * signatures of its thinking.
     */
    private fun idsOf(name: String, count: Int) = (1..count).flatMap { call ->
        val answer = Json.parse(fixture("$name-$call")).asObject()!!
        val items = (answer["output"] ?: answer["content"])!!.asArray()!!.map { it.asObject()!! }

        items.flatMap { item -> listOf("id", "call_id", "signature").mapNotNull { item[it]?.asString() } }
    }

    private fun fixtures(name: String, count: Int) = (1..count).map { fixture("$name-$it") }.toTypedArray()

    private fun fixture(name: String) =
        javaClass.getResource("/$name.json")?.readText() ?: error("Missing fixture $name")

    private val http = FakeHttpClient()
    private val runner = AgentRunner(ModelRegistry())
    private val catalog = ModelCatalog().addOpenAIModels().addAnthropicModels()

    private companion object {
        const val WEATHER_TOLD = "[support] got from getWeather: {\"celsius\":7}"
        val QUESTION = Message.user("Cuánto sale un paquete de una semana a Bariloche? Y qué clima hay?")
    }
}
