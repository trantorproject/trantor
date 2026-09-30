@file:Suppress("ClassName")

package dev.botta.trantor.ai.agents

import dev.botta.json.Json
import dev.botta.trantor.ai.RunContext
import dev.botta.trantor.ai.errors.NoObjectGeneratedError
import dev.botta.trantor.ai.generation.MaxStepsExceededError
import dev.botta.trantor.ai.models.CallOptions
import dev.botta.trantor.ai.models.ModelRegistry
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.ai.providers.AIProvider
import dev.botta.trantor.ai.providers.openai.OpenAIOptions
import dev.botta.trantor.ai.providers.openai.ServiceTiers
import dev.botta.trantor.ai.testing.FakeChatModel
import dev.botta.trantor.ai.tools.*
import kotlinx.serialization.Serializable
import dev.botta.trantor.serialization.gson.GsonSerializer
import dev.botta.trantor.serialization.gson.adapters.StringValueSerializer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

class AgentRunnerTest {
    @Test
    fun `runs an agent with its tools to the answer`() {
        model.answers(listOf(weatherCall), listOf(TextPart("Hacen 7 grados")))

        val result = runner.run(support().tools(weather).build(), Message.user("Que temperatura hay?"))

        assertThat(weather.cities).containsExactly("Bariloche")
        assertThat(result.text).isEqualTo("Hacen 7 grados")
        assertThat(result.newMessages).hasSize(3)
    }

    @Test
    fun `its model comes from the registry`() {
        runner.run(Agent("support").model("fast").build(), Message.user("Hola"))

        assertThat(provider.asked).containsExactly("fast-model")
    }

    @Test
    fun `its instructions go first and its dynamic instructions last, asked again on every step`() {
        model.answers(listOf(weatherCall), listOf(TextPart("Hacen 7 grados")))
        var hour = 10
        val agent = support()
            .instructions { run -> "Atendés a ${run.require<Customer>().name}" }
            .dynamicInstructions { "Son las ${hour++}" }
            .tools(weather)
            .build()
        val question = Message.user("Que temperatura hay?")

        runner.run(agent, question) { context(RunContext(Customer("Ana"))) }

        assertThat(model.requests[0].messages).containsExactly(Message.system("Atendés a Ana"), question)
        assertThat(model.requests.map { it.dynamicSystem }).containsExactly("Son las 10", "Son las 11")
    }

    @Test
    fun `the instructions are not part of the conversation`() {
        val result = runner.run(support().instructions("Sos soporte").build(), Message.user("Hola"))

        assertThat(result.newMessages.filterIsInstance<Message.System>()).isEmpty()
    }

    @Test
    fun `the conversation so far goes after the instructions`() {
        val history = listOf(Message.user("Hola"), Message.assistant("Hola, en que te ayudo?"), Message.user("Precios"))

        runner.run(support().instructions("Sos soporte").build(), history)

        assertThat(model.request?.messages).containsExactly(Message.system("Sos soporte"), *history.toTypedArray())
    }

    @Test
    fun `its settings go on the request`() {
        runner.run(support().settings { temperature = 0.2 }.build(), Message.user("Hola"))

        assertThat(model.request?.settings?.temperature).isEqualTo(0.2)
    }

    @Test
    fun `the options of the run go after the ones of the agent, so the run wins where both say something`() {
        val ofTheAgent = OpenAIOptions(serviceTier = ServiceTiers.Flex, promptCacheKey = "agente")
        val ofTheRun = OpenAIOptions(promptCacheKey = "chat-1")

        runner.run(support().options(ofTheAgent).build(), Message.user("Hola")) { options(ofTheRun) }

        assertThat(model.request?.providerOptions?.forProvider("openai")).containsExactly(ofTheAgent, ofTheRun)
    }

    @Test
    fun `the limit of steps and the call options are the run's`() {
        model.answers(listOf(weatherCall), listOf(weatherCall))
        val options = CallOptions(timeout = 60.seconds)

        assertThatThrownBy {
            runner.run(support().tools(weather).build(), Message.user("Hola")) {
                maxSteps(2)
                callOptions(options)
            }
        }.isInstanceOf(MaxStepsExceededError::class.java)
        assertThat(model.options).isSameAs(options)
    }

    @Test
    fun `each step says which agent ran it, and each run has an id of its own`() {
        model.answers(listOf(weatherCall), listOf(TextPart("Hacen 7 grados")))
        val agent = support().tools(weather).build()

        val result = runner.run(agent, Message.user("Que temperatura hay?"))
        val other = runner.run(agent, Message.user("Hola"))

        assertThat(result.steps.map { it.agent }).containsExactly(agent, agent)
        assertThat(result.steps[0].step.toolResults).hasSize(1)
        assertThat(result.lastAgent).isSameAs(agent)
        assertThat(result.runId).isNotBlank().isNotEqualTo(other.runId)
    }

    @Nested
    inner class `Its tools` {
        @Test
        fun `know the agent and the run they are called from`() {
            model.answers(listOf(weatherCall))
            val agent = support().tools(weather).build()
            val run = RunContext(Customer("Ana"))

            val result = runner.run(agent, Message.user("Hola")) { context(run) }
            val context = weather.context!!.agentContext()

            assertThat(context.agent).isSameAs(agent)
            assertThat(context.runId).isEqualTo(result.runId)
            assertThat(context.run).isSameAs(run)
            assertThat(context.callId).isEqualTo("call_1")
        }

        @Test
        fun `one that needs an agent says so when it is called without one`() {
            val context = ToolContext("call_1", "assign")

            assertThatThrownBy { context.agentContext() }
                .hasMessage("assign only runs inside an agent, and it was called without one")
        }
    }

    @Nested
    inner class `Its output` {
        @Test
        fun `as the format of the answer goes on every step, and the answer is the object`() {
            model.answers(listOf(weatherCall), listOf(TextPart("""{"city":"Bariloche","celsius":7}""")))
            val agent = support().tools(weather).output<Weather>().build()

            val result = runner.run(agent, Message.user("Que temperatura hay?"))

            assertThat(model.requests.map { it.output }).allMatch { it is OutputSpec.Json }
            assertThat(result.output<Weather>()).isEqualTo(Weather("Bariloche", 7))
        }

        @Test
        fun `as a tool is the args of the call that ended the run`() {
            model.answers(listOf(weatherCall), listOf(finalResult("""{"city":"Bariloche","celsius":7}""")))
            val agent = support().tools(weather).output<Weather>(OutputMode.Tool).build()

            val result = runner.run(agent, Message.user("Que temperatura hay?"))

            assertThat(model.requests[0].output).isEqualTo(OutputSpec.Text)
            assertThat(model.requests[0].tools.map { it.name }).containsExactly("getWeather", "final_result")
            assertThat(result.output<Weather>()).isEqualTo(Weather("Bariloche", 7))
            assertThat(result.newMessages.last()).isInstanceOf(Message.Tool::class.java)
        }

        @Test
        fun `as a tool is read by the serializer of the run, with the types the application registered`() {
            model.answers(listOf(finalResult("""{"code":"ABC-1"}""")))
            val serializer = GsonSerializer().apply {
                registerTypeAdapter(Code::class.java, StringValueSerializer({ Code(it) }, { it.value }))
            }
            val agent = support().output<Picked>(OutputMode.Tool).build()

            val result = AgentRunner(registry, serializer = serializer).run(agent, Message.user("Cual elegis?"))

            assertThat(result.output<Picked>()).isEqualTo(Picked(Code("ABC-1")))
        }

        @Test
        fun `as a tool, an answer without calling it gets a reminder`() {
            model.answers(listOf(TextPart("Hacen 7")), listOf(finalResult("""{"city":"Bariloche","celsius":7}""")))
            val agent = support().output<Weather>(OutputMode.Tool).build()

            val result = runner.run(agent, Message.user("Que temperatura hay?"))

            assertThat(model.requests[1].messages.last())
                .isEqualTo(Message.user("Please include your response in a call to final_result."))
            assertThat(result.output<Weather>()).isEqualTo(Weather("Bariloche", 7))
        }

        @Test
        fun `as a tool, answering without it again leaves no object`() {
            model.answers(listOf(TextPart("Hacen 7")), listOf(TextPart("Ya te dije, 7")))
            val agent = support().output<Weather>(OutputMode.Tool).build()

            val result = runner.run(agent, Message.user("Que temperatura hay?"))

            assertThatThrownBy { result.output<Weather>() }
                .isInstanceOf(NoObjectGeneratedError::class.java)
                .hasMessage("The model answered without calling final_result")
        }
    }

    private fun support() = Agent("support").model(model)

    private fun finalResult(args: String) = ToolCallPart("call_9", "final_result", Json.parse(args).asObject()!!)

    private val model = FakeChatModel()
    private val provider = FakeProvider(model)
    private val registry = ModelRegistry()
        .addProvider(provider)
        .addAlias("default", "fake/default-model")
        .addAlias("fast", "fake/fast-model")
    private val runner = AgentRunner(registry)
    private val weather = WeatherTool()
    private val weatherCall = ToolCallPart("call_1", "getWeather", Json.obj("city" to "Bariloche"))

    data class Customer(val name: String)

    @Serializable
    data class Weather(val city: String, val celsius: Int)

    // Serializable only until structured output leaves kotlinx, which still reads the answer in the native mode
    @Serializable
    data class Picked(val code: Code)

    @Serializable
    data class Code(val value: String)

    class FakeProvider(private val model: FakeChatModel): AIProvider {
        override val name = "fake"
        val asked = mutableListOf<String>()

        override fun chatModel(modelId: String): ChatModel {
            asked.add(modelId)
            return model
        }
    }

    class WeatherTool: Tool<WeatherTool.Args>() {
        override val name = "getWeather"
        override val description = "The current weather of a city"

        val cities = mutableListOf<String>()
        var context: ToolContext? = null

        override fun execute(args: Args, context: ToolContext): ToolResult {
            cities.add(args.city)
            this.context = context
            return ToolResult.text("7 grados")
        }

        @Serializable
        data class Args(val city: String)
    }
}
