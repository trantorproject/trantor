@file:Suppress("ClassName")

package dev.botta.trantor.ai.providers.openai

import dev.botta.json.Json
import dev.botta.trantor.ai.errors.InvalidProviderOptionError
import dev.botta.trantor.ai.errors.UnsupportedRequestError
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.ai.providers.ProviderOption
import dev.botta.trantor.ai.providers.ProviderOptions
import dev.botta.trantor.ai.providers.RawOptions
import dev.botta.trantor.ai.testing.FakeHttpClient
import dev.botta.trantor.ai.tools.ProviderToolSpec
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class OpenAIChatModelOptionsTest {
    @Nested
    inner class `typed options` {
        @Test
        fun `go into the body with the names OpenAI uses`() {
            val options = OpenAIOptions(
                serviceTier = OpenAIServiceTiers.Flex,
                promptCacheKey = "tenant-7",
                safetyIdentifier = "user-42",
                truncation = OpenAITruncations.Auto,
            )

            generateWith(options)

            assertThat(sentBody()["service_tier"]?.asString()).isEqualTo("flex")
            assertThat(sentBody()["prompt_cache_key"]?.asString()).isEqualTo("tenant-7")
            assertThat(sentBody()["safety_identifier"]?.asString()).isEqualTo("user-42")
            assertThat(sentBody()["truncation"]?.asString()).isEqualTo("auto")
        }

        @Test
        fun `only send what was set`() {
            generateWith(OpenAIOptions(serviceTier = OpenAIServiceTiers.Priority))

            assertThat(sentBody().containsKey("truncation")).isFalse()
            assertThat(sentBody().containsKey("prompt_cache_key")).isFalse()
        }

        @Test
        fun `several add up in order, and what a later one sets wins`() {
            // An agent brings its options and the run its own after them
            val options = ProviderOptions.of(
                OpenAIOptions(serviceTier = OpenAIServiceTiers.Flex, promptCacheKey = "del-agente"),
                OpenAIOptions(promptCacheKey = "del-run"),
            )

            model.generate(requestWith(options))

            assertThat(sentBody()["prompt_cache_key"]?.asString()).isEqualTo("del-run")
            assertThat(sentBody()["service_tier"]?.asString()).isEqualTo("flex")
        }

        @Test
        fun `verbosity travels inside text, where OpenAI takes it`() {
            generateWith(OpenAIOptions(verbosity = OpenAIVerbosities.Low))

            assertThat(sentBody()["text"].toString()).isEqualTo("""{"verbosity":"low"}""")
        }

        @Test
        fun `verbosity does not step on the output format`() {
            val request = ChatRequest(
                messages = listOf(Message.user("Hola")),
                output = OutputSpec.Json(Json.obj("type" to "object"), name = "answer"),
                providerOptions = ProviderOptions.of(OpenAIOptions(verbosity = OpenAIVerbosities.High)),
            )

            model.generate(request)

            assertThat(sentBody().path("text.format.name")?.asString()).isEqualTo("answer")
            assertThat(sentBody().path("text.verbosity")?.asString()).isEqualTo("high")
        }

        @Test
        fun `store of the call wins over store of the config`() {
            val model = OpenAIChatModel("gpt-4.1-mini", OpenAIConfig(apiKey = "sk-test", store = false), httpClient)

            model.generate(requestWith(OpenAIOptions(store = true)))

            assertThat(sentBody()["store"]?.asBoolean()).isTrue()
        }
    }

    @Nested
    inner class `raw options` {
        @Test
        fun `go into the body as they were written`() {
            generateWith(RawOptions("openai", Json.obj("top_logprobs" to 3, "background" to false)))

            assertThat(sentBody()["top_logprobs"]?.asInt()).isEqualTo(3)
            assertThat(sentBody()["background"]?.asBoolean()).isFalse()
        }

        @Test
        fun `a key the adapter already filled in is a conflict, not an override`() {
            assertThatThrownBy { generateWith(RawOptions("openai", Json.obj("model" to "gpt-4o"))) }
                .isInstanceOfSatisfying(InvalidProviderOptionError::class.java) {
                    assertThat(it.option).isEqualTo("model")
                    assertThat(it.provider).isEqualTo("openai")
                }
        }

        @Test
        fun `a setting that was not used is not a conflict`() {
            generateWith(RawOptions("openai", Json.obj("temperature" to 0.2)))

            assertThat(sentBody()["temperature"]?.asDouble()).isEqualTo(0.2)
        }

        @Test
        fun `the same setting filled in by both is a conflict`() {
            val request = ChatRequest(
                messages = listOf(Message.user("Hola")),
                settings = ChatSettings(temperature = 0.9),
                providerOptions = ProviderOptions.of(RawOptions("openai", Json.obj("temperature" to 0.2))),
            )

            assertThatThrownBy { model.generate(request) }
                .isInstanceOf(InvalidProviderOptionError::class.java)
                .hasMessageContaining("temperature")
        }

        @Test
        fun `add a field next to one the adapter wrote`() {
            val request = ChatRequest(
                messages = listOf(Message.user("Hola")),
                output = OutputSpec.Json(Json.obj("type" to "object"), name = "answer"),
                providerOptions = ProviderOptions.of(
                    RawOptions("openai", Json.obj("text" to Json.obj("verbosity" to "low"))),
                ),
            )

            model.generate(request)

            assertThat(sentBody().path("text.format.name")?.asString()).isEqualTo("answer")
            assertThat(sentBody().path("text.verbosity")?.asString()).isEqualTo("low")
        }

        @Test
        fun `add to a list the adapter wrote`() {
            // A reasoning model, because that is the one the adapter writes an include for
            val model = OpenAIChatModel("o4-mini", OpenAIConfig(apiKey = "sk-test"), httpClient)
            val request = ChatRequest(
                messages = listOf(Message.user("Hola")),
                settings = ChatSettings(reasoning = Reasoning.effort(ReasoningEfforts.Low)),
                providerOptions = ProviderOptions.of(
                    RawOptions("openai", Json.obj("include" to Json.array("message.output_text.logprobs"))),
                ),
            )

            model.generate(request)

            assertThat(sentBody()["include"]?.asArray()?.map { it.asString() })
                .containsExactly("reasoning.encrypted_content", "message.output_text.logprobs")
        }

        @Test
        fun `only the value that is really in the way is a conflict`() {
            val request = ChatRequest(
                messages = listOf(Message.user("Hola")),
                output = OutputSpec.Json(Json.obj("type" to "object"), name = "answer"),
                providerOptions = ProviderOptions.of(
                    RawOptions("openai", Json.obj("text" to Json.obj("format" to "whatever"))),
                ),
            )

            assertThatThrownBy { model.generate(request) }
                .isInstanceOfSatisfying(InvalidProviderOptionError::class.java) {
                    assertThat(it.option).isEqualTo("text.format")
                }
        }

        @Test
        fun `a typed option is what conflicts, whatever order they came in`() {
            val options = ProviderOptions.of(
                RawOptions("openai", Json.obj("service_tier" to "flex")),
                OpenAIOptions(serviceTier = OpenAIServiceTiers.Priority),
            )

            assertThatThrownBy { model.generate(requestWith(options)) }
                .isInstanceOf(InvalidProviderOptionError::class.java)
                .hasMessageContaining("service_tier")
        }
    }

    @Nested
    inner class `options of someone else` {
        @Test
        fun `are dropped with a warning`() {
            val response = generateWith(RawOptions("anthropic", Json.obj("thinking" to "enabled")))

            assertThat(sentBody().containsKey("thinking")).isFalse()
            assertThat(response.warnings.map { it.message })
                .containsExactly("Options for anthropic are not OpenAI options and were dropped")
        }

        @Test
        fun `an option of the provider that this adapter does not know is a warning too`() {
            val response = generateWith(UnknownOpenAIOption)

            assertThat(response.warnings.map { it.message })
                .containsExactly("UnknownOpenAIOption is not an option this adapter knows")
        }
    }

    @Nested
    inner class `failing on warnings` {
        @Test
        fun `turns a dropped setting into an error`() {
            val request = ChatRequest(
                messages = listOf(Message.user("Hola")),
                settings = ChatSettings(seed = 7, failOnWarnings = true),
            )

            assertThatThrownBy { model.generate(request) }
                .isInstanceOfSatisfying(UnsupportedRequestError::class.java) {
                    assertThat(it.warnings.map { warning -> warning.setting }).containsExactly("seed")
                }
                .hasMessageContaining("openai could not honor the request")
        }

        @Test
        fun `catches a dropped tool as well, not only settings`() {
            val request = ChatRequest(
                messages = listOf(Message.user("Hola")),
                tools = listOf(ProviderToolSpec("anthropic.computer", Json.obj())),
                settings = ChatSettings(failOnWarnings = true),
            )

            assertThatThrownBy { model.generate(request) }
                .isInstanceOf(UnsupportedRequestError::class.java)
                .hasMessageContaining("anthropic.computer")
        }

        @Test
        fun `a request the adapter can honor goes through`() {
            val request = ChatRequest(
                messages = listOf(Message.user("Hola")),
                settings = ChatSettings(temperature = 0.5, failOnWarnings = true),
            )

            val response = model.generate(request)

            assertThat(response.warnings).isEmpty()
            assertThat(response.text).isEqualTo("Hola, ¿en qué puedo ayudarte?")
        }

        @Test
        fun `is off by default, so a lost setting does not lose the answer`() {
            val response = model.generate(
                ChatRequest(listOf(Message.user("Hola")), settings = ChatSettings(seed = 7))
            )

            assertThat(response.warnings).hasSize(1)
            assertThat(response.text).isEqualTo("Hola, ¿en qué puedo ayudarte?")
        }

        @Test
        fun `it also applies while streaming`() {
            httpClient.body = fixture("chat/stream-text.txt")
            val request = ChatRequest(
                messages = listOf(Message.user("Hola")),
                settings = ChatSettings(seed = 7, failOnWarnings = true),
            )

            assertThatThrownBy { model.stream(request) }.isInstanceOf(UnsupportedRequestError::class.java)
        }
    }

    private fun generateWith(option: ProviderOption) = model.generate(requestWith(ProviderOptions.of(option)))

    private fun requestWith(option: ProviderOption) = requestWith(ProviderOptions.of(option))

    private fun requestWith(options: ProviderOptions) =
        ChatRequest(listOf(Message.user("Hola")), providerOptions = options)

    private fun sentBody() = Json.parse(httpClient.requestBody!!).asObject()!!

    private fun fixture(name: String) =
        javaClass.getResource("/openai/$name")?.readText() ?: error("Missing fixture $name")

    private object UnknownOpenAIOption: ProviderOption {
        override val provider = "openai"
    }

    private val httpClient = FakeHttpClient(body = OpenAIChatModelOptionsTest::class.java
        .getResource("/openai/chat/text-simple.json")!!.readText())
    private val model = OpenAIChatModel("gpt-4.1-mini", OpenAIConfig(apiKey = "sk-test"), httpClient)
}
