package dev.botta.trantor.ai.providers.anthropic

import dev.botta.json.Json
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.ai.testing.FakeHttpClient
import dev.botta.trantor.ai.tools.ToolOutput
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * What the adapter reads back, against answers recorded from the real api. The few bodies written inline are
 * shapes no recording has yet — a citation, a server tool — and say only what the adapter does with a field.
 */
class AnthropicChatModelResponseTest {
    @Test
    fun `returns the text of the response`() {
        httpClient.body = fixture("text-simple")

        val response = model.generate(ChatRequest(Message.user("Contame un chiste corto")))

        assertThat(response.text).isEqualTo("¿Por qué los pájaros no usan Facebook?\n\nPorque ya tienen Twitter 🐦")
        assertThat(response.finishReason).isEqualTo(FinishReasons.Stop)
        assertThat(response.rawFinishReason).isEqualTo("end_turn")
    }

    @Test
    fun `returns the id and the dated model that actually answered`() {
        httpClient.body = fixture("text-simple")

        val info = model.generate(ChatRequest(Message.user("Hola"))).info

        assertThat(info.id).isEqualTo("msg_011CfFGveUejYgEaWzh1M6hM")
        // Asked for claude-sonnet-4-5 and a dated one answered, which is what the field is for
        assertThat(info.model).isEqualTo("claude-sonnet-4-5-20250929")
        assertThat(info.provider).isEqualTo("anthropic")
        assertThat(info.latency.isPositive()).isTrue()
    }

    @Test
    fun `returns the usage with details as subsets`() {
        httpClient.body = fixture("text-simple")

        val usage = model.generate(ChatRequest(Message.user("Hola"))).usage

        assertThat(usage.inputTokens).isEqualTo(15)
        assertThat(usage.outputTokens).isEqualTo(28)
        assertThat(usage.cacheReadTokens).isZero()
        assertThat(usage.cacheWriteTokens).isZero()
        assertThat(usage.totalTokens).isEqualTo(43)
        // Anthropic reports it only when the model thought, so it is unknown and not zero
        assertThat(usage.reasoningTokens).isNull()
        assertThat(usage.raw).isNotNull()
    }

    @Test
    fun `adds what went into the cache into the input, so the same field means the same as in OpenAI`() {
        // Anthropic leaves the cache out of input_tokens: here it says 19, the question, for a prompt of 7,249.
        // Recorded with the mark at the end of the system prompt, the first of two calls that share it
        httpClient.body = fixture("cache-write")

        val usage = model.generate(ChatRequest(Message.user("Hola"))).usage

        assertThat(usage.inputTokens).isEqualTo(7_249)
        assertThat(usage.cacheWriteTokens).isEqualTo(7_230)
        assertThat(usage.cacheReadTokens).isZero()
        assertThat(usage.uncachedInputTokens).isEqualTo(19)
    }

    @Test
    fun `and what came out of it too`() {
        // The second call, a moment later with another question: the whole system prompt is read back
        httpClient.body = fixture("cache-read")

        val usage = model.generate(ChatRequest(Message.user("Hola"))).usage

        assertThat(usage.inputTokens).isEqualTo(7_250)
        assertThat(usage.cacheReadTokens).isEqualTo(7_230)
        assertThat(usage.cacheWriteTokens).isZero()
        assertThat(usage.uncachedInputTokens).isEqualTo(20)
        assertThat(usage.totalTokens).isEqualTo(7_281)
    }

    @Test
    fun `reads the thinking tokens, which are billed inside the output`() {
        httpClient.body = usageOf("""{"input_tokens":10,"output_tokens":900,
            "output_tokens_details":{"thinking_tokens":800}}""")

        val usage = model.generate(ChatRequest(Message.user("Hola"))).usage

        assertThat(usage.outputTokens).isEqualTo(900)
        assertThat(usage.reasoningTokens).isEqualTo(800)
    }

    @Test
    fun `a response cut by max tokens finishes by length`() {
        httpClient.body = """{"content":[{"type":"text","text":"Habia una"}],"stop_reason":"max_tokens"}"""

        val response = model.generate(ChatRequest(Message.user("Contame una historia")))

        assertThat(response.finishReason).isEqualTo(FinishReasons.Length)
    }

    @Test
    fun `a stop sequence is a stop and not a cut`() {
        httpClient.body = """{"content":[{"type":"text","text":"Listo"}],"stop_reason":"stop_sequence"}"""

        assertThat(model.generate(ChatRequest(Message.user("Hola"))).finishReason).isEqualTo(FinishReasons.Stop)
    }

    @Test
    fun `a stop reason we do not map keeps its name`() {
        httpClient.body = """{"content":[],"stop_reason":"pause_turn"}"""

        val response = model.generate(ChatRequest(Message.user("Hola")))

        assertThat(response.finishReason).isEqualTo(FinishReasons.Other)
        assertThat(response.rawFinishReason).isEqualTo("pause_turn")
    }

    @Test
    fun `thinking comes back whole, so the next turn can send it as it was`() {
        httpClient.body = """{"content":[{"type":"thinking","thinking":"Lo pienso","signature":"abc"},
            {"type":"text","text":"42"}],"stop_reason":"end_turn"}"""

        val response = model.generate(ChatRequest(Message.user("Cuanto es?")))
        val reasoning = response.content.filterIsInstance<ReasoningPart>().single()

        assertThat(reasoning.text).isEqualTo("Lo pienso")
        // The signature is an encrypted copy of the whole reasoning: it is what carries it, not the text
        assertThat(reasoning.opaque.toString()).contains("\"signature\":\"abc\"")
        assertThat(response.text).isEqualTo("42")
    }

    @Test
    fun `and goes back to Anthropic exactly as it came`() {
        httpClient.body = """{"content":[{"type":"thinking","thinking":"Lo pienso","signature":"abc"}],
            "stop_reason":"end_turn"}"""
        val first = model.generate(ChatRequest(Message.user("Cuanto es?")))

        model.generate(ChatRequest(Message.user("Cuanto es?"), first.asMessage(), Message.user("Seguro?")))

        val sent = Json.parse(httpClient.requestBody!!).asObject()!!["messages"]?.asArray()?.get(1)

        assertThat(sent.toString())
            .isEqualTo("""{"role":"assistant","content":[{"type":"thinking","thinking":"Lo pienso","signature":"abc"}]}""")
    }

    @Test
    fun `reasoning of another provider is dropped, because only its own can take it back`() {
        val foreign = Message.Assistant(listOf(ReasoningPart(text = "de openai", opaque = Json.obj("id" to "rs_1"))))

        val response = model.generate(ChatRequest(Message.user("Hola"), foreign, Message.user("Y?")))

        assertThat(httpClient.requestBody).doesNotContain("rs_1")
        assertThat(response.warnings.map { it.message })
            .contains("Reasoning that Anthropic did not produce was dropped")
    }

    @Test
    fun `a block we do not model is kept whole and sent back`() {
        httpClient.body = """{"content":[{"type":"web_search_tool_result","tool_use_id":"srv_1","content":[]}],
            "stop_reason":"end_turn"}"""
        val first = model.generate(ChatRequest(Message.user("Buscá algo")))
        val part = first.content.filterIsInstance<ProviderPart>().single()

        model.generate(ChatRequest(Message.user("Buscá algo"), first.asMessage()))

        assertThat(part.type).isEqualTo("web_search_tool_result")
        assertThat(httpClient.requestBody)
            .contains(""""type":"web_search_tool_result","tool_use_id":"srv_1"""")
    }

    @Test
    fun `a tool Anthropic ran itself is a call the application has nothing to run`() {
        httpClient.body = """{"content":[{"type":"server_tool_use","id":"srv_1","name":"web_search",
            "input":{"query":"bariloche"}}],"stop_reason":"end_turn"}"""
        val first = model.generate(ChatRequest(Message.user("Buscá algo")))

        model.generate(ChatRequest(Message.user("Buscá algo"), first.asMessage()))

        val call = first.toolCalls.single()
        assertThat(call.toolName).isEqualTo("web_search")
        assertThat(call.providerExecuted).isTrue()
        // And it goes back as the block it was, not as the tool_use of a tool the application runs
        assertThat(httpClient.requestBody)
            .contains(""""type":"server_tool_use","id":"srv_1","name":"web_search","input":{"query":"bariloche"}""")
    }

    @Test
    fun `citations travel back with the text they belong to`() {
        httpClient.body = """{"content":[{"type":"text","text":"Segun el documento","citations":[{"type":"char_location",
            "document_index":0}]}],"stop_reason":"end_turn"}"""
        val first = model.generate(ChatRequest(Message.user("Que dice?")))

        model.generate(ChatRequest(Message.user("Que dice?"), first.asMessage()))

        assertThat(httpClient.requestBody).contains(""""citations":[{"type":"char_location","document_index":0}]""")
    }

    @Test
    fun `reads the tool the model asked for`() {
        httpClient.body = fixture("tool-call")

        val response = model.generate(ChatRequest(Message.user("Que temperatura hay en Bariloche?")))

        val call = response.toolCalls.single()
        assertThat(call.callId).isEqualTo("toolu_01HqdUxCTExh8AgjnGwofbud")
        assertThat(call.toolName).isEqualTo("getWeather")
        assertThat(call.input).isEqualTo(Json.obj("city" to "Bariloche, Argentina"))
        assertThat(call.providerExecuted).isFalse()
        assertThat(response.finishReason).isEqualTo(FinishReasons.ToolCalls)
    }

    @Test
    fun `sends back the call as it came, with what we do not model, and its result after it`() {
        httpClient.body = fixture("tool-call")
        val question = Message.user("Que temperatura hay en Bariloche?")
        val asked = model.generate(ChatRequest(question))
        val call = asked.toolCalls.single()
        val result = ToolResultPart(call.callId, call.toolName, ToolOutput.Json(Json.obj("celsius" to 7)))
        httpClient.body = fixture("tool-answer")

        val answer = model.generate(ChatRequest(question, asked.asMessage(), Message.toolResult(result)))

        // caller is a field Anthropic added that the part has no place for, and it travels back all the same
        assertThat(httpClient.requestBody).contains(
            """{"role":"assistant","content":[{"type":"tool_use","id":"toolu_01HqdUxCTExh8AgjnGwofbud",""" +
                """"name":"getWeather","input":{"city":"Bariloche, Argentina"},"caller":{"type":"direct"}}]}""",
            """{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_01HqdUxCTExh8AgjnGwofbud",""" +
                """"content":"{\"celsius\":7}"}]}""",
        )
        assertThat(answer.text).isEqualTo("La temperatura actual en Bariloche, Argentina es de **7°C**.")
        assertThat(answer.finishReason).isEqualTo(FinishReasons.Stop)
    }

    private fun usageOf(usage: String) =
        """{"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn","usage":$usage}"""

    private fun fixture(name: String) =
        javaClass.getResource("/anthropic/$name.json")?.readText() ?: error("Missing fixture $name")

    private val httpClient = FakeHttpClient()
    private val model = AnthropicChatModel("claude-sonnet-4-5", AnthropicConfig(apiKey = "sk-ant-test"), httpClient)
}
