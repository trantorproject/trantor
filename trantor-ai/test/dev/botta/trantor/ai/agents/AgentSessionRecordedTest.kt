package dev.botta.trantor.ai.agents

import dev.botta.json.Json
import dev.botta.json.values.JsonObject
import dev.botta.trantor.ai.history.InMemorySession
import dev.botta.trantor.ai.history.LastMessages
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
 * A conversation of four turns kept in a session, against what the providers really answered. A LastMessages(10,
 * step = 4) cuts on the second step of the third turn, taking the first turn out, and the fourth turn sends from the
 * same place. Each turn is the question, the call to getWeather, its result and the answer, but the last one, which
 * answers without tools.
 */
class AgentSessionRecordedTest {
    @Test
    fun `on o4-mini the session keeps every turn, and the calls send what the policy leaves`() {
        http.answers(*fixtures("openai/session"))

        converse(OpenAIChatModel("o4-mini", OpenAIConfig("sk-test"), http))

        assertThat(sent).containsExactly(2, 4, 6, 8, 10, 8, 10)
        assertThat(kept).containsExactly(4, 8, 12, 14)
    }

    @Test
    fun `on Claude Sonnet 4-5 too, and the cache comes back on the turn after the cut`() {
        http.answers(*fixtures("anthropic/session"))

        val turns = converse(AnthropicChatModel("claude-sonnet-4-5", AnthropicConfig(apiKey = "sk-ant-test"), http))

        assertThat(sent).containsExactly(2, 4, 6, 8, 10, 8, 10)
        assertThat(kept).containsExactly(4, 8, 12, 14)
        // The cut changes what goes first: the call after it reads the system prompt alone, the next turn all of it
        val afterTheCut = turns[2].steps[1].step.response.usage.cacheReadTokens!!
        val nextTurn = turns[3].steps[0].step.response.usage.cacheReadTokens!!
        assertThat(afterTheCut).isLessThan(nextTurn)
    }

    /**
     * Opus 5.5 ties each thinking block to what came before it, and the recording forced the api to refuse a block
     * whose prefix changed. The thinking of the second turn is sent until the cut, and left out from it on, with a
     * warning; the thinking produced after the cut goes back on the fourth turn. Every call was accepted, and no
     * answer came back with input transformations.
     */
    @Test
    fun `on Opus 5-5 the thinking from before the cut is left out, and the one produced after it goes back`() {
        val answers = fixtures("anthropic/bound-session")
        http.answers(*answers)
        val model = AnthropicChatModel("claude-opus-5-5", AnthropicConfig(apiKey = "sk-ant-test"), http)

        val turns = converse(model)

        val secondTurn = signatureOf(answers[2])
        val afterTheCut = signatureOf(answers[5])
        assertThat(thinkingSentBy(4)).containsExactly(secondTurn)
        assertThat(thinkingSentBy(5)).isEmpty()
        assertThat(thinkingSentBy(6)).containsExactly(afterTheCut)
        assertThat(turns.map { turn -> turn.warnings.size }).containsExactly(0, 0, 1, 1)
    }

    private fun converse(model: ChatModel): List<AgentRunResult> {
        val session = InMemorySession()
        val support = Agent("support")
            .model(model)
            .instructions("Sos el soporte de una agencia de viajes. Usá getWeather para el clima. Respondé corto.")
            .tools(WeatherTool())
            .hooks(CountSent())
            .build()

        return questions.map { question ->
            agents.run(support, Message.user(question)) {
                session(session)
                contextPolicy(LastMessages(10, step = 4))
            }.also { kept.add(session.load().size) }
        }
    }

    /** The signatures of the thinking blocks the request of call [index] carried, from zero. */
    private fun thinkingSentBy(index: Int) = Json.parse(http.requests[index].body as String).asObject()!!
        .getValue("messages").asArray()!!
        .flatMap { it.asObject()!!["content"]!!.asArray()!! }
        .map { it.asObject()!! }
        .filter { it["type"]?.asString() == "thinking" }
        .map { it["signature"]!!.asString() }

    private fun signatureOf(answer: String): String {
        val thinking = Json.parse(answer).asObject()!!.getValue("content").asArray()!!.first() as JsonObject

        return thinking.getValue("signature").asString()!!
    }

    private fun fixtures(name: String) = (1..7).map { fixture("$name-$it") }.toTypedArray()

    private fun fixture(name: String) =
        javaClass.getResource("/$name.json")?.readText() ?: error("Missing fixture $name")

    /** How many messages each call sends, the instructions of the agent included. */
    inner class CountSent: AgentHooks {
        override fun beforeModel(step: AgentHookContext, request: ChatRequest) =
            request.also { sent.add(it.messages.size) }
    }

    private val sent = mutableListOf<Int>()
    private val kept = mutableListOf<Int>()
    private val http = FakeHttpClient()
    private val agents = AgentRunner(ModelRegistry())
    private val questions = listOf(
        "Qué clima hay en Bariloche?",
        "Y en Córdoba?",
        "Y en Mendoza?",
        "Odio el frío. Entre Córdoba y Mendoza, a cuál me conviene ir? Pensalo bien y explicame por qué.",
    )

    /** The tool the recordings were made with, answering what it answered then. */
    class WeatherTool: Tool<WeatherTool.Args>(Args.serializer()) {
        override val name = "getWeather"
        override val description = "The current weather of a city, in celsius"

        override fun execute(args: Args, context: ToolContext) = ToolResult.json(Json.obj("celsius" to 7))

        @Serializable
        data class Args(val city: String)
    }
}
