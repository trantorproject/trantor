package dev.botta.trantor.ai.agents

import dev.botta.json.Json
import dev.botta.trantor.ai.RunContext
import dev.botta.trantor.ai.models.ModelRegistry
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.ai.providers.AIProvider
import dev.botta.trantor.ai.providers.openai.OpenAIOptions
import dev.botta.trantor.ai.testing.FakeChatModel
import dev.botta.trantor.ai.tools.*
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import dev.botta.trantor.serialization.gson.GsonSerializer
import dev.botta.trantor.ai.testing.SkuTool
import kotlin.reflect.typeOf
import dev.botta.trantor.primitives.serialization.schemaOf

class AgentTest {
    @Test
    fun `keeps what it was built with`() {
        val options = OpenAIOptions(promptCacheKey = "chat-1")

        val agent = Agent("support")
            .tools(weather, price)
            .settings { reasoning = Reasoning.effort(ReasoningEfforts.Low) }
            .options(options)
            .build()

        assertThat(agent.name).isEqualTo("support")
        assertThat(agent.tools).containsExactly(weather, price)
        assertThat(agent.settings.reasoning).isEqualTo(Reasoning.effort(ReasoningEfforts.Low))
        assertThat(agent.options).containsExactly(options)
    }

    @Test
    fun `its model is a reference the registry resolves, the default one when it has none`() {
        val fast = Agent("support").model("fast").build()
        val plain = Agent("support").build()

        fast.modelFrom(registry)
        plain.modelFrom(registry)

        assertThat(provider.asked).containsExactly("fast-model", "default-model")
    }

    @Test
    fun `or a model in hand`() {
        val model = FakeChatModel()

        assertThat(Agent("support").model(model).build().modelFrom(registry)).isSameAs(model)
    }

    @Test
    fun `what the builder gets after building does not change the agent`() {
        val builder = Agent("support").tools(weather).settings { temperature = 0.2 }
        val agent = builder.build()

        builder.tools(price).settings { temperature = 0.9 }
        agent.settings.temperature = 0.5

        assertThat(agent.tools).containsExactly(weather)
        assertThat(agent.settings.temperature).isEqualTo(0.2)
    }

    @Nested
    inner class `Its instructions` {
        @Test
        fun `a text is the same on every call`() {
            val agent = Agent("support").instructions("Sos soporte").dynamicInstructions("Son las 10").build()

            assertThat(agent.instructions(RunContext())).isEqualTo("Sos soporte")
            assertThat(agent.dynamicInstructions(RunContext())).isEqualTo("Son las 10")
        }

        @Test
        fun `a function is asked again on every call, with the run`() {
            var hour = 10
            val agent = Agent("support")
                .instructions { run -> "Atendés a ${run.require<Customer>().name}" }
                .dynamicInstructions { "Son las ${hour++}" }
                .build()
            val run = RunContext(Customer("Ana"))

            assertThat(agent.instructions(run)).isEqualTo("Atendés a Ana")
            assertThat(agent.dynamicInstructions(run)).isEqualTo("Son las 10")
            assertThat(agent.dynamicInstructions(run)).isEqualTo("Son las 11")
        }

        @Test
        fun `without them there are none`() {
            val agent = Agent("support").build()

            assertThat(agent.instructions(RunContext())).isNull()
            assertThat(agent.dynamicInstructions(RunContext())).isNull()
        }
    }

    @Nested
    inner class `A handoff` {
        @Test
        fun `is a tool that hands the conversation over to the other agent`() {
            val agent = Agent("support").tools(weather).handoffs("sales").build()
            val transfer = agent.tools.last()

            val result = transfer.call(Json.obj(), ToolContext("call_1", "transfer_to_sales"))

            assertThat(agent.tools.map { it.name }).containsExactly("getWeather", "transfer_to_sales")
            assertThat(agent.handoffs).containsExactly("sales")
            assertThat(transfer.description).isEqualTo("Hands the conversation over to the sales agent.")
            assertThat(transfer.spec(GsonSerializer()).parameters["properties"]).isEqualTo(Json.obj())
            assertThat(result).isEqualTo(ToolResult.text("Transferred to sales.").handoffTo("sales"))
        }

        @Test
        fun `can say when to use it`() {
            val agent = Agent("support").handoff("sales", "For prices and purchases").build()

            assertThat(agent.tools.single().description).isEqualTo("For prices and purchases")
        }

        @Test
        fun `only reads, since the change happens once the step is over`() {
            assertThat(Agent("support").handoffs("sales").build().tools.single().readOnly).isTrue()
        }
    }

    @Nested
    inner class `Its output` {
        @Test
        fun `goes as the format of the answer unless told otherwise`() {
            val output = Agent("support").output<Invoice>().build().output!!

            assertThat(output.mode).isEqualTo(OutputMode.Native)
            assertThat(output.schema(GsonSerializer())).isEqualTo(GsonSerializer().schemaOf<Invoice>())
            assertThat(output.type).isEqualTo(typeOf<Invoice>())
        }

        @Test
        fun `is described by each serializer, for an agent that runs with more than one`() {
            val output = Agent("support").output<Stocked>().build().output!!

            val plain = output.schema(GsonSerializer())
            val registered = output.schema(SkuTool.serializer())

            assertThat(plain.path("properties.sku.pattern")).isNull()
            assertThat(registered.path("properties.sku.pattern")?.asString()).isEqualTo(SkuTool.PATTERN)
        }

        @Test
        fun `or as a tool, which is one more of its tools`() {
            val agent = Agent("support").tools(weather).output<Invoice>(OutputMode.Tool).build()

            assertThat(agent.output!!.mode).isEqualTo(OutputMode.Tool)
            assertThat(agent.tools.map { it.name }).containsExactly("getWeather", "final_result")
        }

        @Test
        fun `is text when it asks for none`() {
            assertThat(Agent("support").build().output).isNull()
        }
    }

    @Nested
    inner class `Its values` {
        @Test
        fun `are found by their class or by an interface they implement`() {
            val agent = Agent("support").with(StoreId(7)).build()

            assertThat(agent.get<StoreId>()).isEqualTo(StoreId(7))
            assertThat(agent.require<Scope>()).isEqualTo(StoreId(7))
            assertThat(agent.get<Customer>()).isNull()
        }

        @Test
        fun `one that is not there says what there is`() {
            val agent = Agent("support").with(StoreId(7)).build()

            assertThatThrownBy { agent.require<Customer>() }
                .hasMessage("The agent support has no Customer. It has: StoreId")
        }

        @Test
        fun `two that are the same type are ambiguous, and asking for it names them`() {
            val agent = Agent("support").with(StoreId(7)).with(TenantId(3)).build()

            assertThatThrownBy { agent.get<Scope>() }
                .hasMessage("The agent support has more than one Scope: StoreId, TenantId. Ask for one of those")
        }
    }

    @Nested
    inner class `It does not build` {
        @Test
        fun `without a name`() {
            assertThatThrownBy { Agent("").build() }.hasMessage("An agent needs a name")
        }

        @Test
        fun `with a name that cannot go in the name of a tool`() {
            // Another agent hands over to this one with transfer_to_<name>
            assertThatThrownBy { Agent("atención al cliente").build() }.hasMessage(
                "The name of an agent goes in the name of the tool that hands over to it, so it takes letters, " +
                    "digits, _ and -, up to 52 of them: atención al cliente",
            )
        }

        @Test
        fun `with a handoff to an agent whose name cannot go in the name of a tool`() {
            assertThatThrownBy { Agent("support").handoffs("sales team").build() }
                .hasMessageEndingWith("up to 52 of them: sales team")
        }

        @Test
        fun `with a handoff to itself`() {
            assertThatThrownBy { Agent("support").handoffs("support").build() }
                .hasMessage("The agent support hands over to itself")
        }

        @Test
        fun `with two tools of the same name`() {
            assertThatThrownBy { Agent("support").tools(weather, WeatherTool()).build() }
                .hasMessage("The agent support has more than one tool called getWeather")
        }

        @Test
        fun `with a tool to search for called like one it always has`() {
            assertThatThrownBy { Agent("support").tools(weather).searchableTools(WeatherTool()).build() }
                .hasMessage("The agent support has more than one tool called getWeather")
        }

        @Test
        fun `with a tool called like one of its handoffs`() {
            assertThatThrownBy { Agent("support").tools(TransferTool()).handoffs("sales").build() }
                .hasMessage("The agent support has more than one tool called transfer_to_sales")
        }

        @Test
        fun `with two values of the same class`() {
            // Nobody could ever ask for either of them
            assertThatThrownBy { Agent("support").with(StoreId(1)).with(StoreId(2)).build() }
                .hasMessage("The agent support has two values of the class StoreId")
        }
    }

    private val weather = WeatherTool()
    private val price = PriceTool()
    private val provider = FakeProvider()
    private val registry = ModelRegistry()
        .addProvider(provider)
        .addAlias("default", "fake/default-model")
        .addAlias("fast", "fake/fast-model")

    interface Scope

    data class StoreId(val value: Int): Scope

    data class TenantId(val value: Int): Scope

    data class Customer(val name: String)

    data class Invoice(val number: String, val total: Int)

    data class City(val city: String)

    data class Stocked(val sku: SkuTool.Sku, val units: Int)

    class WeatherTool: Tool<City>() {
        override val name = "getWeather"
        override val description = "The current weather of a city"

        override fun execute(args: City, context: ToolContext) = ToolResult.text("7 grados")
    }

    class PriceTool: Tool<City>() {
        override val name = "getPrice"
        override val description = "The price of a trip to a city"

        override fun execute(args: City, context: ToolContext) = ToolResult.text("100 dólares")
    }

    class TransferTool: Tool<City>() {
        override val name = "transfer_to_sales"
        override val description = "A tool of the application that happens to have that name"

        override fun execute(args: City, context: ToolContext) = ToolResult.text("ok")
    }

    class FakeProvider: AIProvider {
        override val name = "fake"
        val asked = mutableListOf<String>()

        override fun chatModel(modelId: String): ChatModel {
            asked.add(modelId)
            return FakeChatModel()
        }
    }
}
