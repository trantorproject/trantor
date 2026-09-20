@file:Suppress("ClassName")

package dev.botta.trantor.ai

import dev.botta.trantor.ai.errors.ModelNotFoundError
import dev.botta.trantor.ai.models.CallOptions
import dev.botta.trantor.ai.models.ModelRegistry
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.ai.models.middleware.ChatModelMiddleware
import dev.botta.trantor.ai.providers.AIProvider
import dev.botta.trantor.ai.providers.openai.OpenAIConfig
import dev.botta.trantor.ai.providers.openai.addOpenAI
import dev.botta.trantor.ai.providers.openai.openAIConfigOf
import dev.botta.trantor.ai.testing.FakeChatModel
import dev.botta.trantor.config.ConfigManager
import dev.botta.trantor.config.providers.addMemoryCollection
import dev.botta.trantor.di.DefaultServiceProvider
import dev.botta.trantor.di.ServiceRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ServiceRegistryExtensionsTest {
    @Test
    fun `registers the model registry`() {
        registry.addAI()

        assertThat(provider.get<ModelRegistry>()).isNotNull()
    }

    @Test
    fun `registering twice leaves one registry`() {
        registry.addAI()
        registry.addAI()

        assertThat(registry.count { it.serviceType == ModelRegistry::class.java }).isEqualTo(1)
    }

    @Test
    fun `takes the aliases from the ai models section`() {
        config.addMemoryCollection(
            "ai.models.default" to "fake/a-model",
            "ai.models.fast" to "fake/another-model",
        )
        registry.addAI()
        registry.addSingleton<AIProvider> { FakeProvider() }

        val models = provider.get<ModelRegistry>()

        assertThat(models.chat().modelId).isEqualTo("a-model")
        assertThat(models.chat("fast").modelId).isEqualTo("another-model")
    }

    @Test
    fun `works without any aliases configured`() {
        registry.addAI()
        registry.addSingleton<AIProvider> { FakeProvider() }

        assertThat(provider.get<ModelRegistry>().chat("fake/a-model").modelId).isEqualTo("a-model")
    }

    @Test
    fun `a provider registered after addAI is still found`() {
        registry.addAI()
        registry.addSingleton<AIProvider> { FakeProvider() }

        assertThat(provider.get<ModelRegistry>().chat("fake/a-model")).isNotNull()
    }

    @Test
    fun `wraps the models in the middlewares that were registered`() {
        registry.addAI()
        registry.addSingleton<AIProvider> { FakeProvider() }
        registry.addSingleton<ChatModelMiddleware> { Counting }

        provider.get<ModelRegistry>().chat("fake/a-model").generate(ChatRequest("Hola"))

        assertThat(Counting.calls).isEqualTo(1)
    }

    @Test
    fun `registers openai as a provider`() {
        registry.addOpenAI(OpenAIConfig(apiKey = "sk-test"))

        val model = provider.get<ModelRegistry>().chat("openai/gpt-4.1-mini")

        assertThat(model.provider).isEqualTo("openai")
        assertThat(model.modelId).isEqualTo("gpt-4.1-mini")
    }

    @Test
    fun `addOpenAI brings the registry along, so an app only calls one`() {
        registry.addOpenAI(OpenAIConfig(apiKey = "sk-test"))

        assertThat(provider.get<ModelRegistry>()).isNotNull()
    }

    @Test
    fun `reads the openai config from its section`() {
        config.addMemoryCollection(
            "ai.providers.openai.apiKey" to "sk-from-config",
            "ai.providers.openai.baseUrl" to "http://localhost:1234/v1",
            "ai.providers.openai.organization" to "org-7",
            "ai.providers.openai.store" to "false",
        )

        val openAI = openAIConfigOf(config)

        assertThat(openAI.apiKey).isEqualTo("sk-from-config")
        assertThat(openAI.baseUrl).isEqualTo("http://localhost:1234/v1")
        assertThat(openAI.organization).isEqualTo("org-7")
        assertThat(openAI.store).isFalse()
    }

    @Test
    fun `what the config does not say keeps its default`() {
        config.addMemoryCollection("ai.providers.openai.apiKey" to "sk-from-config")

        val openAI = openAIConfigOf(config)

        assertThat(openAI.baseUrl).isEqualTo(OpenAIConfig.DEFAULT_BASE_URL)
        assertThat(openAI.organization).isNull()
        assertThat(openAI.store).isNull()
    }

    @Test
    fun `a key configured in the section is used instead of the environment`() {
        config.addMemoryCollection(
            "ai.providers.openai.apiKey" to "sk-from-config",
            "ai.models.default" to "openai/gpt-4.1-mini",
        )
        registry.addOpenAI()

        assertThat(provider.get<ModelRegistry>().chat().modelId).isEqualTo("gpt-4.1-mini")
    }

    @Test
    fun `two providers live together`() {
        registry.addOpenAI(OpenAIConfig(apiKey = "sk-test"))
        registry.addSingleton<AIProvider> { FakeProvider() }

        val models = provider.get<ModelRegistry>()

        assertThat(models.chat("openai/gpt-4.1-mini").provider).isEqualTo("openai")
        assertThat(models.chat("fake/a-model").provider).isEqualTo("fake")
    }

    @Test
    fun `an alias pointing at a provider that was never registered says so`() {
        config.addMemoryCollection("ai.models.default" to "anthropic/claude")
        registry.addOpenAI(OpenAIConfig(apiKey = "sk-test"))

        assertThatThrownBy { provider.get<ModelRegistry>().chat() }
            .isInstanceOf(ModelNotFoundError::class.java)
            .hasMessageContaining("There is no provider called anthropic")
    }

    private class FakeProvider: AIProvider {
        override val name = "fake"

        override fun chatModel(modelId: String) = FakeChatModel(modelId = modelId, provider = name)
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

    private val config = ConfigManager()
    private val registry = ServiceRegistry(config)
    private val provider = DefaultServiceProvider(registry)
}
