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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ServiceRegistryExtensionsTest {
    @Nested
    inner class `what addAI brings` {
        @Test
        fun `the providers of Trantor, so an application calls one thing`() {
            registry.addAI()

            assertThat(models().chat("openai/gpt-4.1-mini").provider).isEqualTo("openai")
        }

        @Test
        fun `calling it twice leaves one registry and one provider`() {
            registry.addAI()
            registry.addAI()

            assertThat(registry.count { it.serviceType == ModelRegistry::class.java }).isEqualTo(1)
            assertThat(models().chat("openai/gpt-4.1-mini")).isNotNull()
        }

        @Test
        fun `a provider nobody asks for costs nothing`() {
            // There is no api key anywhere, and that only matters when someone calls an OpenAI model
            registry.addAI()
            registry.addFakeProvider()

            assertThat(models().chat("fake/a-model").provider).isEqualTo("fake")
        }

        @Test
        fun `an openai the application configured wins over the one it would bring`() {
            registry.addOpenAI(OpenAIConfig(apiKey = "sk-from-the-app"))
            registry.addAI()

            assertThat(provider.get<OpenAIConfig>().apiKey).isEqualTo("sk-from-the-app")
        }

        @Test
        fun `the registry alone leaves the providers to the application`() {
            registry.addModelRegistry()
            registry.addFakeProvider()

            assertThatThrownBy { models().chat("openai/gpt-4.1-mini") }
                .isInstanceOf(ModelNotFoundError::class.java)
                .hasMessageContaining("There is no provider called openai")
        }

        @Test
        fun `a provider added after the registry is still found`() {
            registry.addModelRegistry()
            registry.addFakeProvider()

            assertThat(models().chat("fake/a-model")).isNotNull()
        }
    }

    @Nested
    inner class `middlewares` {
        @Test
        fun `wrap the models the application named`() {
            registry.addAI { models, _ -> models.use(Counting()) }
            registry.addFakeProvider()

            models().chat("fake/a-model").generate(ChatRequest("Hola"))

            assertThat(Counting.calls).isEqualTo(1)
        }

        @Test
        fun `one nobody named is not used, even if it is in the container`() {
            registry.addAI()
            registry.addFakeProvider()
            registry.addSingleton<ChatModelMiddleware>(Counting())

            models().chat("fake/a-model").generate(ChatRequest("Hola"))

            assertThat(Counting.calls).isZero()
        }

        @Test
        fun `can be built out of the container`() {
            registry.addSingleton(Greeting("hola"))
            registry.addAI { models, services -> models.use(Counting(services.get<Greeting>().text)) }
            registry.addFakeProvider()

            models().chat("fake/a-model").generate(ChatRequest("Hola"))

            assertThat(Counting.seen).isEqualTo("hola")
        }

        @Test
        fun `wrap in the order they were named`() {
            registry.addAI { models, _ ->
                models.use(Recorder("a"))
                models.use(Recorder("b"))
            }
            registry.addFakeProvider()

            models().chat("fake/a-model").generate(ChatRequest("Hola"))

            assertThat(Recorder.calls).containsExactly("a", "b")
        }
    }

    @Nested
    inner class `aliases` {
        @Test
        fun `come from the ai models section`() {
            config.addMemoryCollection(
                "ai.models.default" to "fake/a-model",
                "ai.models.fast" to "fake/another-model",
            )
            registry.addModelRegistry()
            registry.addFakeProvider()

            assertThat(models().chat().modelId).isEqualTo("a-model")
            assertThat(models().chat("fast").modelId).isEqualTo("another-model")
        }

        @Test
        fun `are not needed`() {
            registry.addModelRegistry()
            registry.addFakeProvider()

            assertThat(models().chat("fake/a-model").modelId).isEqualTo("a-model")
        }

        @Test
        fun `one pointing at a provider that was never registered says so`() {
            config.addMemoryCollection("ai.models.default" to "anthropic/claude")
            registry.addAI()

            assertThatThrownBy { models().chat() }
                .isInstanceOf(ModelNotFoundError::class.java)
                .hasMessageContaining("There is no provider called anthropic")
        }
    }

    @Nested
    inner class `the openai provider` {
        @Test
        fun `takes a config of its own`() {
            registry.addOpenAI(OpenAIConfig(apiKey = "sk-test"))

            val model = models().chat("openai/gpt-4.1-mini")

            assertThat(model.provider).isEqualTo("openai")
            assertThat(model.modelId).isEqualTo("gpt-4.1-mini")
        }

        @Test
        fun `brings the registry along`() {
            registry.addOpenAI(OpenAIConfig(apiKey = "sk-test"))

            assertThat(models()).isNotNull()
        }

        @Test
        fun `reads its config from its section`() {
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
        fun `keeps its defaults for what the config does not say`() {
            config.addMemoryCollection("ai.providers.openai.apiKey" to "sk-from-config")

            val openAI = openAIConfigOf(config)

            assertThat(openAI.baseUrl).isEqualTo(OpenAIConfig.DEFAULT_BASE_URL)
            assertThat(openAI.organization).isNull()
            assertThat(openAI.store).isNull()
        }

        @Test
        fun `lives together with another provider`() {
            registry.addOpenAI(OpenAIConfig(apiKey = "sk-test"))
            registry.addFakeProvider()

            assertThat(models().chat("openai/gpt-4.1-mini").provider).isEqualTo("openai")
            assertThat(models().chat("fake/a-model").provider).isEqualTo("fake")
        }
    }

    @BeforeEach
    fun forgetWhatTheMiddlewaresSaw() {
        Counting.calls = 0
        Counting.seen = null
        Recorder.calls.clear()
    }

    private fun models() = provider.get<ModelRegistry>()

    /** A provider adds itself to the registry, the way addOpenAI does. */
    private fun ServiceRegistry.addFakeProvider() = apply {
        addModelRegistry()
        configure<ModelRegistry> { models, _ -> models.addProvider(FakeProvider()) }
    }

    private data class Greeting(val text: String)

    private class FakeProvider: AIProvider {
        override val name = "fake"

        override fun chatModel(modelId: String) = FakeChatModel(modelId = modelId, provider = name)
    }

    private class Counting(private val greeting: String? = null): ChatModelMiddleware {
        override fun generate(
            request: ChatRequest,
            options: CallOptions,
            next: (ChatRequest, CallOptions) -> ChatResponse,
        ): ChatResponse {
            calls++
            greeting?.let { seen = it }

            return next(request, options)
        }

        companion object {
            var calls = 0
            var seen: String? = null
        }
    }

    private class Recorder(private val name: String): ChatModelMiddleware {
        override fun generate(
            request: ChatRequest,
            options: CallOptions,
            next: (ChatRequest, CallOptions) -> ChatResponse,
        ): ChatResponse {
            calls.add(name)

            return next(request, options)
        }

        companion object {
            val calls = mutableListOf<String>()
        }
    }

    private val config = ConfigManager()
    private val registry = ServiceRegistry(config)
    private val provider = DefaultServiceProvider(registry)
}
