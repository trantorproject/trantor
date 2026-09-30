package dev.botta.trantor.ai.agents

import dev.botta.json.Json
import dev.botta.trantor.ai.models.ModelRegistry
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.ai.providers.anthropic.AnthropicChatModel
import dev.botta.trantor.ai.providers.anthropic.AnthropicConfig
import dev.botta.trantor.ai.providers.openai.OpenAIChatModel
import dev.botta.trantor.ai.providers.openai.OpenAIConfig
import dev.botta.trantor.ai.testing.FakeHttpClient
import dev.botta.trantor.ai.tools.Tool
import dev.botta.trantor.ai.tools.ToolContext
import dev.botta.trantor.ai.tools.ToolOutput
import dev.botta.trantor.ai.tools.ToolResult
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * An agent answering an object through the output tool, against what the providers really answered: o4-mini and
 * Claude Sonnet 4.5 both asked for the weather first and then called final_result with the object, without needing
 * the reminder. The second call of each recording was accepted, so what goes on it is what the api took.
 */
class AgentRunnerRecordedTest {
    @Test
    fun `o4-mini answers the object by calling the output tool`() {
        http.answers(fixture("openai/agent-output-tool-1"), fixture("openai/agent-output-tool-2"))

        val result = runner.run(agent(OpenAIChatModel("o4-mini", OpenAIConfig("sk-test"), http)), question)

        assertThat(result.output<CityWeather>()).isEqualTo(CityWeather("Bariloche", 7))
        assertAnsweredByTheOutputTool(result)
        assertThat(toolNames(1)).containsExactly("getWeather", "final_result")
    }

    @Test
    fun `so does Claude Sonnet 4-5`() {
        http.answers(fixture("anthropic/agent-output-tool-1"), fixture("anthropic/agent-output-tool-2"))

        val result = runner.run(
            agent(AnthropicChatModel("claude-sonnet-4-5", AnthropicConfig(apiKey = "sk-ant-test"), http)),
            question,
        )

        assertThat(result.output<CityWeather>()).isEqualTo(CityWeather("Bariloche, Argentina", 7))
        assertAnsweredByTheOutputTool(result)
        assertThat(toolNames(1)).containsExactly("getWeather", "final_result")
    }

    private fun assertAnsweredByTheOutputTool(result: AgentRunResult) {
        assertThat(http.requests).hasSize(2)
        assertThat(result.steps.map { it.step.reminder }).containsOnlyNulls()
        assertThat((result.newMessages.last() as Message.Tool).results.single().output)
            .isEqualTo(ToolOutput.Text("Final result processed."))
    }

    private fun toolNames(call: Int) =
        sent(call)["tools"]!!.asArray()!!.map { it.asObject()!!["name"]!!.asString() }

    private fun sent(call: Int) = Json.parse(http.requests[call].body as String).asObject()!!

    private fun agent(model: ChatModel) = Agent("support")
        .model(model)
        .instructions("Sos el asistente de una agencia de viajes. Usá las tools para lo que no sepas.")
        .dynamicInstructions("Son las 10:00")
        .tools(WeatherTool())
        .settings { reasoning = Reasoning.effort(ReasoningEfforts.Low, ReasoningSummaries.Auto) }
        .output<CityWeather>(OutputMode.Tool)
        .build()

    private fun fixture(name: String) =
        javaClass.getResource("/$name.json")?.readText() ?: error("Missing fixture $name")

    private val http = FakeHttpClient()
    private val runner = AgentRunner(ModelRegistry())
    private val question = Message.user("Que temperatura hay en Bariloche?")

    @Serializable
    data class CityWeather(val city: String, val celsius: Int)

    /** The tool the recordings were made with, answering what it answered then. */
    class WeatherTool: Tool<WeatherTool.Args>() {
        override val name = "getWeather"
        override val description = "The current weather of a city, in celsius"

        override fun execute(args: Args, context: ToolContext) = ToolResult.json(Json.obj("celsius" to 7))

        @Serializable
        data class Args(val city: String)
    }
}
