@file:Suppress("ClassName")

package dev.botta.trantor.ai.providers.anthropic

import dev.botta.json.Json
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.ai.providers.ProviderOptions
import dev.botta.trantor.ai.schemas.JsonSchemas
import dev.botta.trantor.ai.testing.FakeHttpClient
import dev.botta.trantor.ai.tools.FunctionToolSpec
import dev.botta.trantor.ai.tools.ToolChoice
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * The same request against models of different generations.
 *
 * Anthropic moved `temperature`, the shape of thinking and structured output between generations, and answers 400
 * to whichever one a model dropped. These tests are the contract that the plain api works everywhere: the same
 * `ChatRequest` comes out as whatever each model takes, and says so when it could not.
 */
class AnthropicChatModelPerModelTest {
    @Nested
    inner class `sampling settings` {
        @Test
        fun `go to a model that still takes them`() {
            val settings = ChatSettings(temperature = 0.2, topP = 0.9)

            generate("claude-sonnet-4-5", settings)

            assertThat(sentBody()["temperature"]?.asDouble()).isEqualTo(0.2)
            assertThat(sentBody()["top_p"]?.asDouble()).isEqualTo(0.9)
        }

        @Test
        fun `are dropped with a warning on a model that answers 400 to them`() {
            val settings = ChatSettings(temperature = 0.2, topP = 0.9)

            val response = generate("claude-opus-5", settings)

            assertThat(sentBody().containsKey("temperature")).isFalse()
            assertThat(sentBody().containsKey("top_p")).isFalse()
            assertThat(response.warnings.map { it.setting }).containsExactly("temperature", "topP")
        }

        @Test
        fun `and a temperature above what Anthropic takes is brought down instead of failing the call`() {
            // OpenAI goes to 2 and Anthropic to 1, so the same portable setting would be a failed call here
            val response = generate("claude-sonnet-4-5", ChatSettings(temperature = 1.8))

            assertThat(sentBody()["temperature"]?.asDouble()).isEqualTo(1.0)
            assertThat(response.warnings.map { it.setting }).containsExactly("temperature")
        }
    }

    @Nested
    inner class `asking the model to think` {
        @Test
        fun `is an effort where there is one`() {
            generate("claude-opus-5", ChatSettings(reasoning = Reasoning.effort(ReasoningEfforts.Medium)))

            assertThat(sentBody().path("output_config.effort")?.asString()).isEqualTo("medium")
            assertThat(sentBody().path("thinking.type")?.asString()).isEqualTo("adaptive")
        }

        @Test
        fun `and a share of the budget where there is not`() {
            generate("claude-sonnet-4-5", ChatSettings(reasoning = Reasoning.effort(ReasoningEfforts.Medium)))

            assertThat(sentBody().containsKey("output_config")).isFalse()
            assertThat(sentBody().path("thinking.type")?.asString()).isEqualTo("enabled")
            // Thirty percent of the 64k this model gives
            assertThat(sentBody().path("thinking.budget_tokens")?.asInt()).isEqualTo(19_200)
        }

        @Test
        fun `so the same request works on both, which is the whole point`() {
            val settings = ChatSettings(reasoning = Reasoning.effort(ReasoningEfforts.High))

            val onNew = generate("claude-opus-5", settings)
            val onOld = generate("claude-sonnet-4-5", settings)

            assertThat(onNew.warnings).isEmpty()
            assertThat(onOld.warnings).isEmpty()
        }

        @Test
        fun `a budget in tokens goes as it was asked where the model takes one`() {
            generate("claude-sonnet-4-5", ChatSettings(reasoning = Reasoning.budget(10_000)))

            assertThat(sentBody().path("thinking.budget_tokens")?.asInt()).isEqualTo(10_000)
        }

        @Test
        fun `and is dropped where the model rejects it, since no level means that many tokens`() {
            val response = generate("claude-opus-5", ChatSettings(reasoning = Reasoning.budget(10_000)))

            assertThat(sentBody().containsKey("thinking")).isFalse()
            assertThat(response.warnings.map { it.setting }).containsExactly("reasoning.budgetTokens")
        }

        @Test
        fun `a budget under the minimum of the api is raised to it`() {
            generate("claude-sonnet-4-5", ChatSettings(reasoning = Reasoning.budget(10)))

            assertThat(sentBody().path("thinking.budget_tokens")?.asInt()).isEqualTo(1_024)
        }

        @Test
        fun `minimal is asked for as low, because Anthropic has no level below it`() {
            val response = generate("claude-opus-5", ChatSettings(reasoning = Reasoning.effort(ReasoningEfforts.Minimal)))

            assertThat(sentBody().path("output_config.effort")?.asString()).isEqualTo("low")
            assertThat(response.warnings.map { it.setting }).containsExactly("reasoning")
        }

        @Test
        fun `Off turns thinking off instead of leaving it to the model`() {
            generate("claude-opus-5", ChatSettings(reasoning = Reasoning.Off))

            assertThat(sentBody().path("thinking.type")?.asString()).isEqualTo("disabled")
        }

        @Test
        fun `except on a model that always thinks, which answers 400 to it`() {
            val response = generate("claude-opus-5-5", ChatSettings(reasoning = Reasoning.Off))

            assertThat(sentBody().containsKey("thinking")).isFalse()
            assertThat(response.warnings.map { it.message }).containsExactly(
                "claude-opus-5-5 always thinks, so Reasoning.Off was not sent; a lower effort is how it thinks less",
            )
        }

        @Test
        fun `and saying nothing sends nothing, so the model keeps its own default`() {
            generate("claude-opus-5", ChatSettings())

            assertThat(sentBody().containsKey("thinking")).isFalse()
            assertThat(sentBody().containsKey("output_config")).isFalse()
        }
    }

    @Nested
    inner class `max tokens` {
        @Test
        fun `asks for everything the model gives when nobody said otherwise`() {
            generate("claude-opus-5", ChatSettings())

            assertThat(sentBody()["max_tokens"]?.asInt()).isEqualTo(128_000)
        }

        @Test
        fun `leaves room for the thinking on top of the answer, because both come out of the same ceiling`() {
            generate("claude-sonnet-4-5", ChatSettings(maxOutputTokens = 1_000, reasoning = Reasoning.budget(4_000)))

            assertThat(sentBody()["max_tokens"]?.asInt()).isEqualTo(5_000)
        }

        @Test
        fun `and never asks for more than the model can give`() {
            val response = generate("claude-sonnet-4-5", ChatSettings(maxOutputTokens = 200_000))

            assertThat(sentBody()["max_tokens"]?.asInt()).isEqualTo(64_000)
            assertThat(response.warnings.map { it.setting }).containsExactly("maxOutputTokens")
        }
    }

    @Nested
    inner class `structured output` {
        @Test
        fun `goes as the native format where the model has one`() {
            generateWith("claude-sonnet-4-5", ChatRequest(listOf(Message.user("Hola")), output = jsonOutput()))

            assertThat(sentBody().path("output_config.format.type")?.asString()).isEqualTo("json_schema")
            // Anthropic demands the object be closed, exactly like OpenAI in strict mode
            assertThat(sentBody().path("output_config.format.schema.additionalProperties")?.asBoolean()).isFalse()
        }

        @Test
        fun `and is dropped with a warning on a model that does not have one`() {
            val response = generateWith(
                "claude-sonnet-4",
                ChatRequest(listOf(Message.user("Hola")), output = jsonOutput()),
            )

            assertThat(sentBody().containsKey("output_config")).isFalse()
            assertThat(response.warnings.map { it.setting }).containsExactly("output")
        }
    }

    @Nested
    inner class `being told to call a tool` {
        @Test
        fun `goes to a model that takes it`() {
            generateWith("claude-sonnet-4-5", requestWith(ToolChoice.Required))

            assertThat(sentBody()["tool_choice"].toString()).isEqualTo("""{"type":"any"}""")
        }

        @Test
        fun `and is left to the model on the ones that answer 400 to it`() {
            val response = generateWith("claude-fable-5-1", requestWith(ToolChoice.Named("getWeather")))

            assertThat(sentBody()["tool_choice"].toString()).isEqualTo("""{"type":"auto"}""")
            assertThat(response.warnings.map { it.setting }).containsExactly("toolChoice")
        }

        @Test
        fun `and on any model that is thinking to a budget, which is the other way it is refused`() {
            val request = requestWith(ToolChoice.Required)
                .copy(settings = ChatSettings(reasoning = Reasoning.budget(2_000)))

            val response = generateWith("claude-sonnet-4-5", request)

            assertThat(sentBody().path("thinking.type")?.asString()).isEqualTo("enabled")
            assertThat(sentBody()["tool_choice"].toString()).isEqualTo("""{"type":"auto"}""")
            assertThat(response.warnings.map { it.setting }).containsExactly("toolChoice")
        }

        @Test
        fun `but not while it thinks adaptively, which is the shape the newest models take`() {
            val request = requestWith(ToolChoice.Required)
                .copy(settings = ChatSettings(reasoning = Reasoning.effort(ReasoningEfforts.High)))

            val response = generateWith("claude-opus-5", request)

            assertThat(sentBody()["tool_choice"].toString()).isEqualTo("""{"type":"any"}""")
            assertThat(response.warnings).isEmpty()
        }
    }

    @Nested
    inner class `holding a tool to its schema` {
        @Test
        fun `is asked for where the model compiles the grammar`() {
            generateWith("claude-sonnet-4-5", requestWith(ToolChoice.Auto))

            assertThat(sentBody()["tools"]!!.asArray()!![0].asObject()!!["strict"]?.asBoolean()).isTrue()
        }

        @Test
        fun `and the tool still goes without it where it does not, because half a tool beats none`() {
            val response = generateWith("claude-sonnet-4", requestWith(ToolChoice.Auto))

            val tool = sentBody()["tools"]!!.asArray()!![0].asObject()!!
            assertThat(tool.containsKey("strict")).isFalse()
            assertThat(tool["name"]?.asString()).isEqualTo("getWeather")
            assertThat(response.warnings.map { it.setting }).containsExactly("tools")
        }
    }

    @Nested
    inner class `a model nobody described` {
        @Test
        fun `stands in the newest one, because that is what a model that just came out is`() {
            generate("claude-sonnet-9", ChatSettings(reasoning = Reasoning.effort(ReasoningEfforts.High)))

            assertThat(sentBody().path("output_config.effort")?.asString()).isEqualTo("high")
            assertThat(sentBody()["max_tokens"]?.asInt()).isEqualTo(128_000)
        }

        @Test
        fun `and what it decides out of that says it was a guess`() {
            val response = generate("claude-sonnet-9", ChatSettings(temperature = 0.2))

            assertThat(sentBody().containsKey("temperature")).isFalse()
            assertThat(response.warnings.single().message)
                .contains("add claude-sonnet-9 to it if it takes more")
        }
    }

    @Nested
    inner class `the options of the provider` {
        @Test
        fun `send a level the portable setting does not reach`() {
            generateWith(
                "claude-opus-5",
                ChatRequest(
                    listOf(Message.user("Hola")),
                    providerOptions = ProviderOptions.of(AnthropicOptions(effort = AnthropicEfforts.Max)),
                ),
            )

            assertThat(sentBody().path("output_config.effort")?.asString()).isEqualTo("max")
        }

        @Test
        fun `and win over it, so whoever knows their model gets exactly what they asked for`() {
            generateWith(
                "claude-opus-5",
                ChatRequest(
                    listOf(Message.user("Hola")),
                    settings = ChatSettings(reasoning = Reasoning.effort(ReasoningEfforts.Low)),
                    providerOptions = ProviderOptions.of(AnthropicOptions(effort = AnthropicEfforts.XHigh)),
                ),
            )

            assertThat(sentBody().path("output_config.effort")?.asString()).isEqualTo("xhigh")
        }

        @Test
        fun `reach a budget on a model the table says does not take one`() {
            generateWith(
                "claude-opus-5",
                ChatRequest(
                    listOf(Message.user("Hola")),
                    providerOptions = ProviderOptions.of(
                        AnthropicOptions(thinking = AnthropicThinking.Budget(8_000)),
                    ),
                ),
            )

            // Asked for on purpose and sent on purpose: the table guides the plain api, not this
            assertThat(sentBody().path("thinking.budget_tokens")?.asInt()).isEqualTo(8_000)
        }
    }

    @Nested
    inner class `the cache` {
        @Test
        fun `is off unless the application asked for it, because a write costs more than a plain call`() {
            cached(AnthropicCache.Off)

            assertThat(httpClient.requestBody).doesNotContain("cache_control")
        }

        @Test
        fun `of the system prompt is a mark at its end, which is what a long prompt shared by every call needs`() {
            cached(AnthropicCache(system = true))

            assertThat(sentBody()["system"].toString()).isEqualTo(
                """[{"type":"text","text":"Sos el asistente de una ferreteria","cache_control":{"type":"ephemeral"}}]"""
            )
            assertThat(sentBody().containsKey("cache_control")).isFalse()
        }

        @Test
        fun `and with no system prompt there is nothing to mark, and nothing fails`() {
            val config = AnthropicConfig(apiKey = "sk-ant-test", cache = AnthropicCache(system = true))

            AnthropicChatModel("claude-opus-5", config, httpClient).generate(ChatRequest(Message.user("Hola")))

            assertThat(httpClient.requestBody).doesNotContain("cache_control")
        }

        @Test
        fun `of the tools is a mark on the last one, so all of them are cached`() {
            cached(AnthropicCache(tools = true))

            val tools = sentBody()["tools"]!!.asArray()!!

            assertThat(tools[0].asObject()!!.containsKey("cache_control")).isFalse()
            assertThat(tools[1].asObject()!!["cache_control"].toString()).isEqualTo("""{"type":"ephemeral"}""")
        }

        @Test
        fun `of the conversation is Anthropic's own mark, which moves forward as the conversation grows`() {
            cached(AnthropicCache(conversation = true))

            assertThat(sentBody()["cache_control"].toString()).isEqualTo("""{"type":"ephemeral"}""")
            assertThat(sentBody()["system"]?.asString()).isEqualTo("Sos el asistente de una ferreteria")
        }

        @Test
        fun `and the three go together, each one a mark of its own`() {
            cached(AnthropicCache(system = true, tools = true, conversation = true))

            assertThat(httpClient.requestBody!!.split("cache_control").size - 1).isEqualTo(3)
        }

        @Test
        fun `can be kept for an hour instead of five minutes, which applies to every mark`() {
            cached(AnthropicCache(system = true, conversation = true, ttl = AnthropicCacheTtl.OneHour))

            assertThat(sentBody()["cache_control"].toString()).isEqualTo("""{"type":"ephemeral","ttl":"1h"}""")
            assertThat(sentBody()["system"].toString()).contains(""""cache_control":{"type":"ephemeral","ttl":"1h"}""")
        }

        @Test
        fun `a call can ask for another than the one the application set`() {
            val config = AnthropicConfig(apiKey = "sk-ant-test", cache = AnthropicCache(conversation = true))
            val options = ProviderOptions.of(AnthropicOptions(cache = AnthropicCache(system = true)))

            AnthropicChatModel("claude-opus-5", config, httpClient)
                .generate(ChatRequest(listOf(system, Message.user("Hola")), providerOptions = options))

            assertThat(sentBody().containsKey("cache_control")).isFalse()
            assertThat(sentBody()["system"].toString()).contains("cache_control")
        }

        @Test
        fun `and a cut somewhere precise is marked on the part it goes after`() {
            val marked = TextPart(
                "el contrato entero",
                dev.botta.trantor.ai.providers.ProviderMetadata.of(
                    "anthropic",
                    Json.obj("cache_control" to Json.obj("type" to "ephemeral")),
                ),
            )

            generateWith("claude-opus-5", ChatRequest(listOf(Message.User(listOf(marked)))))

            assertThat(httpClient.requestBody)
                .contains("""{"type":"text","text":"el contrato entero","cache_control":{"type":"ephemeral"}}""")
        }

        /** A system prompt, two tools and a question: something for every mark to land on. */
        private fun cached(cache: AnthropicCache) {
            val config = AnthropicConfig(apiKey = "sk-ant-test", cache = cache)
            val tools = listOf("getWeather", "getTime").map {
                FunctionToolSpec(name = it, parameters = Json.obj("type" to "object"))
            }

            AnthropicChatModel("claude-opus-5", config, httpClient)
                .generate(ChatRequest(listOf(system, Message.user("Hola")), tools = tools))
        }

        private val system = Message.system("Sos el asistente de una ferreteria")
    }

    @Nested
    inner class `a system message in the middle` {
        @Test
        fun `stays where it was on a model that takes it, so the cached prefix survives`() {
            generateWith("claude-opus-5", withSystemInTheMiddle)

            assertThat(sentBody()["system"]?.asString()).isEqualTo("Sos un asistente")
            assertThat(sentBody()["messages"]?.asArray()?.get(1).toString())
                .isEqualTo("""{"role":"system","content":[{"type":"text","text":"Se breve"}]}""")
        }

        @Test
        fun `is joined into the system field on a model that does not, and says so`() {
            val response = generateWith("claude-sonnet-4-5", withSystemInTheMiddle)

            assertThat(sentBody()["system"]?.asString()).isEqualTo("Sos un asistente\n\nSe breve")
            assertThat(sentBody()["messages"]?.asArray()?.size).isEqualTo(1)
            assertThat(response.warnings.map { it.message }).containsExactly(
                "claude-sonnet-4-5 does not take system messages in the middle of the conversation, " +
                    "so the 2 of them were joined into the system prompt",
            )
        }

        @Test
        fun `and a model nobody described is taken to be the newest, which takes it`() {
            generateWith("claude-sonnet-9", withSystemInTheMiddle)

            assertThat(sentBody()["messages"]?.asArray()?.get(1)?.asObject()?.get("role")?.asString())
                .isEqualTo("system")
        }

        private val withSystemInTheMiddle =
            ChatRequest(Message.system("Sos un asistente"), Message.user("Hola"), Message.system("Se breve"))
    }

    @Nested
    inner class `the dynamic system prompt` {
        @Test
        fun `goes last, as a system message, on a model that takes one in the middle`() {
            generateWith("claude-opus-5", withDynamicSystem)

            assertThat(sentBody()["system"]?.asString()).isEqualTo("Sos el asistente de una ferreteria")
            assertThat(sentBody()["messages"]?.asArray()?.last().toString())
                .isEqualTo("""{"role":"system","content":[{"type":"text","text":"Hoy es martes"}]}""")
        }

        @Test
        fun `goes under the system prompt, as a block of its own, on a model that does not`() {
            generateWith("claude-sonnet-4-5", withDynamicSystem)

            assertThat(sentBody()["system"].toString()).isEqualTo(
                """[{"type":"text","text":"Sos el asistente de una ferreteria"},""" +
                    """{"type":"text","text":"Hoy es martes"}]"""
            )
            assertThat(sentBody()["messages"]?.asArray()?.size).isEqualTo(1)
        }

        @Test
        fun `and there the mark of the system cache goes between the two, so a change does not undo it`() {
            cachedWith("claude-sonnet-4-5", AnthropicCache(system = true), withDynamicSystem)

            assertThat(sentBody()["system"].toString()).isEqualTo(
                """[{"type":"text","text":"Sos el asistente de una ferreteria",""" +
                    """"cache_control":{"type":"ephemeral"}},{"type":"text","text":"Hoy es martes"}]"""
            )
        }

        @Test
        fun `with no stable system prompt there is nothing to mark`() {
            val onlyDynamic = withDynamicSystem.copy(messages = listOf(hola))

            cachedWith("claude-sonnet-4-5", AnthropicCache(system = true), onlyDynamic)

            assertThat(sentBody()["system"]?.asString()).isEqualTo("Hoy es martes")
            assertThat(httpClient.requestBody).doesNotContain("cache_control")
        }

        @Test
        fun `the conversation mark goes on the last block before it, which Anthropic's own mark would not`() {
            cachedWith("claude-opus-5", AnthropicCache(conversation = true), withDynamicSystem)

            val messages = sentBody()["messages"]!!.asArray()!!

            assertThat(sentBody().containsKey("cache_control")).isFalse()
            assertThat(messages[0].toString()).isEqualTo(
                """{"role":"user","content":[{"type":"text","text":"Hola","cache_control":{"type":"ephemeral"}}]}"""
            )
            assertThat(messages.last().toString()).doesNotContain("cache_control")
        }

        @Test
        fun `and it keeps the duration of the cache`() {
            val cache = AnthropicCache(conversation = true, ttl = AnthropicCacheTtl.OneHour)

            cachedWith("claude-opus-5", cache, withDynamicSystem)

            assertThat(sentBody()["messages"]!!.asArray()!![0].toString())
                .contains(""""cache_control":{"type":"ephemeral","ttl":"1h"}""")
        }

        /**
         * Two turns recorded with the long system prompt of the cache recordings and the time as the dynamic part,
         * which changed between them. What the second one read back is what the whole thing is for.
         */
        @Test
        fun `recorded where it goes last, the second turn reads back everything but the dynamic part`() {
            val next = twoRecordedTurns("claude-opus-5", "dynamic-system")

            assertThat(sentBody()["messages"]!!.asArray()!!.last().asObject()!!["role"]?.asString())
                .isEqualTo("system")
            assertThat(next.usage.cacheReadTokens).isEqualTo(8_157)
            assertThat(next.usage.cacheWriteTokens).isEqualTo(123)
            assertThat(next.usage.uncachedInputTokens).isEqualTo(24)
        }

        @Test
        fun `recorded where it goes under the system prompt, only the system prompt is read back`() {
            val next = twoRecordedTurns("claude-sonnet-4-5", "dynamic-system-fallback")

            assertThat(sentBody()["system"]!!.asArray()).hasSize(2)
            assertThat(next.usage.cacheReadTokens).isEqualTo(7_230)
            assertThat(next.usage.cacheWriteTokens).isEqualTo(103)
        }

        private fun twoRecordedTurns(modelId: String, recording: String): ChatResponse {
            val cache = AnthropicCache(system = true, conversation = true)
            val model = AnthropicChatModel(modelId, AnthropicConfig(apiKey = "sk-ant-test", cache = cache), httpClient)
            httpClient.answers(fixture("$recording-1"), fixture("$recording-2"))

            val answer = model.generate(withDynamicSystem)

            return model.generate(
                withDynamicSystem.copy(
                    messages = withDynamicSystem.messages + answer.asMessage() + Message.user("Y el 17?"),
                    dynamicSystem = "Son las 11",
                ),
            )
        }

        private fun fixture(name: String) =
            javaClass.getResource("/anthropic/$name.json")?.readText() ?: error("Missing fixture $name")

        private fun cachedWith(modelId: String, cache: AnthropicCache, request: ChatRequest) {
            val config = AnthropicConfig(apiKey = "sk-ant-test", cache = cache)

            AnthropicChatModel(modelId, config, httpClient).generate(request)
        }

        private val hola = Message.user("Hola")
        private val withDynamicSystem = ChatRequest(
            messages = listOf(Message.system("Sos el asistente de una ferreteria"), hola),
            dynamicSystem = "Hoy es martes",
        )
    }

    /**
     * Claude Opus 5.5 and Fable 5.1 tie each thinking block to everything that came before it, and answer 400 when
     * that changed. The dynamic part moves to the end on every call, which is such a change for the thinking of the
     * call before, so the copy that was there is put back where it was, turned off.
     */
    @Nested
    inner class `on a model that ties its thinking to what came before it` {
        @Test
        fun `the dynamic part goes last as a system message that lasts one turn, with the beta it takes`() {
            generateWith("claude-opus-5-5", request())

            assertThat(sentBody()["messages"]!!.asArray()!!.last().toString()).isEqualTo(turnScoped("Son las 10"))
            assertThat(httpClient.request?.headers?.get("anthropic-beta"))
                .isEqualTo("mid-conversation-system-clear-at-2026-08-21")
        }

        @Test
        fun `the thinking of the answer keeps the dynamic part it was produced with`() {
            httpClient.body = fixture("bound-thinking-2")

            val answer = generateWith("claude-opus-5-5", request())

            assertThat(stampOf(answer.content)).isEqualTo("Son las 10")
        }

        @Test
        fun `and the next call puts that copy back before the answer, where it was`() {
            httpClient.body = fixture("bound-thinking-2")
            val answer = generateWith("claude-opus-5-5", request())
            val history = listOf(question, answer.asMessage(), Message.user("Y en Lima?"))

            generateWith("claude-opus-5-5", request("Son las 11", history))

            val messages = sentBody()["messages"]!!.asArray()!!.map { it.asObject()!! }
            assertThat(messages.map { it["role"]?.asString() })
                .containsExactly("user", "system", "assistant", "user", "system")
            assertThat(messages[1].toString()).isEqualTo(turnScoped("Son las 10"))
            assertThat(messages[4].toString()).isEqualTo(turnScoped("Son las 11"))
        }

        @Test
        fun `an answer that carries none goes back as it came`() {
            val history = listOf(question, Message.assistant("Hacen 7 grados"), Message.user("Y en Lima?"))

            generateWith("claude-opus-5-5", request(history = history))

            assertThat(sentBody()["messages"]!!.asArray()!!.map { it.asObject()!!["role"]?.asString() })
                .containsExactly("user", "assistant", "user", "system")
        }

        @Test
        fun `a streamed answer keeps it too`() {
            httpClient.body = fixture("stream-thinking", "txt")
            val model = AnthropicChatModel("claude-opus-5-5", AnthropicConfig(apiKey = "sk-ant-test"), httpClient)

            val (done, response) = model.stream(request()).use { stream ->
                val parts = stream.asSequence().filterIsInstance<StreamPart.PartDone>().map { it.part }.toList()

                parts to stream.response()
            }

            assertThat(stampOf(done)).isEqualTo("Son las 10")
            assertThat(stampOf(response.content)).isEqualTo("Son las 10")
        }

        @Test
        fun `a model that does not tie its thinking gets none of this`() {
            httpClient.body = fixture("bound-thinking-2")

            val answer = generateWith("claude-opus-5", request())

            assertThat(sentBody()["messages"]!!.asArray()!!.last().toString()).doesNotContain("clear_at")
            assertThat(httpClient.request?.headers).doesNotContainKey("anthropic-beta")
            assertThat(stampOf(answer.content)).isNull()
            assertThat(setupOf(answer.content)).isNull()
        }

        @Test
        fun `and neither does a call without a dynamic part`() {
            httpClient.body = fixture("bound-thinking-2")

            val answer = generateWith("claude-opus-5-5", request(dynamic = null))

            assertThat(httpClient.requestBody).doesNotContain("clear_at")
            assertThat(httpClient.request?.headers).doesNotContainKey("anthropic-beta")
            assertThat(stampOf(answer.content)).isNull()
        }

        @Test
        fun `the thinking of the answer remembers the system prompt and tools it was produced under`() {
            httpClient.body = fixture("bound-thinking-2")

            val answer = generateWith("claude-opus-5-5", request(history = listOf(Message.system("Sos soporte"), question)))
            val other = generateWith("claude-opus-5-5", request(history = listOf(Message.system("Sos ventas"), question)))

            assertThat(setupOf(answer.content)).isNotBlank().isNotEqualTo(setupOf(other.content))
        }

        @Test
        fun `thinking produced under another system prompt is left out, since Anthropic would refuse it`() {
            // What a handoff does: the agent that answers next has other instructions and other tools
            httpClient.body = fixture("bound-thinking-2")
            val support = Message.system("Sos soporte")
            val first = generateWith("claude-opus-5-5", request(history = listOf(support, question)))
            val second = generateWith(
                "claude-opus-5-5",
                request(history = listOf(support, question, first.asMessage(), Message.user("Y en Lima?"))),
            )
            val history = listOf(
                Message.system("Sos ventas"), question, first.asMessage(), Message.user("Y en Lima?"),
                second.asMessage(), Message.user("Te paso con ventas"),
            )

            val result = generateWith("claude-opus-5-5", request(history = history))

            assertThat(blockTypesOf("assistant")).containsExactly(listOf("text"), listOf("text"))
            assertThat(result.warnings.map { it.message }).contains(
                "Thinking produced under another system prompt or other tools was left out: claude-opus-5-5 ties " +
                    "each thinking block to what came before it, and would refuse it",
            )
        }

        @Test
        fun `and so is the one under other tools`() {
            httpClient.body = fixture("bound-thinking-2")
            val before = generateWith("claude-opus-5-5", request())
            val history = listOf(question, before.asMessage(), Message.user("Y en Lima?"))

            generateWith("claude-opus-5-5", request(history = history).copy(tools = listOf(weatherSpec)))

            assertThat(blockTypesOf("assistant")).containsExactly(listOf("text"))
        }

        @Test
        fun `only up to the last one that changed, so the thinking produced after it stays`() {
            // Anthropic takes thinking left out from the start, not from the middle
            httpClient.body = fixture("bound-thinking-2")
            val support = Message.system("Sos soporte")
            val sales = Message.system("Sos ventas")
            val bySupport = generateWith("claude-opus-5-5", request(history = listOf(support, question)))
            val bySales = generateWith(
                "claude-opus-5-5",
                request(history = listOf(sales, question, bySupport.asMessage(), Message.user("Y en Lima?"))),
            )
            val history = listOf(
                sales, question, bySupport.asMessage(), Message.user("Y en Lima?"), bySales.asMessage(),
                Message.user("Y en Quito?"),
            )

            generateWith("claude-opus-5-5", request(history = history))

            assertThat(blockTypesOf("assistant")).containsExactly(listOf("text"), listOf("thinking", "text"))
        }

        @Test
        fun `and a block that still fits goes too when one after it changed, since none can be left out of the middle`() {
            // Sales hands over to support and support back to sales: the first thinking of sales still fits, but
            // keeping it would leave out the one of support from the middle
            httpClient.body = fixture("bound-thinking-2")
            val support = Message.system("Sos soporte")
            val sales = Message.system("Sos ventas")
            val bySales = generateWith("claude-opus-5-5", request(history = listOf(sales, question)))
            val bySupport = generateWith(
                "claude-opus-5-5",
                request(history = listOf(support, question, bySales.asMessage(), Message.user("Y en Lima?"))),
            )
            val history = listOf(
                sales, question, bySales.asMessage(), Message.user("Y en Lima?"), bySupport.asMessage(),
                Message.user("Y en Quito?"),
            )

            generateWith("claude-opus-5-5", request(history = history))

            assertThat(blockTypesOf("assistant")).containsExactly(listOf("text"), listOf("text"))
        }

        @Test
        fun `the same system prompt and tools keep all of it`() {
            httpClient.body = fixture("bound-thinking-2")
            val before = generateWith("claude-opus-5-5", request())
            val history = listOf(question, before.asMessage(), Message.user("Y en Lima?"))

            val result = generateWith("claude-opus-5-5", request(history = history))

            assertThat(blockTypesOf("assistant")).containsExactly(listOf("thinking", "text"))
            assertThat(result.warnings).isEmpty()
        }

        private fun request(dynamic: String? = "Son las 10", history: List<Message> = listOf(question)) =
            ChatRequest(history, dynamicSystem = dynamic)

        private fun blockTypesOf(role: String) = sentBody()["messages"]!!.asArray()!!.map { it.asObject()!! }
            .filter { it["role"]?.asString() == role }
            .map { message -> message["content"]!!.asArray()!!.map { it.asObject()!!["type"]?.asString() } }

        private fun setupOf(parts: List<Part>) = parts.filterIsInstance<ReasoningPart>().single()
            .metadata["anthropic"]?.get("setup")?.asString()

        private val weatherSpec = FunctionToolSpec(
            name = "getWeather",
            parameters = Json.obj("type" to "object", "properties" to Json.obj("city" to Json.obj("type" to "string"))),
        )

        private fun stampOf(parts: List<Part>) = parts.filterIsInstance<ReasoningPart>().single()
            .metadata["anthropic"]?.get("dynamic_system")?.asString()

        private fun turnScoped(text: String) =
            """{"role":"system","content":[{"type":"text","text":"$text"}],"clear_at":"next_user_message"}"""

        private fun fixture(name: String, extension: String = "json") =
            javaClass.getResource("/anthropic/$name.$extension")?.readText() ?: error("Missing fixture $name")

        private val question = Message.user("Que temperatura hay en Bariloche?")
    }

    @Serializable
    private data class Answer(val city: String)

    private fun jsonOutput() = OutputSpec.Json(JsonSchemas.of<Answer>())

    private fun requestWith(choice: ToolChoice) = ChatRequest(
        messages = listOf(Message.user("Que temperatura hay en Bariloche?")),
        tools = listOf(
            FunctionToolSpec(
                name = "getWeather",
                parameters = Json.obj(
                    "type" to "object",
                    "properties" to Json.obj("city" to Json.obj("type" to "string")),
                ),
            )
        ),
        toolChoice = choice,
    )

    private fun generate(modelId: String, settings: ChatSettings) =
        generateWith(modelId, ChatRequest(listOf(Message.user("Hola")), settings = settings))

    private fun generateWith(modelId: String, request: ChatRequest) =
        AnthropicChatModel(modelId, AnthropicConfig(apiKey = "sk-ant-test"), httpClient).generate(request)

    private fun sentBody() = Json.parse(httpClient.requestBody!!).asObject()!!

    private val httpClient = FakeHttpClient()
}
