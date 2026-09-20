@file:Suppress("ClassName")

package dev.botta.trantor.ai.models.catalog

import dev.botta.trantor.ai.models.chat.ReasoningEfforts
import dev.botta.trantor.ai.providers.anthropic.addAnthropicModels
import dev.botta.trantor.ai.providers.openai.addOpenAIModels
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ModelCatalogTest {
    @Nested
    inner class `finding a model` {
        @Test
        fun `by its own name`() {
            assertThat(catalog.find("anthropic", "claude-opus-5")?.capabilities?.maxOutputTokens).isEqualTo(128_000)
        }

        @Test
        fun `or by the family of a dated snapshot, which needs no entry of its own`() {
            val spec = catalog.find("anthropic", "claude-sonnet-4-5-20250929")

            assertThat(spec?.capabilities?.maxOutputTokens).isEqualTo(64_000)
            // The snapshot keeps its own name, so whoever reads the spec sees the model that was asked for
            assertThat(spec?.modelId).isEqualTo("claude-sonnet-4-5-20250929")
        }

        @Test
        fun `and the other shape of date too`() {
            assertThat(catalog.find("openai", "gpt-4.1-mini-2025-04-14")?.capabilities?.temperature)
                .isEqualTo(ValueRange.ZeroToTwo)
        }

        @Test
        fun `a version suffix is the same model as well`() {
            assertThat(catalog.find("anthropic", "claude-opus-5@20260101")).isNotNull()
            assertThat(catalog.find("anthropic", "claude-opus-5-latest")).isNotNull()
        }

        @Test
        fun `but a plain prefix is not, because that is a different model`() {
            // gpt-4 is written before gpt-4o and gpt-4.1 without being either of them, so inheriting by
            // prefix would hand a newer model the capabilities of an older one and drop what it does take
            val onlyGpt4 = ModelCatalog().add("openai/gpt-4", capabilities = ModelCapabilities.Modern)

            assertThat(onlyGpt4.find("openai", "gpt-4o")).isNull()
            assertThat(onlyGpt4.find("openai", "gpt-4.1")).isNull()
            // Nor is a named variant: gpt-4-turbo has its own price and its own window
            assertThat(onlyGpt4.find("openai", "gpt-4-turbo")).isNull()
            // A date is, and OpenAI still writes some of them with four digits
            assertThat(onlyGpt4.find("openai", "gpt-4-0613")).isNotNull()
        }

        @Test
        fun `and a model nobody described stands in the newest one of its provider`() {
            // A model that comes out is the one before it with something taken away, far more often than not,
            // so the newest entry is the closest guess there is and the call goes out working
            val guess = catalog.find("anthropic", "claude-sonnet-9")

            assertThat(guess?.capabilities).isEqualTo(catalog.find("anthropic", "claude-opus-5")?.capabilities)
            assertThat(guess?.modelId).isEqualTo("claude-sonnet-9")
            assertThat(guess?.isGuess).isTrue()
        }

        @Test
        fun `and a written model is never a guess`() {
            assertThat(catalog.find("anthropic", "claude-sonnet-4-5")?.isGuess).isFalse()
            assertThat(catalog.find("anthropic", "claude-sonnet-4-5-20250929")?.isGuess).isFalse()
        }

        @Test
        fun `with no default registered there is nothing to stand in, and the model stays unknown`() {
            val bare = ModelCatalog().add("x/a", capabilities = ModelCapabilities.Modern)

            assertThat(bare.find("x", "b")).isNull()
        }
    }

    @Nested
    inner class `adding a model` {
        @Test
        fun `described as one that is already there, which is what a new model usually is`() {
            catalog.add("anthropic/claude-6", like = "anthropic/claude-opus-5")

            val capabilities = catalog.find("anthropic", "claude-6")?.capabilities

            assertThat(capabilities?.reasoningEfforts).contains(ReasoningEfforts.High)
            assertThat(capabilities?.temperature).isNull()
        }

        @Test
        fun `with what changed`() {
            catalog.add("anthropic/claude-6", like = "anthropic/claude-opus-5") { copy(maxOutputTokens = 256_000) }

            assertThat(catalog.find("anthropic", "claude-6")?.capabilities?.maxOutputTokens).isEqualTo(256_000)
        }

        @Test
        fun `naming one that is not registered yet, so the order of the calls does not matter`() {
            val catalog = ModelCatalog()
            catalog.add("anthropic/claude-6", like = "anthropic/claude-opus-5")

            catalog.addAnthropicModels()

            assertThat(catalog.find("anthropic", "claude-6")).isNotNull()
        }

        @Test
        fun `replaces one that was already described`() {
            catalog.add("anthropic/claude-opus-5", like = "anthropic/claude-sonnet-4-5")

            assertThat(catalog.find("anthropic", "claude-opus-5")?.capabilities?.maxOutputTokens).isEqualTo(64_000)
        }

        @Test
        fun `and describing it as something nobody knows says so, instead of answering a wrong spec`() {
            // The default does not answer a like: a typo has to stay a typo, or nothing would ever be wrong
            catalog.add("anthropic/claude-6", like = "anthropic/claude-nowhere")

            assertThatThrownBy { catalog.find("anthropic", "claude-6") }
                .hasMessageContaining("claude-nowhere")
        }

        @Test
        fun `a description that goes in circles says so too`() {
            catalog.add("x/a", like = "x/b")
            catalog.add("x/b", like = "x/a")

            assertThatThrownBy { catalog.find("x", "a") }.hasMessageContaining("goes in circles")
        }
    }

    @Nested
    inner class `what each provider brought` {
        @Test
        fun `is listed apart`() {
            assertThat(catalog.all("anthropic")).isNotEmpty().allMatch { it.provider == "anthropic" }
            assertThat(catalog.all("openai")).isNotEmpty().allMatch { it.provider == "openai" }
        }

        @Test
        fun `and says the thing that breaks a call if it is wrong`() {
            // A reasoning model of either provider refuses a temperature that is not its own
            assertThat(catalog.find("openai", "o4-mini")?.capabilities?.temperature).isNull()
            assertThat(catalog.find("anthropic", "claude-opus-5")?.capabilities?.temperature).isNull()

            // And one that does not reason refuses being asked to
            assertThat(catalog.find("openai", "gpt-4.1")?.capabilities?.reasoningEfforts).isEmpty()
            assertThat(catalog.find("anthropic", "claude-sonnet-4-5")?.capabilities?.reasoningEfforts).isEmpty()
        }
    }

    private val catalog = ModelCatalog().addAnthropicModels().addOpenAIModels()
}
