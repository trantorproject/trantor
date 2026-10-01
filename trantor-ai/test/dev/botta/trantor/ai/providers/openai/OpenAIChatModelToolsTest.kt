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
        httpClient.body = fixture("chat/tool-call")

        model.generate(requestWith(weatherTool))

        assertThat(sentBody()["tools"].toString()).isEqualTo(
            """[{"type":"function","name":"getWeather","description":"The weather of a city","strict":true,""" +
                """"parameters":{"type":"object","properties":{"city":{"type":"string"}},""" +
                """"additionalProperties":false,"required":["city"]}}]"""
        )
    }

    @Test
    fun `a tool that is not strict keeps its schema as it is`() {
        httpClient.body = fixture("chat/tool-call")

        model.generate(requestWith(weatherTool.copy(strict = false)))

        assertThat(sentBody()["tools"]?.asArray()?.get(0)?.asObject()?.get("parameters").toString())
            .isEqualTo("""{"type":"object","properties":{"city":{"type":"string"}}}""")
    }

    @Test
    fun `a tool whose schema refers to itself stays strict, which OpenAI holds a model to`() {
        httpClient.body = fixture("chat/tool-call")
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
        httpClient.body = fixture("chat/tool-call")

        model.generate(requestWith(weatherTool))

        assertThat(sentBody()["tool_choice"]?.asString()).isEqualTo("auto")
    }

    @Test
    fun `asks for a tool by name`() {
        httpClient.body = fixture("chat/tool-call")

        model.generate(requestWith(weatherTool, choice = ToolChoice.Named("getWeather")))

        assertThat(sentBody()["tool_choice"].toString()).isEqualTo("""{"type":"function","name":"getWeather"}""")
    }

    @Test
    fun `asks for any tool`() {
        httpClient.body = fixture("chat/tool-call")

        model.generate(requestWith(weatherTool, choice = ToolChoice.Required))

        assertThat(sentBody()["tool_choice"]?.asString()).isEqualTo("required")
    }

    @Test
    fun `sends a tool of the provider with its own arguments`() {
        httpClient.body = fixture("chat/tool-call")
        val webSearch = ProviderToolSpec("openai.web_search", Json.obj("search_context_size" to "low"))

        model.generate(requestWith(webSearch))

        assertThat(sentBody()["tools"].toString())
            .isEqualTo("""[{"search_context_size":"low","type":"web_search"}]""")
    }

    @Test
    fun `drops a tool of another provider with a warning`() {
        httpClient.body = fixture("chat/tool-call")
        val anthropicTool = ProviderToolSpec("anthropic.computer", Json.obj())

        val response = model.generate(requestWith(anthropicTool))

        assertThat(sentBody()["tools"].toString()).isEqualTo("[]")
        assertThat(response.warnings.map { it.message })
            .containsExactly("Tool anthropic.computer is not an OpenAI tool and was dropped")
    }

    @Test
    fun `sends whether tools can run in parallel`() {
        httpClient.body = fixture("chat/tool-call")
        val request = requestWith(weatherTool).copy(settings = ChatSettings(parallelToolCalls = false))

        model.generate(request)

        assertThat(sentBody()["parallel_tool_calls"]?.asBoolean()).isFalse()
    }

    @Test
    fun `reads the tool the model asked for`() {
        httpClient.body = fixture("chat/tool-call")

        val response = model.generate(requestWith(weatherTool))

        val call = response.toolCalls.single()
        assertThat(call.callId).isEqualTo("call_Bh8ibYfKDf5kfsf1gsbUdqff")
        assertThat(call.toolName).isEqualTo("getWeather")
        assertThat(call.input).isEqualTo(Json.obj("city" to "Bariloche"))
        assertThat(response.finishReason).isEqualTo(FinishReasons.ToolCalls)
    }

    @Test
    fun `keeps the item id of the call`() {
        httpClient.body = fixture("chat/tool-call")

        val call = model.generate(requestWith(weatherTool)).toolCalls.single()

        assertThat(call.metadata["openai"])
            .isEqualTo(Json.obj("id" to "fc_0fa3f5f108a19346006aaf410db8a887d2bf2f6e4749804c5b"))
    }

    @Test
    fun `sends back the call and its result`() {
        httpClient.body = fixture("chat/tool-call")
        val call = model.generate(requestWith(weatherTool)).toolCalls.single()
        val result = ToolResultPart(call.callId, call.toolName, ToolOutput.Json(Json.obj("celsius" to 7)))

        model.generate(
            requestWith(weatherTool).copy(
                messages = listOf(
                    Message.user("Que temperatura hay?"),
                    Message.Assistant(listOf(call)),
                    Message.toolResult(result),
                )
            )
        )

        assertThat(sentBody()["input"]?.asArray()?.get(1).toString()).isEqualTo(
            """{"type":"function_call","call_id":"call_Bh8ibYfKDf5kfsf1gsbUdqff","name":"getWeather",""" +
                """"arguments":"{\"city\":\"Bariloche\"}"}"""
        )
        assertThat(sentBody()["input"]?.asArray()?.get(2).toString()).isEqualTo(
            """{"type":"function_call_output","call_id":"call_Bh8ibYfKDf5kfsf1gsbUdqff","output":"{\"celsius\":7}"}"""
        )
    }

    @Test
    fun `sends a text result of a tool as it is`() {
        httpClient.body = fixture("chat/tool-call")
        val result = ToolResultPart("call_Bh8ibYfKDf5kfsf1gsbUdqff", "getWeather", ToolOutput.Text("7 grados"))

        model.generate(requestWith(weatherTool).copy(messages = listOf(Message.toolResult(result))))

        assertThat(sentBody()["input"]?.asArray()?.get(0)?.asObject()?.get("output")?.asString()).isEqualTo("7 grados")
    }

    @Test
    fun `without a search in the request a deferred tool goes up front, since nothing would find it, and says so`() {
        httpClient.body = fixture("chat/tool-call")

        val response = searchingModel.generate(requestWith(weatherTool.copy(deferLoading = true)))

        val tools = sentBody()["tools"]!!.asArray()!!.map { it.asObject()!! }
        assertThat(tools.single().containsKey("defer_loading")).isFalse()
        assertThat(response.warnings.single().message).contains("getWeather", "no search")
    }

    @Test
    fun `a model that does not load deferred tools gets them up front, and says so`() {
        httpClient.body = fixture("chat/tool-call")

        val response = model.generate(searchRequest)

        val tools = sentBody()["tools"]!!.asArray()!!.map { it.asObject()!! }
        assertThat(tools.map { it["type"]?.asString() }).containsExactly("function", "function")
        assertThat(tools.none { it.containsKey("defer_loading") }).isTrue()
        assertThat(response.warnings.single().message).contains("getWeather", "does not load them")
    }

    @Test
    fun `sends back the namespace OpenAI gave the call of a tool it found, which it needs to match it`() {
        httpClient.body = fixture("tool-search/search-2")
        val searching = OpenAIChatModel("gpt-5.4", OpenAIConfig(apiKey = "sk-test"), httpClient)
        val request = requestWith(weatherTool.copy(deferLoading = true))
        val answer = searching.generate(request)

        searching.generate(request.copy(messages = listOf(answer.asMessage())))

        val call = sentBody()["input"]!!.asArray()!!.map { it.asObject()!! }
            .single { it["type"]?.asString() == "function_call" }
        assertThat(call["namespace"]?.asString()).isEqualTo("getWeather")
    }

    @Test
    fun `a search of the application goes as a tool search the client runs, and the tools deferred`() {
        httpClient.body = fixture("chat/tool-call")

        searchingModel.generate(searchRequest)

        val tools = sentBody()["tools"]!!.asArray()!!.map { it.asObject()!! }
        assertThat(tools.map { it["type"]?.asString() }).containsExactly("tool_search", "function")
        assertThat(tools[0]["execution"]?.asString()).isEqualTo("client")
        assertThat(tools[1]["defer_loading"]?.asBoolean()).isTrue()
        assertThat(tools[0]["description"]?.asString()).isEqualTo("Searches the tools")
        assertThat(tools[0]["parameters"]?.asObject()?.get("required").toString()).isEqualTo("""["query"]""")
    }

    @Test
    fun `and the tool search the model asks the client for is a call to the search of the application`() {
        httpClient.body = fixture("tool-search/search-1")

        val response = searchingModel.generate(searchRequest)

        val call = response.toolCalls.single()
        assertThat(call.toolName).isEqualTo("search_tools")
        assertThat(call.callId).isEqualTo("call_4XeRHbGhiHND56661lPct5pE")
        assertThat(call.input["query"]?.asString()).startsWith("weather current temperature")
        assertThat(response.finishReason).isEqualTo(FinishReasons.ToolCalls)
    }

    @Test
    fun `which goes back as the tool search it was, and its result as the definitions of the tools it found`() {
        httpClient.body = fixture("tool-search/search-1")
        val call = searchingModel.generate(searchRequest).toolCalls.single()
        val found = Json.obj("tools" to Json.array(Json.obj("name" to "getWeather", "description" to "")))
        val result = ToolResultPart(call.callId, call.toolName, ToolOutput.Json(found))

        searchingModel.generate(
            searchRequest.copy(messages = searchRequest.messages + call.asAssistant() + Message.toolResult(result)),
        )

        val input = sentBody()["input"]!!.asArray()!!.map { it.asObject()!! }
        assertThat(input[1]["type"]?.asString()).isEqualTo("tool_search_call")
        assertThat(input[1]["execution"]?.asString()).isEqualTo("client")
        assertThat(input[1]["call_id"]?.asString()).isEqualTo(call.callId)
        assertThat(input[1]["arguments"]).isEqualTo(call.input)
        assertThat(input[2]["type"]?.asString()).isEqualTo("tool_search_output")
        assertThat(input[2]["execution"]?.asString()).isEqualTo("client")
        assertThat(input[2]["call_id"]?.asString()).isEqualTo(call.callId)
        val loaded = input[2]["tools"]!!.asArray()!!.map { it.asObject()!! }
        assertThat(loaded.map { it["name"]?.asString() }).containsExactly("getWeather")
        assertThat(loaded.single()["parameters"]?.asObject()?.get("properties")?.asObject()?.keys)
            .containsExactly("city")
    }

    private fun ToolCallPart.asAssistant() = Message.Assistant(listOf(this))

    private val searchRequest by lazy {
        ChatRequest(
            messages = listOf(Message.user("Que temperatura hay en Bariloche?")),
            tools = listOf(
                FunctionToolSpec(
                    name = "search_tools",
                    description = "Searches the tools",
                    parameters = Json.obj(
                        "type" to "object",
                        "properties" to Json.obj("query" to Json.obj("type" to "string")),
                    ),
                    searchesTools = true,
                ),
                weatherTool.copy(deferLoading = true),
            ),
        )
    }

    private val searchingModel by lazy { OpenAIChatModel("gpt-5.4", OpenAIConfig(apiKey = "sk-test"), httpClient) }

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
