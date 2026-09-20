@file:Suppress("ClassName")

package dev.botta.trantor.ai.providers.openai

import dev.botta.json.Json
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.ai.testing.FakeHttpClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * The same request against models of different families.
 *
 * OpenAI splits its models in two and each half refuses what the other takes: a reasoning model answers 400 to a
 * `temperature` that is not its own, and a model that does not reason answers 400 to `reasoning`. These tests are
 * the contract that the plain api works on both.
 */
class OpenAIChatModelPerModelTest {
    @Nested
    inner class `sampling settings` {
        @Test
        fun `go to a model that does not reason`() {
            generate("gpt-4.1-mini", ChatSettings(temperature = 0.2, topP = 0.9))

            assertThat(sentBody()["temperature"]?.asDouble()).isEqualTo(0.2)
            assertThat(sentBody()["top_p"]?.asDouble()).isEqualTo(0.9)
        }

        @Test
        fun `and are dropped on one that does, which is the failure half the libraries have open as a bug`() {
            val response = generate("o4-mini", ChatSettings(temperature = 0.2, topP = 0.9))

            assertThat(sentBody().containsKey("temperature")).isFalse()
            assertThat(sentBody().containsKey("top_p")).isFalse()
            assertThat(response.warnings.map { it.setting }).containsExactly("temperature", "topP")
        }

        @Test
        fun `a model that reasons by default still refuses them while it is reasoning`() {
            val response = generate("gpt-5.6-terra", ChatSettings(temperature = 0.2))

            assertThat(sentBody().containsKey("temperature")).isFalse()
            assertThat(response.warnings.map { it.setting }).containsExactly("temperature")
        }

        @Test
        fun `but takes them once it is told not to reason at all, which is a level of its own`() {
            val response = generate("gpt-5.6-terra", ChatSettings(temperature = 0.2, reasoning = Reasoning.Off))

            assertThat(sentBody()["temperature"]?.asDouble()).isEqualTo(0.2)
            assertThat(sentBody().path("reasoning.effort")?.asString()).isEqualTo("none")
            assertThat(response.warnings).isEmpty()
        }

        @Test
        fun `and a model that cannot be told that refuses them either way`() {
            // An effort of none is a 400 on GPT-6, so there is no state in which it takes a temperature
            val response = generate("gpt-6-astra", ChatSettings(temperature = 0.2, reasoning = Reasoning.Off))

            assertThat(sentBody().containsKey("temperature")).isFalse()
            assertThat(response.warnings.map { it.setting }).contains("temperature")
        }
    }

    @Nested
    inner class `asking the model to think` {
        @Test
        fun `goes to a model that reasons`() {
            generate("gpt-6-astra", ChatSettings(reasoning = Reasoning.effort(ReasoningEfforts.Medium)))

            assertThat(sentBody().path("reasoning.effort")?.asString()).isEqualTo("medium")
        }

        @Test
        fun `and is dropped on one that does not, instead of being a 400`() {
            val response = generate("gpt-4.1-mini", ChatSettings(reasoning = Reasoning.effort(ReasoningEfforts.Low)))

            assertThat(sentBody().containsKey("reasoning")).isFalse()
            assertThat(response.warnings.map { it.setting }).containsExactly("reasoning")
        }

        @Test
        fun `minimal reaches the family that has it`() {
            generate("gpt-5-mini", ChatSettings(reasoning = Reasoning.effort(ReasoningEfforts.Minimal)))

            assertThat(sentBody().path("reasoning.effort")?.asString()).isEqualTo("minimal")
        }

        @Test
        fun `and turns into the nearest level on the family that does not`() {
            val response = generate("gpt-6-astra", ChatSettings(reasoning = Reasoning.effort(ReasoningEfforts.Minimal)))

            assertThat(sentBody().path("reasoning.effort")?.asString()).isEqualTo("low")
            assertThat(response.warnings.map { it.setting }).containsExactly("reasoning")
        }
    }

    @Nested
    inner class `a model nobody described` {
        @Test
        fun `stands in the newest one`() {
            val response = generate("gpt-7", ChatSettings(temperature = 0.2))

            assertThat(sentBody().containsKey("temperature")).isFalse()
            assertThat(response.warnings.single().message).contains("add gpt-7 to it if it takes more")
        }
    }

    private fun generate(modelId: String, settings: ChatSettings) =
        OpenAIChatModel(modelId, OpenAIConfig(apiKey = "sk-test"), httpClient)
            .generate(ChatRequest(listOf(Message.user("Hola")), settings = settings))

    private fun sentBody() = Json.parse(httpClient.requestBody!!).asObject()!!

    private val httpClient = FakeHttpClient()
}
