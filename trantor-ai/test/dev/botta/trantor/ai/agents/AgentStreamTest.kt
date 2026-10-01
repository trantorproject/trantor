package dev.botta.trantor.ai.agents

import dev.botta.json.Json
import dev.botta.trantor.ai.generation.RunEvent
import dev.botta.trantor.ai.generation.ToolLoop
import dev.botta.trantor.ai.generation.textDeltas
import dev.botta.trantor.ai.models.ModelRegistry
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.ai.testing.FakeChatModel
import dev.botta.trantor.ai.tools.Tool
import dev.botta.trantor.ai.tools.ToolContext
import dev.botta.trantor.ai.tools.ToolOutput
import dev.botta.trantor.ai.tools.ToolResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A run of the agents received as it happens, with the handoff between the steps of one agent and the other. */
class AgentStreamTest {
    @Test
    fun `the handoff comes between the step that handed over and the first one of the other agent`() {
        supportModel.answers(listOf(transfer))
        salesModel.answers(listOf(TextPart("Cuesta 100 dólares")))

        val events = runner.stream(support, question) { team(sales) }.use { it.asSequence().toList() }

        assertThat(events.filterNot { it is RunEvent.Model }).containsExactly(
            RunEvent.StepStarted(1),
            RunEvent.ToolStarted(transfer),
            RunEvent.ToolFinished(
                ToolResultPart("call_1", "transfer_to_sales", ToolOutput.Text("Transferred to sales.")),
            ),
            RunEvent.StepFinished(1),
            RunEvent.Handoff("support", "sales"),
            RunEvent.StepStarted(2),
            RunEvent.StepFinished(2),
        )
    }

    @Test
    fun `the text of both agents arrives, one after the other`() {
        supportModel.streams(listOf(StreamPart.TextDelta("Te paso con ventas")))
        supportModel.answers(listOf(TextPart("Te paso con ventas"), transfer))
        salesModel.streams(listOf(StreamPart.TextDelta("Cuesta 100 dólares")))
        salesModel.answers(listOf(TextPart("Cuesta 100 dólares")))

        val text = runner.stream(support, question) { team(sales) }.use { it.textDeltas().toList() }

        assertThat(text).containsExactly("Te paso con ventas", "Cuesta 100 dólares")
    }

    @Test
    fun `closing it at the handoff does not run the step of the other agent`() {
        supportModel.answers(listOf(transfer))

        runner.stream(support, question) { team(sales) }.use { stream ->
            while (stream.next() !is RunEvent.Handoff) continue
        }

        assertThat(salesModel.requests).isEmpty()
    }

    @Test
    fun `its result says which agent ran each step and which one answered`() {
        supportModel.answers(listOf(transfer))
        salesModel.answers(listOf(TextPart("Cuesta 100 dólares")))

        val result = runner.stream(support, question) { team(sales) }.use { it.result() }

        assertThat(result.steps.map { it.agent.name }).containsExactly("support", "sales")
        assertThat(result.lastAgent).isSameAs(sales)
        assertThat(result.text).isEqualTo("Cuesta 100 dólares")
    }

    @Test
    fun `a generation never tells of a handoff, since it has no team`() {
        supportModel.answers(listOf(ToolCallPart("call_1", "assign", Json.obj())), listOf(TextPart("Listo")))

        val events = ToolLoop(supportModel, listOf(AssignTool())).stream(ChatRequest(question))
            .use { it.asSequence().toList() }

        assertThat(events.filterIsInstance<RunEvent.Handoff>()).isEmpty()
    }

    private val transfer = ToolCallPart("call_1", "transfer_to_sales", Json.obj())
    private val supportModel = FakeChatModel(modelId = "support-model")
    private val salesModel = FakeChatModel(modelId = "sales-model")
    private val support = Agent("support").model(supportModel).instructions("Sos soporte").handoffs("sales").build()
    private val sales = Agent("sales").model(salesModel).instructions("Sos ventas").build()
    private val runner = AgentRunner(ModelRegistry())
    private val question = Message.user("Cuanto sale?")

    class NoArgs

    class AssignTool: Tool<NoArgs>() {
        override val name = "assign"
        override val description = "Assigns the conversation to sales"

        override fun execute(args: NoArgs, context: ToolContext) =
            ToolResult.text("Te paso con ventas").handoffTo("sales")
    }
}
