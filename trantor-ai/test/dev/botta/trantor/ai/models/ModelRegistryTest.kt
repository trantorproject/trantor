@file:Suppress("ClassName")

package dev.botta.trantor.ai.models

import dev.botta.trantor.ai.models.chat.ChatModel
import dev.botta.trantor.ai.models.chat.ChatRequest
import dev.botta.trantor.ai.models.chat.ChatResponse
import dev.botta.trantor.ai.models.middleware.ChatModelMiddleware
import dev.botta.trantor.ai.providers.AIProvider
import dev.botta.trantor.ai.testing.FakeChatModel
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ModelRegistryTest {
    @Test
    fun `builds the model of a provider by its reference`() {
        val registry = registryWith(openai)

        val model = registry.chat("openai/gpt-4.1-mini")

        assertThat(model.provider).isEqualTo("openai")
        assertThat(model.modelId).isEqualTo("gpt-4.1-mini")
    }

    @Test
    fun `a model id with slashes goes to the provider whole`() {
        val registry = registryWith(gateway)

        assertThat(registry.chat("gateway/openai/gpt-4.1-mini").modelId).isEqualTo("openai/gpt-4.1-mini")
    }

    @Test
    fun `does not check the model id against anything`() {
        val registry = registryWith(openai)

        assertThat(registry.chat("openai/a-model-that-came-out-today").modelId)
            .isEqualTo("a-model-that-came-out-today")
    }

    @Test
    fun `gives back the same model for the same reference`() {
        val registry = registryWith(openai)

        assertThat(registry.chat("openai/gpt-4.1-mini")).isSameAs(registry.chat("openai/gpt-4.1-mini"))
    }

    @Test
    fun `resolves an alias`() {
        val registry = registryWith(openai).withAliases(mapOf("fast" to "openai/gpt-4.1-mini"))

        assertThat(registry.chat("fast").modelId).isEqualTo("gpt-4.1-mini")
    }

    @Test
    fun `an alias can point at another alias`() {
        val aliases = mapOf("summary" to "cheap", "cheap" to "openai/gpt-4.1-nano")
        val registry = registryWith(openai).withAliases(aliases)

        assertThat(registry.chat("summary").modelId).isEqualTo("gpt-4.1-nano")
    }

    @Test
    fun `two aliases pointing at the same model share the model`() {
        val aliases = mapOf("fast" to "openai/gpt-4.1-mini", "cheap" to "openai/gpt-4.1-mini")
        val registry = registryWith(openai).withAliases(aliases)

        assertThat(registry.chat("fast")).isSameAs(registry.chat("cheap"))
    }

    @Test
    fun `asking for no model in particular gives the default`() {
        val registry = registryWith(openai).withAliases(mapOf("default" to "openai/gpt-4.1-mini"))

        assertThat(registry.chat().modelId).isEqualTo("gpt-4.1-mini")
    }

    @Test
    fun `without a default it says so instead of picking one`() {
        val registry = registryWith(openai).withAliases(mapOf("fast" to "openai/gpt-4.1-mini"))

        assertThatThrownBy { registry.chat() }
            .isInstanceOf(ModelNotFoundError::class.java)
            .hasMessageContaining("There is no model configured as default")
            .hasMessageContaining("fast")
    }

    @Test
    fun `a provider that is not registered names the ones that are`() {
        val registry = registryWith(openai, gateway)

        assertThatThrownBy { registry.chat("anthropic/claude") }
            .isInstanceOfSatisfying(ModelNotFoundError::class.java) {
                assertThat(it.reference).isEqualTo("anthropic/claude")
            }
            .hasMessageContaining("There is no provider called anthropic")
            .hasMessageContaining("gateway, openai")
    }

    @Test
    fun `a reference without a provider is not taken for a model id`() {
        val registry = registryWith(openai).withAliases(mapOf("fast" to "openai/gpt-4.1-mini"))

        assertThatThrownBy { registry.chat("gpt-4.1-mini") }
            .isInstanceOf(ModelNotFoundError::class.java)
            .hasMessageContaining("\"gpt-4.1-mini\" is not a model reference")
            .hasMessageContaining("aliases: fast")
    }

    @Test
    fun `an alias pointing at something that is not a reference says where it came from`() {
        val registry = registryWith(openai).withAliases(mapOf("fast" to "gpt-4.1-mini"))

        assertThatThrownBy { registry.chat("fast") }
            .isInstanceOf(ModelNotFoundError::class.java)
            .hasMessageContaining("The alias \"fast\" points at \"gpt-4.1-mini\", which is not a model reference")
    }

    @Test
    fun `aliases that point at each other do not hang`() {
        val registry = registryWith(openai).withAliases(mapOf("a" to "b", "b" to "a"))

        assertThatThrownBy { registry.chat("a") }
            .isInstanceOf(ModelNotFoundError::class.java)
            .hasMessageContaining("goes in circles")
    }

    @Test
    fun `a provider with no model id is a bad reference`() {
        val registry = registryWith(openai)

        assertThatThrownBy { registry.chat("openai/") }
            .isInstanceOf(ModelNotFoundError::class.java)
            .hasMessageContaining("is not a model reference")
    }

    @Test
    fun `the same provider twice is a mistake, not the last one winning`() {
        val registry = registryWith(openai)

        assertThatThrownBy { registry.addProvider(FakeProvider("openai")) }
            .hasMessageContaining("Provider openai is already registered")
    }

    @Test
    fun `a middleware added later reaches the models asked for after it`() {
        val registry = registryWith(openai)
        registry.chat("openai/gpt-4.1-mini")

        registry.use(Counting)

        registry.chat("openai/gpt-4.1-mini").generate(ChatRequest("Hola"))
        assertThat(Counting.calls).isEqualTo(1)
    }

    private object Counting: ChatModelMiddleware {
        var calls = 0

        override fun generate(
            request: ChatRequest,
            options: CallOptions,
            next: (ChatRequest, CallOptions) -> ChatResponse,
        ): ChatResponse {
            calls++

            return next(request, options)
        }
    }

    private fun registryWith(vararg providers: AIProvider) =
        ModelRegistry().apply { providers.forEach { addProvider(it) } }

    private fun ModelRegistry.withAliases(aliases: Map<String, String>) =
        apply { aliases.forEach { (name, reference) -> addAlias(name, reference) } }

    private class FakeProvider(override val name: String): AIProvider {
        override fun chatModel(modelId: String): ChatModel = FakeChatModel(modelId = modelId, provider = name)
    }

    private val openai = FakeProvider("openai")
    private val gateway = FakeProvider("gateway")
}
