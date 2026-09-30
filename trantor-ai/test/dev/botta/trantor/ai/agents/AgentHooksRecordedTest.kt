package dev.botta.trantor.ai.agents

import dev.botta.json.Json
import dev.botta.json.values.JsonObject
import dev.botta.trantor.ai.generation.ToolFailure
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
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A hook of the agent that adds to every call where the user lives, and one of the run that logs the tools, against
 * what the providers really answered. The question does not say where the user lives, so a call to getWeather with
 * Córdoba is what the hook added reaching the model; the second call of each recording was accepted with it.
 */
class AgentHooksRecordedTest {
    @Test
    fun `o4-mini asks for the weather where the hook says the user lives`() {
        http.answers(fixture("openai/hooks-1"), fixture("openai/hooks-2"))

        val result = run(OpenAIChatModel("o4-mini", OpenAIConfig("sk-test"), http))

        assertThat(http.requests[0].body as String).contains("El cliente vive en Córdoba, Argentina.")
        assertThat(log).containsExactly("getWeather {\"city\":\"Córdoba, Argentina\"}", "getWeather answered")
        assertThat(result.text).contains("Córdoba")
    }

    @Test
    fun `so does Claude Sonnet 4-5`() {
        http.answers(fixture("anthropic/hooks-1"), fixture("anthropic/hooks-2"))

        val result = run(AnthropicChatModel("claude-sonnet-4-5", AnthropicConfig(apiKey = "sk-ant-test"), http))

        assertThat(http.requests[0].body as String).contains("El cliente vive en Córdoba, Argentina.")
        assertThat(log).containsExactly("getWeather {\"city\":\"Córdoba, Argentina\"}", "getWeather answered")
        assertThat(result.text).contains("Córdoba")
    }

    private fun run(model: ChatModel): AgentRunResult {
        val support = Agent("support")
            .model(model)
            .instructions("Sos el soporte de una agencia de viajes.")
            .tools(WeatherTool())
            .hooks(WhereTheUserLives())
            .build()

        return runner.run(support, Message.user("Qué temperatura hace donde vivo?")) { hooks(LogTools()) }
    }

    /** The hooks the recordings were made with. */
    class WhereTheUserLives: AgentHooks {
        override fun beforeModel(step: AgentHookContext, request: ChatRequest) = request.copy(
            dynamicSystem = listOfNotNull(request.dynamicSystem, "El cliente vive en Córdoba, Argentina.").joinToString(" "),
        )
    }

    inner class LogTools: AgentHooks {
        override fun beforeTool(call: ToolCallPart, context: AgentToolContext): JsonObject {
            log.add("${call.toolName} ${call.input}")
            return call.input
        }

        override fun afterTool(result: ToolResultPart, failure: ToolFailure?, context: AgentToolContext) {
            log.add("${result.toolName} answered")
        }
    }

    private fun fixture(name: String) =
        javaClass.getResource("/$name.json")?.readText() ?: error("Missing fixture $name")

    private val log = mutableListOf<String>()
    private val http = FakeHttpClient()
    private val runner = AgentRunner(ModelRegistry())

    class WeatherTool: Tool<WeatherTool.Args>() {
        override val name = "getWeather"
        override val description = "The current weather of a city, in celsius"

        override fun execute(args: Args, context: ToolContext) = ToolResult.json(Json.obj("celsius" to 7))

        data class Args(val city: String)
    }
}
