@file:Suppress("ClassName")

package dev.botta.trantor.ai.providers.anthropic

import dev.botta.json.Json
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.ai.providers.ProviderOptions
import dev.botta.trantor.ai.schemas.JsonSchemas
import dev.botta.trantor.ai.testing.FakeHttpClient
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
    inner class `a model the table does not know` {
        @Test
        fun `is assumed to be newer than everything in it, because that is what a new Claude is`() {
            generate("claude-sonnet-9", ChatSettings(reasoning = Reasoning.effort(ReasoningEfforts.High)))

            assertThat(sentBody().path("output_config.effort")?.asString()).isEqualTo("high")
        }

        @Test
        fun `and what it was asked for is not shrunk by a ceiling we guessed`() {
            val response = generate("claude-sonnet-9", ChatSettings(maxOutputTokens = 500_000))

            assertThat(sentBody()["max_tokens"]?.asInt()).isEqualTo(500_000)
            assertThat(response.warnings).isEmpty()
        }

        @Test
        fun `while something that is not Claude at all gets the careful guess`() {
            // An Anthropic compatible server: a field it never heard of is a failed call, one less is a plainer answer
            generate("llama-3-70b", ChatSettings(temperature = 0.2))

            assertThat(sentBody()["temperature"]?.asDouble()).isEqualTo(0.2)
            assertThat(sentBody()["max_tokens"]?.asInt()).isEqualTo(4_096)
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
            generate("claude-opus-5", ChatSettings())

            assertThat(sentBody().containsKey("cache_control")).isFalse()
        }

        @Test
        fun `is one flag, and Anthropic moves the cut as the conversation grows`() {
            val config = AnthropicConfig(apiKey = "sk-ant-test", cache = AnthropicCaches.Automatic)

            AnthropicChatModel("claude-opus-5", config, httpClient).generate(ChatRequest(Message.user("Hola")))

            assertThat(sentBody()["cache_control"].toString()).isEqualTo("""{"type":"ephemeral"}""")
        }

        @Test
        fun `can be kept for an hour instead of five minutes`() {
            val config = AnthropicConfig(apiKey = "sk-ant-test", cache = AnthropicCaches.AutomaticForAnHour)

            AnthropicChatModel("claude-opus-5", config, httpClient).generate(ChatRequest(Message.user("Hola")))

            assertThat(sentBody()["cache_control"].toString()).isEqualTo("""{"type":"ephemeral","ttl":"1h"}""")
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
    }

    @Nested
    inner class `a system message in the middle` {
        @Test
        fun `is joined into the system field by default, which every model takes`() {
            generateWith(
                "claude-opus-5",
                ChatRequest(Message.system("Sos un asistente"), Message.user("Hola"), Message.system("Se breve")),
            )

            assertThat(sentBody()["system"]?.asString()).isEqualTo("Sos un asistente\n\nSe breve")
            assertThat(sentBody()["messages"]?.asArray()?.size).isEqualTo(1)
        }

        @Test
        fun `and stays where it was when the application turned that on, so the cached prefix survives`() {
            val config = AnthropicConfig(apiKey = "sk-ant-test", midConversationSystemMessages = true)
            val request =
                ChatRequest(Message.system("Sos un asistente"), Message.user("Hola"), Message.system("Se breve"))

            AnthropicChatModel("claude-opus-5", config, httpClient).generate(request)

            assertThat(sentBody()["system"]?.asString()).isEqualTo("Sos un asistente")
            assertThat(sentBody()["messages"]?.asArray()?.get(1).toString())
                .isEqualTo("""{"role":"system","content":[{"type":"text","text":"Se breve"}]}""")
        }
    }

    @Serializable
    private data class Answer(val city: String)

    private fun jsonOutput() = OutputSpec.Json(JsonSchemas.of<Answer>())

    private fun generate(modelId: String, settings: ChatSettings) =
        generateWith(modelId, ChatRequest(listOf(Message.user("Hola")), settings = settings))

    private fun generateWith(modelId: String, request: ChatRequest) =
        AnthropicChatModel(modelId, AnthropicConfig(apiKey = "sk-ant-test"), httpClient).generate(request)

    private fun sentBody() = Json.parse(httpClient.requestBody!!).asObject()!!

    private val httpClient = FakeHttpClient()
}
