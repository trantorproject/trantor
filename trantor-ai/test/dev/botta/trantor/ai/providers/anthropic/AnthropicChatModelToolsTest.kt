@file:Suppress("ClassName")

package dev.botta.trantor.ai.providers.anthropic

import dev.botta.json.Json
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.ai.testing.FakeHttpClient
import dev.botta.trantor.ai.tools.*
import dev.botta.trantor.ai.errors.UnsupportedRequestError
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * What the adapter sends when there are tools. What comes back is tested against a recorded answer: a tool_use
 * block written here by hand would only prove that two guesses about the api agree with each other.
 */
class AnthropicChatModelToolsTest {
    @Nested
    inner class `the tools of the call` {
        @Test
        fun `go with their schema, under the name Anthropic gives it`() {
            generate(requestWith(weatherTool))

            assertThat(sentBody()["tools"].toString()).isEqualTo(
                """[{"name":"getWeather","input_schema":{"type":"object","properties":{"city":{"type":"string"}},""" +
                    """"additionalProperties":false,"required":["city"]},"description":"The weather of a city",""" +
                    """"strict":true}]"""
            )
        }

        @Test
        fun `a tool whose schema refers to itself goes without strict, which Anthropic cannot hold a model to`() {
            val response = generate(requestWith(weatherTool.copy(parameters = tree)))

            assertThat(sentTool().containsKey("strict")).isFalse()
            assertThat(response.warnings.single().message).contains("getWeather", "refers to itself")
        }

        @Test
        fun `and so an answer whose schema refers to itself is turned down before it is asked for`() {
            val request = ChatRequest(listOf(Message.user("Un arbol")), output = OutputSpec.Json(tree))

            assertThatThrownBy { generate(request) }
                .isInstanceOf(UnsupportedRequestError::class.java)
                .hasMessageContaining("refers to itself")
            assertThat(httpClient.requestBody).isNull()
        }

        @Test
        fun `and a tool that is not strict keeps the schema as it was written`() {
            generate(requestWith(weatherTool.copy(strict = false)))

            assertThat(sentTool()["input_schema"].toString())
                .isEqualTo("""{"type":"object","properties":{"city":{"type":"string"}}}""")
            assertThat(sentTool().containsKey("strict")).isFalse()
        }

        @Test
        fun `a tool of the provider goes as the versioned type it is, with its own arguments`() {
            val webSearch = ProviderToolSpec("anthropic.web_search_20260209", Json.obj("name" to "web_search"))

            generate(requestWith(webSearch))

            assertThat(sentBody()["tools"].toString())
                .isEqualTo("""[{"name":"web_search","type":"web_search_20260209"}]""")
        }

        @Test
        fun `and a tool of another provider is dropped with a warning`() {
            val response = generate(requestWith(ProviderToolSpec("openai.web_search", Json.obj())))

            assertThat(sentBody()["tools"].toString()).isEqualTo("[]")
            assertThat(response.warnings.map { it.message })
                .containsExactly("Tool openai.web_search is not an Anthropic tool and was dropped")
        }
    }

    @Nested
    inner class `how free the model is to call them` {
        @Test
        fun `is its own choice by default`() {
            generate(requestWith(weatherTool))

            assertThat(sentBody()["tool_choice"].toString()).isEqualTo("""{"type":"auto"}""")
        }

        @Test
        fun `it can be told to call any of them`() {
            generate(requestWith(weatherTool, choice = ToolChoice.Required))

            assertThat(sentBody()["tool_choice"].toString()).isEqualTo("""{"type":"any"}""")
        }

        @Test
        fun `or one by name`() {
            generate(requestWith(weatherTool, choice = ToolChoice.Named("getWeather")))

            assertThat(sentBody()["tool_choice"].toString()).isEqualTo("""{"type":"tool","name":"getWeather"}""")
        }

        @Test
        fun `or none at all`() {
            generate(requestWith(weatherTool, choice = ToolChoice.None))

            assertThat(sentBody()["tool_choice"].toString()).isEqualTo("""{"type":"none"}""")
        }

        @Test
        fun `and whether they can run at once qualifies the choice, instead of being a field of its own`() {
            val request = requestWith(weatherTool).copy(settings = ChatSettings(parallelToolCalls = false))

            generate(request)

            assertThat(sentBody()["tool_choice"].toString())
                .isEqualTo("""{"type":"auto","disable_parallel_tool_use":true}""")
            assertThat(sentBody().containsKey("parallel_tool_calls")).isFalse()
        }

        @Test
        fun `so with no tools there is nowhere to put it, and it says so`() {
            val request = ChatRequest(listOf(Message.user("Hola")), settings = ChatSettings(parallelToolCalls = false))

            val response = generate(request)

            assertThat(sentBody().containsKey("tool_choice")).isFalse()
            assertThat(response.warnings.map { it.setting }).containsExactly("parallelToolCalls")
        }

        @Test
        fun `nothing about tools is sent when there are none`() {
            generate(ChatRequest(Message.user("Hola")))

            assertThat(sentBody().containsKey("tools")).isFalse()
            assertThat(sentBody().containsKey("tool_choice")).isFalse()
        }
    }

    @Nested
    inner class `a conversation that used a tool` {
        @Test
        fun `sends the call back as the block it came in`() {
            val call = ToolCallPart("toolu_01A09q90qw90lq917835lq9", "getWeather", Json.obj("city" to "Bariloche"))

            generate(requestWith(weatherTool).copy(messages = listOf(Message.Assistant(listOf(call)))))

            assertThat(sentBlock().toString()).isEqualTo(
                """{"type":"tool_use","id":"toolu_01A09q90qw90lq917835lq9","name":"getWeather",""" +
                    """"input":{"city":"Bariloche"}}"""
            )
        }

        @Test
        fun `and the result as a turn of the user, matched to its call by id alone`() {
            val result = ToolResultPart("toolu_01A", "getWeather", ToolOutput.Json(Json.obj("celsius" to 7)))

            generate(requestWith(weatherTool).copy(messages = listOf(Message.toolResult(result))))

            assertThat(sentMessage()["role"]?.asString()).isEqualTo("user")
            assertThat(sentBlock().toString()).isEqualTo(
                """{"type":"tool_result","tool_use_id":"toolu_01A","content":"{\"celsius\":7}"}"""
            )
        }

        @Test
        fun `a text result goes as it was written`() {
            val result = ToolResultPart("toolu_01A", "getWeather", ToolOutput.Text("7 grados"))

            generate(requestWith(weatherTool).copy(messages = listOf(Message.toolResult(result))))

            assertThat(sentBlock()["content"]?.asString()).isEqualTo("7 grados")
        }

        @Test
        fun `a tool that failed says so, so that the model can try something else`() {
            val result = ToolResultPart("toolu_01A", "getWeather", ToolOutput.Text("No anda"), isError = true)

            generate(requestWith(weatherTool).copy(messages = listOf(Message.toolResult(result))))

            assertThat(sentBlock()["is_error"]?.asBoolean()).isTrue()
        }

        @Test
        fun `and the results come before anything else the user wrote`() {
            // Anthropic answers 400 to a user message with text before its tool results, and writing the question
            // after the answer is a reasonable way to build the conversation
            val result = ToolResultPart("toolu_01A", "getWeather", ToolOutput.Text("7 grados"))
            val messages = listOf(Message.user("Y ahora?"), Message.toolResult(result))

            generate(requestWith(weatherTool).copy(messages = messages))

            assertThat(sentBlock(0)["type"]?.asString()).isEqualTo("tool_result")
            assertThat(sentBlock(1)["type"]?.asString()).isEqualTo("text")
        }
    }

    private fun requestWith(tool: ToolSpec, choice: ToolChoice = ToolChoice.Auto) = ChatRequest(
        messages = listOf(Message.user("Que temperatura hay en Bariloche?")),
        tools = listOf(tool),
        toolChoice = choice,
    )

    private fun generate(request: ChatRequest) = model.generate(request)

    private fun sentBody() = Json.parse(httpClient.requestBody!!).asObject()!!

    private fun sentTool(index: Int = 0) = sentBody()["tools"]!!.asArray()!![index].asObject()!!

    private fun sentMessage(index: Int = 0) = sentBody()["messages"]!!.asArray()!![index].asObject()!!

    private fun sentBlock(index: Int = 0, message: Int = 0) =
        sentMessage(message)["content"]!!.asArray()!![index].asObject()!!

    private val weatherTool = FunctionToolSpec(
        name = "getWeather",
        description = "The weather of a city",
        parameters = Json.obj(
            "type" to "object",
            "properties" to Json.obj("city" to Json.obj("type" to "string")),
        ),
    )

    private val tree = Json.obj(
        "type" to "object",
        "properties" to Json.obj("children" to Json.obj("type" to "array", "items" to Json.obj($$"$ref" to "#"))),
    )

    // An empty object is an answer the mapper reads without complaining, so these tests are about what was sent
    private val httpClient = FakeHttpClient()
    private val model = AnthropicChatModel("claude-sonnet-4-5", AnthropicConfig(apiKey = "sk-ant-test"), httpClient)
}
