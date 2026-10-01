@file:Suppress("ClassName")

package dev.botta.trantor.ai.models.catalog

import dev.botta.trantor.ai.models.chat.ReasoningEfforts
import dev.botta.trantor.ai.providers.anthropic.addAnthropicModels
import dev.botta.trantor.ai.providers.openai.addOpenAIModels
import dev.botta.trantor.domain.Money
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
            val onlyGpt4 = ModelCatalog().add("openai/gpt-4", ModelCapabilities.Modern)

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

            assertThat(guess?.capabilities).isEqualTo(catalog.find("anthropic", "claude-opus-5-5")?.capabilities)
            assertThat(guess?.modelId).isEqualTo("claude-sonnet-9")
            assertThat(guess?.isGuess).isTrue()
        }

        @Test
        fun `and a written model is never a guess`() {
            assertThat(catalog.find("anthropic", "claude-sonnet-4-5")?.isGuess).isFalse()
            assertThat(catalog.find("anthropic", "claude-sonnet-4-5-20250929")?.isGuess).isFalse()
        }

        @Test
        fun `with no latest set there is nothing to stand in, and the model stays unknown`() {
            val bare = ModelCatalog().add("x/a", ModelCapabilities.Modern)

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
            // The latest model does not answer a like: a typo has to stay a typo, or nothing would ever be wrong
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
    inner class `the price of a model` {
        @Test
        fun `is written with what it takes, on the one line that says everything about the model`() {
            catalog.add("x/big", ModelCapabilities(maxOutputTokens = 1_000), big)

            val spec = catalog.find("x/big")

            assertThat(spec?.capabilities?.maxOutputTokens).isEqualTo(1_000)
            assertThat(spec?.pricing).isEqualTo(big)
        }

        @Test
        fun `as the price list shows it, per million tokens`() {
            val pricing = ModelPricing(input = "3", output = "15", cacheRead = "0.3", cacheWrite = "3.75")

            assertThat(pricing).isEqualTo(
                ModelPricing(
                    inputPerMillion = Money(3),
                    outputPerMillion = Money(15),
                    cacheReadPerMillion = Money("0.3"),
                    cacheWritePerMillion = Money("3.75"),
                )
            )
        }

        @Test
        fun `a model written again without one keeps the price it had`() {
            catalog.add("x/big", ModelCapabilities(), big)
            catalog.add("x/big", ModelCapabilities(maxOutputTokens = 1_000))

            assertThat(catalog.find("x/big")?.pricing).isEqualTo(big)
        }

        @Test
        fun `a model described as another comes with its own price`() {
            catalog.add("x/big", ModelCapabilities(), big)
            catalog.add("x/bigger", like = "x/big", pricing = ModelPricing(input = "5", output = "25"))

            assertThat(catalog.find("x/bigger")?.pricing?.inputPerMillion).isEqualTo(Money(5))
        }

        @Test
        fun `and takes what the other takes, but not what it costs`() {
            catalog.add("x/big", ModelCapabilities(), big)
            catalog.add("x/bigger", like = "x/big")

            assertThat(catalog.find("x/bigger")?.pricing).isNull()
        }

        @Test
        fun `a model nobody described can still be priced, which is all an estimate needs`() {
            catalog.price("x/priced", ModelPricing(input = "1", output = "5"))

            assertThat(catalog.priceOf("x", "priced")?.inputPerMillion).isEqualTo(Money(1))
        }

        @Test
        fun `and a price can be written before the model, since order never matters`() {
            catalog.price("x/big", big)
            catalog.add("x/big", ModelCapabilities())

            assertThat(catalog.find("x/big")?.pricing).isEqualTo(big)
        }

        @Test
        fun `a dated snapshot costs what its family costs`() {
            catalog.add("x/big", ModelCapabilities(), big)

            assertThat(catalog.find("x/big-20250929")?.pricing).isEqualTo(big)
        }

        @Test
        fun `unless it has a price of its own, which some old snapshots do`() {
            catalog.add("x/big", ModelCapabilities(), big)
            catalog.price("x/big-2024-05-13", ModelPricing(input = "5", output = "15"))

            val snapshot = catalog.find("x/big-2024-05-13")

            assertThat(snapshot?.pricing?.inputPerMillion).isEqualTo(Money(5))
            assertThat(snapshot?.capabilities).isEqualTo(catalog.find("x/big")?.capabilities)
        }

        @Test
        fun `a model standing in for the newest does not take its price`() {
            catalog.add("x/newest", ModelCapabilities(), big).setLatest("x", "x/newest")

            assertThat(catalog.find("x/unknown")?.pricing).isNull()
        }

        @Test
        fun `but keeps its own, when somebody wrote the price and not the capabilities`() {
            // What it takes is still a guess; what it costs is not
            catalog.add("x/newest", ModelCapabilities()).setLatest("x", "x/newest")
            catalog.price("x/priced", ModelPricing(input = "1", output = "5"))

            val spec = catalog.find("x/priced")

            assertThat(spec?.isGuess).isTrue()
            assertThat(spec?.pricing?.inputPerMillion).isEqualTo(Money(1))
        }

        @Test
        fun `and a spec added whole brings its price along`() {
            catalog.add(ModelSpec("x", "whole", ModelCapabilities(), pricing = big))

            assertThat(catalog.find("x/whole")?.pricing).isEqualTo(big)
        }

        @Test
        fun `and is listed with the model`() {
            catalog.add("x/big", ModelCapabilities(), big)

            assertThat(catalog.all("x").single().pricing).isEqualTo(big)
        }

        private val big = ModelPricing(input = "3", output = "15")
    }

    @Nested
    inner class `what each provider brought` {
        @Test
        fun `has a price for every model`() {
            val unpriced = catalog.all().filter { it.pricing == null }.map { it.reference }

            assertThat(unpriced).isEmpty()
        }

        @Test
        fun `is listed apart`() {
            assertThat(catalog.all("anthropic")).isNotEmpty().allMatch { it.provider == "anthropic" }
            assertThat(catalog.all("openai")).isNotEmpty().allMatch { it.provider == "openai" }
        }

    }

    private val catalog = ModelCatalog().addAnthropicModels().addOpenAIModels()
}
