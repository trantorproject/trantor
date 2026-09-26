@file:Suppress("ClassName")

package dev.botta.trantor.ai.telemetry

import dev.botta.json.Json
import dev.botta.json.values.JsonObject
import dev.botta.trantor.ai.agents.Agent
import dev.botta.trantor.ai.agents.AgentHooks
import dev.botta.trantor.ai.agents.AgentRunner
import dev.botta.trantor.ai.agents.AgentToolContext
import dev.botta.trantor.ai.generation.ToolLoop
import dev.botta.trantor.ai.models.ModelRegistry
import dev.botta.trantor.ai.models.ResponseInfo
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.ai.testing.FakeChatModel
import dev.botta.trantor.ai.testing.TestTelemetry
import dev.botta.trantor.ai.tools.ProviderToolSpec
import dev.botta.trantor.ai.tools.Tool
import dev.botta.trantor.ai.tools.ToolContext
import dev.botta.trantor.ai.tools.ToolResult
import io.opentelemetry.api.common.AttributeKey.stringKey
import io.opentelemetry.sdk.trace.data.SpanData
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

class ContentCaptureTest {
    @Test
    fun `is off unless the application turns it on`() {
        model.answers(listOf(weatherCall()), listOf(TextPart("Hacen 7 grados")))

        ToolLoop(model, listOf(weather), openTelemetry = telemetry.openTelemetry).run(ChatRequest("Hola"))

        val keys = telemetry.spans.flatMap { span -> span.attributes.asMap().keys.map { it.key } }
        assertThat(keys).doesNotContain(
            SYSTEM.key, INPUT.key, OUTPUT.key, DEFINITIONS.key, ARGUMENTS.key, RESULT.key,
        )
    }

    @Nested
    inner class `a chat span` {
        @Test
        fun `has the instructions of the agent, and its dynamic ones last`() {
            val agent = Agent("support").model(model).instructions("Sos soporte").dynamicInstructions("Hoy es lunes")

            runner().run(agent.build(), Message.user("Hola"))

            assertThat(chat().attributes[SYSTEM]).isEqualTo(
                """[{"type":"text","content":"Sos soporte"},{"type":"text","content":"Hoy es lunes"}]""",
            )
        }

        @Test
        fun `has the conversation as it went to the model, with the name of the agent on its own turns`() {
            model.answers(listOf(TextPart("Busco"), weatherCall()), listOf(TextPart("Hacen 7 grados")))
            val agent = Agent("support").model(model).instructions("Sos soporte").tools(weather)

            runner().run(agent.build(), Message.user("Que clima hay?"))

            assertThat(chats().last().attributes[INPUT]).isEqualTo(
                """[{"role":"user","parts":[{"type":"text","content":"Que clima hay?"}]},""" +
                    """{"role":"assistant","parts":[{"type":"text","content":"Busco"},""" +
                    """{"type":"tool_call","id":"call_1","name":"getWeather","arguments":{"city":"Bariloche"}}],""" +
                    """"name":"support"},""" +
                    """{"role":"tool","parts":[""" +
                    """{"type":"tool_call_response","id":"call_1","response":"7 grados en Bariloche"}]}]""",
            )
        }

        @Test
        fun `has a summary of the conversation as the compaction the conventions have a part for`() {
            loop().run(ChatRequest(Message.Summary("Nico viaja en julio"), Message.user("Cuando viajo?")))

            assertThat(chat().attributes[INPUT]).isEqualTo(
                """[{"role":"assistant","parts":[{"type":"compaction","content":"Nico viaja en julio"}]},""" +
                    """{"role":"user","parts":[{"type":"text","content":"Cuando viajo?"}]}]""",
            )
        }

        @Test
        fun `has what the model answered, with the reasoning it let read and not the one it signed`() {
            model.answers(
                listOf(
                    ReasoningPart(text = "Pienso"),
                    ReasoningPart(opaque = Json.obj("signature" to "abc")),
                    TextPart("Busco"),
                    weatherCall(),
                ),
                listOf(TextPart("Hacen 7 grados")),
            )

            loop().run(ChatRequest("Que clima hay?"))

            assertThat(chats().first().attributes[OUTPUT]).isEqualTo(
                """[{"role":"assistant","parts":[{"type":"reasoning","content":"Pienso"},""" +
                    """{"type":"text","content":"Busco"},""" +
                    """{"type":"tool_call","id":"call_1","name":"getWeather","arguments":{"city":"Bariloche"}}]}]""",
            )
        }

        @Test
        fun `keeps what the conventions have no part for with a type of its own, and never what is raw`() {
            model.answers(
                listOf(
                    ToolCallPart("ws_1", "web_search", Json.obj("query" to "clima"), providerExecuted = true),
                    ProviderPart("openai", "web_search_call", Json.obj("secret" to "raw")),
                    RefusalPart("No puedo"),
                ),
            )

            loop().run(ChatRequest("Hola"))

            assertThat(chat().attributes[OUTPUT]).isEqualTo(
                """[{"role":"assistant","parts":[""" +
                    """{"type":"server_tool_call","id":"ws_1","name":"web_search",""" +
                    """"server_tool_call":{"type":"web_search","arguments":{"query":"clima"}}},""" +
                    """{"type":"web_search_call"},""" +
                    """{"type":"refusal","content":"No puedo"}]}]""",
            )
        }

        @Test
        fun `has the tools the model could call, without their parameters`() {
            val webSearch = ProviderToolSpec("openai.web_search", Json.obj())

            loop().run(ChatRequest(listOf(Message.user("Hola")), tools = listOf(webSearch)))

            assertThat(chat().attributes[DEFINITIONS]).isEqualTo(
                """[{"type":"openai.web_search","name":"openai.web_search"},""" +
                    """{"type":"function","name":"getWeather","description":"The weather of a city"}]""",
            )
        }

        @Test
        fun `in a stream has what the model answered once it is over`() {
            model.streams(listOf(StreamPart.TextDelta("Ho"), StreamPart.TextDelta("la")))
            model.answers(listOf(TextPart("Hola")))

            loop().stream(ChatRequest("Hola")).use { it.result() }

            assertThat(chat().attributes[OUTPUT])
                .isEqualTo("""[{"role":"assistant","parts":[{"type":"text","content":"Hola"}]}]""")
        }

        @Test
        fun `cuts every text longer than the settings allow, and the JSON stays whole`() {
            model.responses.add(
                ChatResponse(
                    listOf(TextPart("Hola, como estas?")),
                    FinishReasons.Stop,
                    ResponseInfo(model = "fake-model", provider = "fake", latency = 1.milliseconds),
                ),
            )

            loop(AITelemetrySettings(captureContent = true, maxContentLength = 4)).run(ChatRequest("Buen dia"))

            assertThat(chat().attributes[INPUT])
                .isEqualTo("""[{"role":"user","parts":[{"type":"text","content":"Buen…"}]}]""")
            assertThat(chat().attributes[OUTPUT])
                .isEqualTo("""[{"role":"assistant","parts":[{"type":"text","content":"Hola…"}]}]""")
            assertThat(Json.parse(chat().attributes[OUTPUT]!!).isArray).isTrue()
        }
    }

    @Nested
    inner class `an execute_tool span` {
        @Test
        fun `has the args the tool ran with, after the hooks, and what it answered`() {
            model.answers(listOf(weatherCall()), listOf(TextPart("Hacen 7 grados")))
            val toUshuaia = object: AgentHooks {
                override fun beforeTool(call: ToolCallPart, context: AgentToolContext): JsonObject =
                    Json.obj("city" to "Ushuaia")
            }
            val agent = Agent("support").model(model).tools(weather).hooks(toUshuaia)

            runner().run(agent.build(), Message.user("Que clima hay?"))

            val tool = telemetry.named("execute_tool getWeather")
            assertThat(tool.attributes[ARGUMENTS]).isEqualTo("""{"city":"Ushuaia"}""")
            assertThat(tool.attributes[RESULT]).isEqualTo("7 grados en Ushuaia")
        }
    }

    private fun chats(): List<SpanData> =
        telemetry.spans.filter { it.name.startsWith("chat") }.sortedBy { it.startEpochNanos }

    private fun chat() = chats().single()

    private fun loop(settings: AITelemetrySettings = capturing) =
        ToolLoop(model, listOf(weather), openTelemetry = telemetry.openTelemetry, telemetrySettings = settings)

    private fun runner() =
        AgentRunner(ModelRegistry(), openTelemetry = telemetry.openTelemetry, telemetrySettings = capturing)

    private fun weatherCall() = ToolCallPart("call_1", "getWeather", Json.obj("city" to "Bariloche"))

    private val telemetry = TestTelemetry()
    private val capturing = AITelemetrySettings(captureContent = true)
    private val model = FakeChatModel()
    private val weather = WeatherTool()

    private class WeatherTool: Tool<WeatherTool.Args>(Args.serializer()) {
        override val name = "getWeather"
        override val description = "The weather of a city"

        override fun execute(args: Args, context: ToolContext) = ToolResult.text("7 grados en ${args.city}")

        @Serializable
        data class Args(val city: String)
    }

    private companion object {
        val SYSTEM = stringKey("gen_ai.system_instructions")
        val INPUT = stringKey("gen_ai.input.messages")
        val OUTPUT = stringKey("gen_ai.output.messages")
        val DEFINITIONS = stringKey("gen_ai.tool.definitions")
        val ARGUMENTS = stringKey("gen_ai.tool.call.arguments")
        val RESULT = stringKey("gen_ai.tool.call.result")
    }
}
