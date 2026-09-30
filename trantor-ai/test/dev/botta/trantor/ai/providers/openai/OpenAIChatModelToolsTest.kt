@file:Suppress("ClassName")

package dev.botta.trantor.ai.providers.openai

import dev.botta.json.Json
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.ai.testing.FakeHttpClient
import dev.botta.trantor.ai.tools.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OpenAIChatModelToolsTest {
    @Test
    fun `sends the tools with their schema`() {
        httpClient.body = fixture("tool-call")

        model.generate(requestWith(weatherTool))

        assertThat(sentBody()["tools"].toString()).isEqualTo(
            """[{"type":"function","name":"getWeather","description":"The weather of a city","strict":true,""" +
                """"parameters":{"type":"object","properties":{"city":{"type":"string"}},""" +
                """"additionalProperties":false,"required":["city"]}}]"""
        )
    }

    @Test
    fun `a tool that is not strict keeps its schema as it is`() {
        httpClient.body = fixture("tool-call")

        model.generate(requestWith(weatherTool.copy(strict = false)))

        assertThat(sentBody()["tools"]?.asArray()?.get(0)?.asObject()?.get("parameters").toString())
            .isEqualTo("""{"type":"object","properties":{"city":{"type":"string"}}}""")
    }

    @Test
    fun `a tool whose schema refers to itself stays strict, which OpenAI holds a model to`() {
        httpClient.body = fixture("tool-call")
        val tree = Json.obj(
            "type" to "object",
            "properties" to Json.obj("children" to Json.obj("type" to "array", "items" to Json.obj("\$ref" to "#"))),
        )

        val response = model.generate(requestWith(weatherTool.copy(parameters = tree)))

        val sent = sentBody()["tools"]?.asArray()?.get(0)?.asObject()
        assertThat(sent?.get("strict")?.asBoolean()).isTrue()
        assertThat(sent?.path("parameters.additionalProperties")?.asBoolean()).isFalse()
        assertThat(response.warnings).isEmpty()
    }

    @Test
    fun `lets the model choose the tool by default`() {
        httpClient.body = fixture("tool-call")

        model.generate(requestWith(weatherTool))

        assertThat(sentBody()["tool_choice"]?.asString()).isEqualTo("auto")
    }

    @Test
    fun `asks for a tool by name`() {
        httpClient.body = fixture("tool-call")

        model.generate(requestWith(weatherTool, choice = ToolChoice.Named("getWeather")))

        assertThat(sentBody()["tool_choice"].toString()).isEqualTo("""{"type":"function","name":"getWeather"}""")
    }

    @Test
    fun `asks for any tool`() {
        httpClient.body = fixture("tool-call")

        model.generate(requestWith(weatherTool, choice = ToolChoice.Required))

        assertThat(sentBody()["tool_choice"]?.asString()).isEqualTo("required")
    }

    @Test
    fun `sends a tool of the provider with its own arguments`() {
        httpClient.body = fixture("tool-call")
        val webSearch = ProviderToolSpec("openai.web_search", Json.obj("search_context_size" to "low"))

        model.generate(requestWith(webSearch))

        assertThat(sentBody()["tools"].toString())
            .isEqualTo("""[{"search_context_size":"low","type":"web_search"}]""")
    }

    @Test
    fun `drops a tool of another provider with a warning`() {
        httpClient.body = fixture("tool-call")
        val anthropicTool = ProviderToolSpec("anthropic.computer", Json.obj())

        val response = model.generate(requestWith(anthropicTool))

        assertThat(sentBody()["tools"].toString()).isEqualTo("[]")
        assertThat(response.warnings.map { it.message }).containsExactly("Tool anthropic.computer is not an OpenAI tool and was dropped")
    }

    @Test
    fun `sends whether tools can run in parallel`() {
        httpClient.body = fixture("tool-call")
        val request = requestWith(weatherTool).copy(settings = ChatSettings(parallelToolCalls = false))

        model.generate(request)

        assertThat(sentBody()["parallel_tool_calls"]?.asBoolean()).isFalse()
    }

    @Test
    fun `reads the tool the model asked for`() {
        httpClient.body = fixture("tool-call")

        val response = model.generate(requestWith(weatherTool))

        val call = response.toolCalls.single()
        assertThat(call.callId).isEqualTo("call_Bh8ibYfKDf5kfsf1gsbUdqff")
        assertThat(call.toolName).isEqualTo("getWeather")
        assertThat(call.input).isEqualTo(Json.obj("city" to "Bariloche"))
        assertThat(response.finishReason).isEqualTo(FinishReasons.ToolCalls)
    }

    @Test
    fun `keeps the item id of the call`() {
        httpClient.body = fixture("tool-call")

        val call = model.generate(requestWith(weatherTool)).toolCalls.single()

        assertThat(call.metadata["openai"]).isEqualTo(Json.obj("id" to "fc_0fa3f5f108a19346006aaf410db8a887d2bf2f6e4749804c5b"))
    }

    @Test
    fun `sends back the call and its result`() {
        httpClient.body = fixture("tool-call")
        val call = model.generate(requestWith(weatherTool)).toolCalls.single()
        val result = ToolResultPart(call.callId, call.toolName, ToolOutput.Json(Json.obj("celsius" to 7)))

        model.generate(
            requestWith(weatherTool).copy(
                messages = listOf(Message.user("Que temperatura hay?"), Message.Assistant(listOf(call)), Message.toolResult(result))
            )
        )

        assertThat(sentBody()["input"]?.asArray()?.get(1).toString()).isEqualTo(
            """{"type":"function_call","call_id":"call_Bh8ibYfKDf5kfsf1gsbUdqff","name":"getWeather","arguments":"{\"city\":\"Bariloche\"}"}"""
        )
        assertThat(sentBody()["input"]?.asArray()?.get(2).toString()).isEqualTo(
            """{"type":"function_call_output","call_id":"call_Bh8ibYfKDf5kfsf1gsbUdqff","output":"{\"celsius\":7}"}"""
        )
    }

    @Test
    fun `sends a text result of a tool as it is`() {
        httpClient.body = fixture("tool-call")
        val result = ToolResultPart("call_Bh8ibYfKDf5kfsf1gsbUdqff", "getWeather", ToolOutput.Text("7 grados"))

        model.generate(requestWith(weatherTool).copy(messages = listOf(Message.toolResult(result))))

        assertThat(sentBody()["input"]?.asArray()?.get(0)?.asObject()?.get("output")?.asString()).isEqualTo("7 grados")
    }

    @Test
    fun `a deferred tool goes with defer_loading, and the tool search of OpenAI after the tools`() {
        httpClient.body = fixture("tool-call")
        val searching = OpenAIChatModel("gpt-5.4", OpenAIConfig(apiKey = "sk-test"), httpClient)
        val request = requestWith(weatherTool.copy(deferLoading = true))

        searching.generate(request.copy(tools = request.tools + timeTool))

        val tools = sentBody()["tools"]!!.asArray()!!.map { it.asObject()!! }
        assertThat(tools.map { it["name"]?.asString() ?: it["type"]?.asString() })
            .containsExactly("getWeather", "getTime", "tool_search")
        assertThat(tools.map { it["defer_loading"]?.asBoolean() }).containsExactly(true, null, null)
    }

    @Test
    fun `a model that does not search tools gets them up front, and says so`() {
        httpClient.body = fixture("tool-call")

        val response = model.generate(requestWith(weatherTool.copy(deferLoading = true)))

        val tools = sentBody()["tools"]!!.asArray()!!.map { it.asObject()!! }
        assertThat(tools.map { it["name"]?.asString() }).containsExactly("getWeather")
        assertThat(tools.single().containsKey("defer_loading")).isFalse()
        assertThat(response.warnings.single().message).contains("does not search tools", "getWeather")
    }

    @Test
    fun `sends back the namespace OpenAI gave the call of a tool it found, which it needs to match it`() {
        httpClient.body = fixture("tool-search-1")
        val searching = OpenAIChatModel("gpt-5.4", OpenAIConfig(apiKey = "sk-test"), httpClient)
        val answer = searching.generate(requestWith(weatherTool.copy(deferLoading = true)))

        searching.generate(requestWith(weatherTool.copy(deferLoading = true)).copy(messages = listOf(answer.asMessage())))

        val call = sentBody()["input"]!!.asArray()!!.map { it.asObject()!! }
            .single { it["type"]?.asString() == "function_call" }
        assertThat(call["namespace"]?.asString()).isEqualTo("getWeather")
    }

    private fun requestWith(tool: ToolSpec, choice: ToolChoice = ToolChoice.Auto) = ChatRequest(
        messages = listOf(Message.user("Que temperatura hay en Bariloche?")),
        tools = listOf(tool),
        toolChoice = choice,
    )

    private fun sentBody() = Json.parse(httpClient.requestBody!!).asObject()!!

    private fun fixture(name: String) =
        javaClass.getResource("/openai/$name.json")?.readText() ?: error("Missing fixture $name")

    private val weatherTool = FunctionToolSpec(
        name = "getWeather",
        description = "The weather of a city",
        parameters = Json.obj(
            "type" to "object",
            "properties" to Json.obj("city" to Json.obj("type" to "string")),
        ),
    )

    private val timeTool = FunctionToolSpec(
        name = "getTime",
        description = "The time",
        parameters = Json.obj("type" to "object", "properties" to Json.obj()),
    )

    private val httpClient = FakeHttpClient()
    private val model = OpenAIChatModel("gpt-4.1-mini", OpenAIConfig(apiKey = "sk-test"), httpClient)
}
