package dev.botta.trantor.ai.agents

import dev.botta.json.Json
import dev.botta.trantor.ai.generation.MaxStepsExceededError
import dev.botta.trantor.ai.generation.ToolLoop
import dev.botta.trantor.ai.models.ModelRegistry
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.ai.testing.FakeChatModel
import dev.botta.trantor.ai.tools.*
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** An agent handing the conversation over to another of its team, by a declared handoff or from a tool of its own. */
class AgentHandoffTest {
    @Test
    fun `a declared handoff sends the next step with the model, instructions and tools of the other agent`() {
        supportModel.answers(listOf(call("call_1", "transfer_to_sales")))
        salesModel.answers(listOf(TextPart("Cuesta 100 dólares")))

        val result = run(support.handoffs("sales").build())

        assertThat(supportModel.requests).hasSize(1)
        assertThat(salesModel.requests.single().messages.first()).isEqualTo(Message.system("Sos ventas"))
        assertThat(salesModel.requests.single().tools.map { it.name }).containsExactly("getPrice")
        assertThat(result.text).isEqualTo("Cuesta 100 dólares")
    }

    @Test
    fun `each step says which agent ran it, and the last one is the one that answered`() {
        supportModel.answers(listOf(call("call_1", "transfer_to_sales")))
        val agent = support.handoffs("sales").build()

        val result = run(agent)

        assertThat(result.steps.map { it.agent.name }).containsExactly("support", "sales")
        assertThat(result.lastAgent).isSameAs(salesAgent)
    }

    @Test
    fun `a tool of the application hands over with its effect, and the model reads what it answered`() {
        supportModel.answers(listOf(call("call_1", "assign")))

        val result = run(support.tools(assign).build())

        assertThat(assign.assigned).containsExactly("sales")
        assertThat(result.steps[0].step.toolResults.single().output).isEqualTo(ToolOutput.Text("Te paso con ventas"))
        assertThat(result.lastAgent).isSameAs(salesAgent)
    }

    @Test
    fun `the calls of the step that hands over still run with the tools of the agent that asked for them`() {
        supportModel.answers(listOf(call("call_1", "getWeather"), call("call_2", "transfer_to_sales")))

        val result = run(support.tools(weather).handoffs("sales").build())

        assertThat(weather.calls).isEqualTo(1)
        assertThat(result.steps[0].step.toolResults.map { it.isError }).containsExactly(false, false)
    }

    @Test
    fun `with two handoffs in a step the first one wins, and the other is told it was ignored`() {
        supportModel.answers(listOf(call("call_1", "transfer_to_sales"), call("call_2", "transfer_to_billing")))

        val result = run(support.handoffs("sales", "billing").build(), billingAgent)
        val ignored = result.steps[0].step.toolResults[1]

        assertThat(result.lastAgent).isSameAs(salesAgent)
        assertThat(ignored.isError).isTrue()
        assertThat(ignored.output).isEqualTo(
            ToolOutput.Text("The conversation was already handed over to sales, so this handoff to billing was ignored"),
        )
        assertThat(result.warnings.map { it.message }).contains(
            "transfer_to_billing handed the conversation over to billing after transfer_to_sales had handed it " +
                "over to sales in the same step; the first one won",
        )
    }

    @Test
    fun `a handoff to an agent outside the team goes back to the model as an error, naming the team`() {
        supportModel.answers(listOf(call("call_1", "assign")), listOf(TextPart("No pude")))
        assign.to = "billing"

        val result = run(support.tools(assign).build())
        val refused = result.steps[0].step.toolResults.single()

        assertThat(refused.isError).isTrue()
        assertThat(refused.output)
            .isEqualTo(ToolOutput.Text("There is no agent called billing. The team is: support, sales"))
        assertThat(result.lastAgent.name).isEqualTo("support")
    }

    @Test
    fun `the limit of steps counts the steps of every agent`() {
        supportModel.answers(listOf(call("call_1", "transfer_to_sales")))
        salesModel.answers(listOf(call("call_2", "getPrice")), listOf(call("call_3", "getPrice")))

        assertThatThrownBy { runner.run(support.handoffs("sales").build(), question) { team(salesAgent); maxSteps(3) } }
            .isInstanceOf(MaxStepsExceededError::class.java)
        assertThat(supportModel.requests.size + salesModel.requests.size).isEqualTo(3)
    }

    @Test
    fun `the tools know the team they can hand over to`() {
        supportModel.answers(listOf(call("call_1", "assign")))

        run(support.tools(assign).build())

        assertThat(assign.context!!.agentContext().team).containsExactly("support", "sales")
    }

    @Test
    fun `a declared handoff to an agent outside the team fails before calling any model`() {
        assertThatThrownBy { runner.run(support.handoffs("billing").build(), question) { team(salesAgent) } }
            .hasMessage("The agent support hands over to billing, which is not in the team: support, sales")
        assertThat(supportModel.requests).isEmpty()
    }

    @Test
    fun `two agents of the team with the same name fail before calling any model`() {
        val other = Agent("sales").model(salesModel).build()

        assertThatThrownBy { runner.run(support.build(), question) { team(salesAgent, other) } }
            .hasMessage("The team has more than one agent called sales")
    }

    @Test
    fun `a handoff in a generation, which has no team, is left with a warning and the run goes on`() {
        supportModel.answers(listOf(call("call_1", "assign")), listOf(TextPart("Listo")))

        val result = ToolLoop(supportModel, listOf(assign)).run(ChatRequest(question))

        assertThat(result.text).isEqualTo("Listo")
        assertThat(result.warnings.map { it.message }).containsExactly(
            "assign handed the conversation over to sales, but a generation has no agents to hand it to; it was ignored",
        )
    }

    private fun run(agent: Agent, vararg others: Agent) =
        runner.run(agent, question) { team(salesAgent, *others) }

    private fun call(callId: String, tool: String) = ToolCallPart(callId, tool, Json.obj())

    private val supportModel = FakeChatModel(modelId = "support-model")
    private val salesModel = FakeChatModel(modelId = "sales-model")
    private val billingModel = FakeChatModel(modelId = "billing-model")
    private val weather = WeatherTool()
    private val assign = AssignTool()
    private val support = Agent("support").model(supportModel).instructions("Sos soporte")
    private val salesAgent = Agent("sales").model(salesModel).instructions("Sos ventas").tools(PriceTool()).build()
    private val billingAgent = Agent("billing").model(billingModel).build()
    private val runner = AgentRunner(ModelRegistry())
    private val question = Message.user("Cuanto sale?")

    class NoArgs

    class WeatherTool: Tool<NoArgs>() {
        override val name = "getWeather"
        override val description = "The weather"
        override val readOnly = true

        var calls = 0

        override fun execute(args: NoArgs, context: ToolContext): ToolResult {
            calls++
            return ToolResult.text("7 grados")
        }
    }

    class PriceTool: Tool<NoArgs>() {
        override val name = "getPrice"
        override val description = "The price"

        override fun execute(args: NoArgs, context: ToolContext) = ToolResult.text("100 dólares")
    }

    /** Assigns the conversation to an area of the application, which is also handing it over to its agent. */
    class AssignTool: Tool<NoArgs>() {
        override val name = "assign"
        override val description = "Assigns the conversation to sales"

        var to = "sales"
        val assigned = mutableListOf<String>()
        var context: ToolContext? = null

        override fun execute(args: NoArgs, context: ToolContext): ToolResult {
            assigned.add(to)
            this.context = context
            return ToolResult.text("Te paso con ventas").handoffTo(to)
        }
    }
}
