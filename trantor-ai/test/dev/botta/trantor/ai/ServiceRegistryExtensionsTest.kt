@file:Suppress("ClassName")

package dev.botta.trantor.ai

import dev.botta.json.Json
import dev.botta.trantor.ai.agents.Agent
import dev.botta.trantor.ai.agents.AgentHookContext
import dev.botta.trantor.ai.agents.AgentHooks
import dev.botta.trantor.ai.agents.AgentRunner
import dev.botta.trantor.ai.agents.GuardrailTrippedError
import dev.botta.trantor.ai.agents.GuardrailVerdict
import dev.botta.trantor.ai.agents.InputGuardrail
import dev.botta.trantor.ai.models.CallOptions
import dev.botta.trantor.ai.models.ModelNotFoundError
import dev.botta.trantor.ai.models.ModelRegistry
import dev.botta.trantor.ai.models.Usage
import dev.botta.trantor.ai.models.catalog.ModelPricing
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.ai.models.middleware.ChatModelMiddleware
import dev.botta.trantor.ai.models.middleware.CostMiddleware
import dev.botta.trantor.ai.providers.AIProvider
import dev.botta.trantor.ai.providers.anthropic.AnthropicCache
import dev.botta.trantor.ai.providers.anthropic.AnthropicCacheTtl
import dev.botta.trantor.ai.providers.anthropic.AnthropicConfig
import dev.botta.trantor.ai.providers.anthropic.addAnthropic
import dev.botta.trantor.ai.providers.openai.OpenAIConfig
import dev.botta.trantor.ai.providers.openai.addOpenAI
import dev.botta.trantor.ai.testing.FakeChatModel
import dev.botta.trantor.ai.testing.FakeHttpClient
import dev.botta.trantor.ai.testing.SkuTool
import dev.botta.trantor.ai.testing.TestTelemetry
import dev.botta.trantor.ai.tools.FunctionToolSpec
import dev.botta.trantor.ai.tools.Tool
import dev.botta.trantor.ai.tools.ToolContext
import dev.botta.trantor.ai.tools.ToolOutput
import dev.botta.trantor.ai.tools.ToolResult
import dev.botta.trantor.config.ConfigManager
import dev.botta.trantor.config.providers.addMemoryCollection
import dev.botta.trantor.di.DefaultServiceProvider
import dev.botta.trantor.di.ServiceRegistry
import dev.botta.trantor.domain.Money
import dev.botta.trantor.primitives.serialization.JsonSerializer
import dev.botta.trantor.serialization.gson.GsonSerializer
import dev.botta.trantor.web.client.HttpClient
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey.stringKey
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ServiceRegistryExtensionsTest {
    @Nested
    inner class `what addAI brings` {
        @Test
        fun `the providers of Trantor, so an application calls one thing`() {
            registry.addAI()

            assertThat(models().chat("openai/gpt-4.1-mini").provider).isEqualTo("openai")
            assertThat(models().chat("anthropic/claude-sonnet-4-5").provider).isEqualTo("anthropic")
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
        fun `a configuration of the application is kept, whichever call comes first`() {
            registry.addOpenAI { openAI, _ -> openAI.apiKey = "sk-from-the-app" }
            registry.addAI()

            assertThat(provider.get<OpenAIConfig>().apiKey).isEqualTo("sk-from-the-app")
        }

        @Test
        fun `and the other way round too`() {
            registry.addAI()
            registry.addOpenAI { openAI, _ -> openAI.apiKey = "sk-from-the-app" }

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
        fun `the one that estimates cost is built out of the container, with the catalog every provider shares`() {
            registry.addAI { models, services -> models.use(services.create<CostMiddleware>()) }
            registry.addFakeProvider()
            registry.addModelCatalog { catalog, _ ->
                catalog.price("fake/a-model", ModelPricing(input = "3", output = "15"))
            }

            val response = models().chat("fake/a-model").generate(ChatRequest("Hola"))

            assertThat(response.info.estimatedCost?.total).isEqualTo(Money("0.000465"))
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
            config.addMemoryCollection("ai.models.default" to "cohere/command")
            registry.addAI()

            assertThatThrownBy { models().chat() }
                .isInstanceOf(ModelNotFoundError::class.java)
                .hasMessageContaining("There is no provider called cohere")
        }
    }

    @Nested
    inner class `each provider of Trantor` {
        @ParameterizedTest
        @ValueSource(strings = ["openai", "anthropic"])
        fun `builds its models`(name: String) {
            add(name)

            assertThat(models().chat(modelOf(name)).provider).isEqualTo(name)
        }

        @ParameterizedTest
        @ValueSource(strings = ["openai", "anthropic"])
        fun `brings the registry along, and the http client of the application when there is none`(name: String) {
            add(name)

            assertThat(models()).isNotNull()
            assertThat(provider.get<HttpClient>()).isNotNull()
        }

        @ParameterizedTest
        @ValueSource(strings = ["openai", "anthropic"])
        fun `calls through the http client of the application`(name: String) {
            // An empty object is an answer the mapper of Anthropic reads without complaining
            val answer =
                if (name == "openai") javaClass.getResource("/openai/chat/text-simple.json")!!.readText() else "{}"
            val http = FakeHttpClient(body = answer)
            registry.addSingleton<HttpClient>(http)
            add(name)

            models().chat(modelOf(name)).generate(ChatRequest("Hola"))

            assertThat(http.request?.url).startsWith(
                if (name == "openai") OpenAIConfig.DEFAULT_BASE_URL else AnthropicConfig.DEFAULT_BASE_URL,
            )
        }

        @ParameterizedTest
        @ValueSource(strings = ["openai", "anthropic"])
        fun `lives together with another provider`(name: String) {
            add(name)
            registry.addFakeProvider()

            assertThat(models().chat(modelOf(name)).provider).isEqualTo(name)
            assertThat(models().chat("fake/a-model").provider).isEqualTo("fake")
        }

        private fun add(name: String) {
            if (name == "openai") registry.addOpenAI { openAI, _ -> openAI.apiKey = "sk-test" }
            else registry.addAnthropic { anthropic, _ -> anthropic.apiKey = "sk-ant-test" }
        }

        private fun modelOf(name: String) =
            if (name == "openai") "openai/gpt-4.1-mini" else "anthropic/claude-sonnet-4-5"
    }

    @Nested
    inner class `the openai provider` {
        @Test
        fun `reads its config from its section`() {
            config.addMemoryCollection(
                "ai.providers.openai.apiKey" to "sk-from-config",
                "ai.providers.openai.baseUrl" to "http://localhost:1234/v1",
                "ai.providers.openai.organization" to "org-7",
            )
            registry.addOpenAI()

            val openAI = provider.get<OpenAIConfig>()

            assertThat(openAI.apiKey).isEqualTo("sk-from-config")
            assertThat(openAI.baseUrl).isEqualTo("http://localhost:1234/v1")
            assertThat(openAI.organization).isEqualTo("org-7")
        }

        @Test
        fun `keeps its defaults for what the config does not say`() {
            config.addMemoryCollection("ai.providers.openai.apiKey" to "sk-from-config")
            registry.addOpenAI()

            val openAI = provider.get<OpenAIConfig>()

            assertThat(openAI.baseUrl).isEqualTo(OpenAIConfig.DEFAULT_BASE_URL)
            assertThat(openAI.organization).isNull()
            assertThat(openAI.store).isNull()
        }

    }

    @Nested
    inner class `the anthropic provider` {
        @Test
        fun `reads its config from its section`() {
            config.addMemoryCollection(
                "ai.providers.anthropic.apiKey" to "sk-ant-from-config",
                "ai.providers.anthropic.baseUrl" to "http://localhost:1234/v1",
                "ai.providers.anthropic.defaultMaxTokens" to "1024",
            )
            registry.addAnthropic()

            val anthropic = provider.get<AnthropicConfig>()

            assertThat(anthropic.apiKey).isEqualTo("sk-ant-from-config")
            assertThat(anthropic.baseUrl).isEqualTo("http://localhost:1234/v1")
            assertThat(anthropic.defaultMaxTokens).isEqualTo(1024)
        }

        @Test
        fun `keeps its defaults for what the config does not say`() {
            config.addMemoryCollection("ai.providers.anthropic.apiKey" to "sk-ant-from-config")
            registry.addAnthropic()

            val anthropic = provider.get<AnthropicConfig>()

            assertThat(anthropic.baseUrl).isEqualTo(AnthropicConfig.DEFAULT_BASE_URL)
            assertThat(anthropic.version).isEqualTo(AnthropicConfig.DEFAULT_VERSION)
            assertThat(anthropic.defaultMaxTokens).isNull()
            assertThat(anthropic.betas).isEmpty()
        }

        @Test
        fun `a configuration of the application is kept, whichever call comes first`() {
            registry.addAnthropic { anthropic, _ -> anthropic.apiKey = "sk-ant-from-the-app" }
            registry.addAI()

            assertThat(provider.get<AnthropicConfig>().apiKey).isEqualTo("sk-ant-from-the-app")
        }

        @Test
        fun `reads which parts of the prompt to cache, and keeps the rest of the cache off`() {
            config.addMemoryCollection(
                "ai.providers.anthropic.cache.system" to "true",
                "ai.providers.anthropic.cache.ttl" to "OneHour",
            )
            registry.addAnthropic()

            assertThat(provider.get<AnthropicConfig>().cache)
                .isEqualTo(AnthropicCache(system = true, ttl = AnthropicCacheTtl.OneHour))
        }

        @Test
        fun `reads the betas it was given as a list`() {
            // What a JSON array of settings.json flattens into, so a beta can be turned on without a release
            config.addMemoryCollection(
                "ai.providers.anthropic.betas.__config_type__" to "array",
                "ai.providers.anthropic.betas.size" to "2",
                "ai.providers.anthropic.betas.0" to "context-1m-2025-08-07",
                "ai.providers.anthropic.betas.1" to "another-one",
            )
            registry.addAnthropic()

            assertThat(provider.get<AnthropicConfig>().betas)
                .containsExactly("context-1m-2025-08-07", "another-one")
        }

    }

    @Nested
    inner class `the facade` {
        @Test
        fun `addAI brings it, over the registry of the application`() {
            registry.addAI()
            registry.addFakeProvider()
            registry.configure<ModelRegistry> { models, _ -> models.addAlias("default", "fake/a-model") }

            val ai = provider.get<AI>()

            assertThat(ai.text("Hola")).isEqualTo("ok")
            assertThat(ai.models()).isSameAs(models())
        }

        @Test
        fun `once, however many times addAI is called`() {
            registry.addAI()
            registry.addAI()

            assertThat(registry.count { it.serviceType == AI::class.java }).isEqualTo(1)
        }

        @Test
        fun `an AI of the application is kept`() {
            val own = DefaultAI(ModelRegistry())
            registry.addSingleton<AI>(own)
            registry.addAI()

            assertThat(provider.get<AI>()).isSameAs(own)
        }

        @Test
        fun `addAI brings the runner of the agents too, once`() {
            registry.addAI()

            assertThat(provider.get<AgentRunner>()).isNotNull()
            assertThat(registry.count { it.serviceType == AgentRunner::class.java }).isEqualTo(1)
        }

        @Test
        fun `the tool error handlers the application names reach its tools`() {
            val model = FakeChatModel(provider = "scripted").answers(listOf(ToolCallPart("call_1", "fail", Json.obj())))
            registry.addAI()
            registry.configure<ModelRegistry> { models, _ -> models.addProvider(ScriptedProvider(model)) }
            registry.addToolErrorHandlers { handlers, _ -> handlers.add { _, _ -> "Not available right now" } }

            val result = provider.get<AI>().generate {
                model("scripted/a-model")
                user("Hola")
                tools(FailingTool())
            }

            assertThat(result.steps[0].toolResults.single().output)
                .isEqualTo(ToolOutput.Text("Not available right now"))
        }

        @Test
        fun `the tools of a generation read and describe their args with the serializer of the application`() {
            val stock = SkuTool()
            val model = FakeChatModel(provider = "scripted")
                .answers(listOf(ToolCallPart("call_1", "stock", Json.obj("sku" to "ABC-1"))), listOf(TextPart("12")))
            registry.addSingleton<JsonSerializer>(SkuTool.serializer())
            registry.addAI()
            registry.configure<ModelRegistry> { models, _ -> models.addProvider(ScriptedProvider(model)) }

            provider.get<AI>().generate {
                model("scripted/a-model")
                user("Hay stock?")
                tools(stock)
            }

            val spec = model.requests.first().tools.single() as FunctionToolSpec
            assertThat(spec.parameters.path("properties.sku.pattern")?.asString()).isEqualTo(SkuTool.PATTERN)
            assertThat(stock.received).isEqualTo(SkuTool.Args(SkuTool.Sku("ABC-1")))
        }

        @Test
        fun `an answer that is an object is described and read by the serializer of the application`() {
            val model = FakeChatModel(provider = "scripted").answers(listOf(TextPart("""{"sku":"ABC-1"}""")))
            registry.addSingleton<JsonSerializer>(SkuTool.serializer())
            registry.addAI()
            registry.configure<ModelRegistry> { models, _ -> models.addProvider(ScriptedProvider(model)) }

            val args = provider.get<AI>().generate<SkuTool.Args> {
                model("scripted/a-model")
                user("Que producto?")
            }

            assertThat(args).isEqualTo(SkuTool.Args(SkuTool.Sku("ABC-1")))
        }

        @Test
        fun `and so do the tools of an agent`() {
            val stock = SkuTool()
            val model = FakeChatModel(provider = "scripted")
                .answers(listOf(ToolCallPart("call_1", "stock", Json.obj("sku" to "ABC-1"))), listOf(TextPart("12")))
            registry.addSingleton<JsonSerializer>(SkuTool.serializer())
            registry.addAI()

            val agent = Agent("seller").model(model).tools(stock).build()

            provider.get<AgentRunner>().run(agent, Message.user("Hay stock?"))

            assertThat(stock.received).isEqualTo(SkuTool.Args(SkuTool.Sku("ABC-1")))
        }

        @Test
        fun `the global hooks of the agents reach every run of the runner`() {
            val model = FakeChatModel(provider = "scripted").answers(listOf(TextPart("Hola")))
            val called = mutableListOf<String>()
            registry.addAI()
            registry.addAgentHooks { hooks, _ ->
                hooks.add(object: AgentHooks {
                    override fun beforeRun(run: AgentHookContext, conversation: List<Message>) {
                        called.add(run.agent.name)
                    }
                })
            }

            provider.get<AgentRunner>().run(Agent("support").model(model).build(), Message.user("Hola"))

            assertThat(called).containsExactly("support")
        }

        @Test
        fun `the global guardrails reach every run of the runner`() {
            val model = FakeChatModel(provider = "scripted").answers(listOf(TextPart("Hola")))
            registry.addAI()
            registry.addGuardrails { guardrails, _ ->
                guardrails.input(InputGuardrail("closed") { _, _ -> GuardrailVerdict.Trip("We are closed") })
            }

            assertThatThrownBy {
                provider.get<AgentRunner>().run(Agent("support").model(model).build(), Message.user("Hola"))
            }
                .isInstanceOf(GuardrailTrippedError::class.java)
                .hasMessageContaining("We are closed")
        }
    }

    @Nested
    inner class `tracing` {
        @Test
        fun `the generations are traced with the OpenTelemetry of the container`() {
            registry.addSingleton<OpenTelemetry>(telemetry.openTelemetry)
            registry.addAI()
            registry.addFakeProvider()

            provider.get<AI>().text("Hola", "fake/a-model")

            assertThat(telemetry.named("chat a-model").parentSpanId).isEqualTo(telemetry.named("invoke_agent").spanId)
        }

        @Test
        fun `registered after addAI too`() {
            registry.addAI()
            registry.addFakeProvider()
            registry.addSingleton<OpenTelemetry>(telemetry.openTelemetry)

            provider.get<AI>().text("Hola", "fake/a-model")

            assertThat(telemetry.spans.map { it.name }).containsExactly("chat a-model", "invoke_agent")
        }

        @Test
        fun `with the content of the calls when the ai telemetry section asks for it`() {
            config.addMemoryCollection("ai.telemetry.captureContent" to "true")
            registry.addAI()
            registry.addFakeProvider()
            registry.addSingleton<OpenTelemetry>(telemetry.openTelemetry)

            provider.get<AI>().text("Hola", "fake/a-model")

            assertThat(telemetry.named("chat a-model").attributes[stringKey("gen_ai.input.messages")])
                .isEqualTo("""[{"role":"user","parts":[{"type":"text","content":"Hola"}]}]""")
        }

        @Test
        fun `and so are the runs of the agents`() {
            val model = FakeChatModel(provider = "scripted").answers(listOf(TextPart("Hola")))
            registry.addAI()
            registry.addSingleton<OpenTelemetry>(telemetry.openTelemetry)

            provider.get<AgentRunner>().run(Agent("support").model(model).build(), Message.user("Hola"))

            assertThat(telemetry.named("chat fake-model").parentSpanId)
                .isEqualTo(telemetry.named("invoke_agent support").spanId)
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

        override fun chatModel(modelId: String) =
            FakeChatModel(modelId = modelId, provider = name, usage = Usage(inputTokens = 15, outputTokens = 28))
    }

    private class ScriptedProvider(private val model: FakeChatModel): AIProvider {
        override val name = "scripted"

        override fun chatModel(modelId: String) = model
    }

    private class FailingTool: Tool<FailingTool.Args>() {
        override val name = "fail"
        override val description = "Always fails"

        override fun execute(args: Args, context: ToolContext): ToolResult = error("db down")

        class Args
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

    private val telemetry = TestTelemetry()
    private val config = ConfigManager()

    // The host registers it; addConfig needs it to read a section into a settings class
    private val registry = ServiceRegistry(config).apply { addSingleton<JsonSerializer>(GsonSerializer()) }
    private val provider = DefaultServiceProvider(registry)
}
