package dev.botta.trantor.ai.agents

import dev.botta.json.Json
import dev.botta.json.values.JsonObject
import dev.botta.trantor.ai.generation.ToolFailure
import dev.botta.trantor.ai.models.ModelRegistry
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.ai.testing.FakeChatModel
import dev.botta.trantor.ai.tools.*
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** What a run calls around its steps: to watch them, and to change the request of a call or the args of a tool. */
class AgentHooksTest {
    @Test
    fun `every point is called in its moment, with what it is about`() {
        supportModel.answers(listOf(weatherCall), listOf(TextPart("7 grados")))
        val log = Log("hook")

        runner.run(support.hooks(log).build(), question)

        assertThat(log.lines).containsExactly(
            "hook beforeRun support: Que temperatura hay?",
            "hook beforeModel support 1",
            "hook afterModel support 1: getWeather",
            "hook beforeTool support getWeather",
            "hook afterTool support getWeather: 7 grados en Bariloche",
            "hook beforeModel support 2",
            "hook afterModel support 2: 7 grados",
            "hook afterRun support: 7 grados",
        )
    }

    @Test
    fun `beforeModel changes the request of that call, and the conversation keeps what it had`() {
        supportModel.answers(listOf(weatherCall), listOf(TextPart("7 grados")))
        val context = object: AgentHooks {
            override fun beforeModel(step: AgentHookContext, request: ChatRequest) =
                if (step.step == 1) request.copy(messages = request.messages + Message.user("Soy de Córdoba")) else request
        }

        runner.run(support.hooks(context).build(), question)

        assertThat(supportModel.requests[0].messages.last()).isEqualTo(Message.user("Soy de Córdoba"))
        assertThat(supportModel.requests[1].messages).doesNotContain(Message.user("Soy de Córdoba"))
    }

    @Test
    fun `beforeTool changes the args the tool gets, and the model's call stays as it asked`() {
        supportModel.answers(listOf(weatherCall), listOf(TextPart("7 grados")))
        val everywhereIsCordoba = object: AgentHooks {
            override fun beforeTool(call: ToolCallPart, context: AgentToolContext) = Json.obj("city" to "Córdoba")
        }

        val result = runner.run(support.hooks(everywhereIsCordoba).build(), question)

        assertThat(result.steps[0].step.toolResults.single().output).isEqualTo(ToolOutput.Text("7 grados en Córdoba"))
        assertThat((result.newMessages[0] as Message.Assistant).parts).containsExactly(weatherCall)
    }

    @Test
    fun `args changed into something the tool does not take go back to the model, as the model's would`() {
        supportModel.answers(listOf(weatherCall), listOf(TextPart("No pude")))
        val broken = object: AgentHooks {
            override fun beforeTool(call: ToolCallPart, context: AgentToolContext) = Json.obj("town" to "Córdoba")
        }

        val result = runner.run(support.hooks(broken).build(), question)

        assertThat(result.steps[0].step.toolResults.single().isError).isTrue()
    }

    @Test
    fun `the global hooks run first, then those of the agent, then those of the run`() {
        supportModel.answers(listOf(TextPart("Hola")))
        val log = mutableListOf<String>()
        val global = GlobalAgentHooks().add(Log("global", log))

        AgentRunner(ModelRegistry(), hooks = global).run(support.hooks(Log("agent", log)).build(), question) {
            hooks(Log("run", log))
        }

        assertThat(log.filter { "beforeModel" in it })
            .containsExactly("global beforeModel support 1", "agent beforeModel support 1", "run beforeModel support 1")
    }

    @Test
    fun `each hook gets what the one before it returned`() {
        supportModel.answers(listOf(TextPart("Hola")))
        fun adding(text: String) = object: AgentHooks {
            override fun beforeModel(step: AgentHookContext, request: ChatRequest) =
                request.copy(messages = request.messages + Message.user(text))
        }

        runner.run(support.hooks(adding("uno"), adding("dos")).build(), question)

        assertThat(supportModel.requests.single().messages.takeLast(2))
            .containsExactly(Message.user("uno"), Message.user("dos"))
    }

    @Test
    fun `a hook that throws fails the run, even around a tool`() {
        supportModel.answers(listOf(weatherCall))
        val failing = object: AgentHooks {
            override fun beforeTool(call: ToolCallPart, context: AgentToolContext): JsonObject =
                throw IllegalStateException("No hay clima hoy")
        }

        assertThatThrownBy { runner.run(support.hooks(failing).build(), question) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("No hay clima hoy")
    }

    @Test
    fun `afterRun is not called when the run fails`() {
        supportModel.answers(listOf(weatherCall), listOf(weatherCall))
        val log = Log("hook")

        assertThatThrownBy { runner.run(support.hooks(log).build(), question) { maxSteps(2) } }
        assertThat(log.lines.filter { "afterRun" in it }).isEmpty()
    }

    @Test
    fun `the hooks of an agent run while it has the conversation, and change with the handoff`() {
        supportModel.answers(listOf(ToolCallPart("call_1", "transfer_to_sales", Json.obj())))
        salesModel.answers(listOf(TextPart("Cuesta 100 dólares")))
        val log = mutableListOf<String>()
        val sales = Agent("sales").model(salesModel).hooks(Log("sales", log)).build()

        runner.run(support.handoffs("sales").hooks(Log("support", log)).build(), question) { team(sales) }

        assertThat(log.filter { "Model" in it || "Tool" in it }).containsExactly(
            "support beforeModel support 1",
            "support afterModel support 1: transfer_to_sales",
            "support beforeTool support transfer_to_sales",
            "support afterTool support transfer_to_sales: Transferred to sales.",
            "sales beforeModel sales 2",
            "sales afterModel sales 2: Cuesta 100 dólares",
        )
        // The run starts with support and ends with sales
        assertThat(log.filter { "Run" in it })
            .containsExactly("support beforeRun support: Que temperatura hay?", "sales afterRun sales: Cuesta 100 dólares")
    }

    @Test
    fun `a stream calls the same hooks, and afterRun once it is read to the end`() {
        supportModel.answers(listOf(weatherCall), listOf(TextPart("7 grados")))
        val log = Log("hook")

        runner.stream(support.hooks(log).build(), question).use { stream ->
            while (stream.hasNext()) {
                assertThat(log.lines.filter { "afterRun" in it }).isEmpty()
                stream.next()
            }
        }

        assertThat(log.lines).containsExactly(
            "hook beforeRun support: Que temperatura hay?",
            "hook beforeModel support 1",
            "hook afterModel support 1: getWeather",
            "hook beforeTool support getWeather",
            "hook afterTool support getWeather: 7 grados en Bariloche",
            "hook beforeModel support 2",
            "hook afterModel support 2: 7 grados",
            "hook afterRun support: 7 grados",
        )
    }

    @Test
    fun `a stream closed before its end never calls afterRun`() {
        supportModel.answers(listOf(weatherCall), listOf(TextPart("7 grados")))
        val log = Log("hook")

        runner.stream(support.hooks(log).build(), question).use { it.next() }

        assertThat(log.lines.filter { "afterRun" in it }).isEmpty()
    }

    /** Writes down what it is called with, one line each. */
    class Log(private val name: String, val lines: MutableList<String> = mutableListOf()): AgentHooks {
        override fun beforeRun(run: AgentHookContext, conversation: List<Message>) {
            lines.add("$name beforeRun ${run.agent.name}: ${(conversation.last() as Message.User).parts.textOf()}")
        }

        override fun beforeModel(step: AgentHookContext, request: ChatRequest): ChatRequest {
            lines.add("$name beforeModel ${step.agent.name} ${step.step}")
            return request
        }

        override fun afterModel(step: AgentHookContext, response: ChatResponse) {
            val said = response.toolCalls.joinToString { it.toolName }.ifEmpty { response.text }
            lines.add("$name afterModel ${step.agent.name} ${step.step}: $said")
        }

        override fun beforeTool(call: ToolCallPart, context: AgentToolContext): JsonObject {
            lines.add("$name beforeTool ${context.agent.name} ${call.toolName}")
            return call.input
        }

        override fun afterTool(result: ToolResultPart, failure: ToolFailure?, context: AgentToolContext) {
            lines.add("$name afterTool ${context.agent.name} ${result.toolName}: ${(result.output as ToolOutput.Text).value}")
        }

        override fun afterRun(run: AgentHookContext, result: AgentRunResult) {
            lines.add("$name afterRun ${run.agent.name}: ${result.text}")
        }

        private fun List<Part>.textOf() = filterIsInstance<TextPart>().joinToString("") { it.text }
    }

    private val weatherCall = ToolCallPart("call_1", "getWeather", Json.obj("city" to "Bariloche"))
    private val supportModel = FakeChatModel(modelId = "support-model")
    private val salesModel = FakeChatModel(modelId = "sales-model")
    private val support = Agent("support").model(supportModel).instructions("Sos soporte").tools(WeatherTool())
    private val runner = AgentRunner(ModelRegistry())
    private val question = Message.user("Que temperatura hay?")

    class WeatherTool: Tool<WeatherTool.Args>(Args.serializer()) {
        override val name = "getWeather"
        override val description = "The weather"

        override fun execute(args: Args, context: ToolContext) = ToolResult.text("7 grados en ${args.city}")

        @Serializable
        data class Args(val city: String)
    }
}
