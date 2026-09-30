@file:Suppress("ClassName")

package dev.botta.trantor.ai.agents

import dev.botta.json.Json
import dev.botta.trantor.ai.errors.NestedApprovalError
import dev.botta.trantor.ai.errors.NoObjectGeneratedError
import dev.botta.trantor.ai.generation.Approve
import dev.botta.trantor.ai.generation.PendingCall
import dev.botta.trantor.ai.generation.Reject
import dev.botta.trantor.ai.generation.RunEvent
import dev.botta.trantor.ai.generation.ToolLoopApprovalTest.RefundTool
import dev.botta.trantor.ai.history.InMemorySession
import dev.botta.trantor.ai.models.ModelRegistry
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.ai.testing.FakeChatModel
import dev.botta.trantor.ai.tools.ToolOutput
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** A run of the agents that ends waiting for a person to approve some of its calls, and the one that picks it up. */
class AgentApprovalTest {
    @Nested
    inner class `a paused run` {
        @Test
        fun `says which agent made the calls that wait`() {
            model.answers(listOf(refundCall))

            val result = runner.run(support.build(), question)

            assertThat(result.paused).isTrue()
            assertThat(result.pending).containsExactly(PendingCall(refundCall, agent = "support"))
        }

        @Test
        fun `keeps its session, with the calls waiting for approval`() {
            model.answers(listOf(refundCall))
            val session = InMemorySession()

            val result = runner.run(support.build(), question) { session(session) }

            assertThat(session.load()).containsExactly(question, Message.Assistant(listOf(refundCall), "support"))
            assertThat(result.newMessages).containsExactly(Message.Assistant(listOf(refundCall), "support"))
        }

        @Test
        fun `is not checked by the output guardrails, since it has no answer yet`() {
            model.answers(listOf(refundCall))
            val checked = mutableListOf<String>()
            val strict = OutputGuardrail("strict") { _, result ->
                checked.add(result.text)
                GuardrailVerdict.Trip("Never")
            }

            val result = runner.run(support.outputGuardrails(strict).build(), question)

            assertThat(checked).isEmpty()
            assertThat(result.paused).isTrue()
        }

        @Test
        fun `is heard of after the run, like one that ended`() {
            model.answers(listOf(TextPart("Te hago la devolucion"), refundCall))
            val log = AgentHooksTest.Log("hook")

            runner.run(support.hooks(log).build(), question)

            assertThat(log.lines.filter { "afterRun" in it })
                .containsExactly("hook afterRun support: Te hago la devolucion")
        }

        @Test
        fun `has no output, and says it is waiting for approval`() {
            model.answers(listOf(refundCall))

            val result = runner.run(support.output<Refund>().build(), question)

            assertThatThrownBy { result.output<Refund>() }
                .isInstanceOf(NoObjectGeneratedError::class.java)
                .hasMessageContaining("waiting for approval of refund")
        }

        @Test
        fun `in a stream, keeps its session without the output guardrails`() {
            model.answers(listOf(refundCall))
            val session = InMemorySession()
            val strict = OutputGuardrail("strict") { _, _ -> GuardrailVerdict.Trip("Never") }

            val result = runner.stream(support.outputGuardrails(strict).build(), question) { session(session) }
                .use { it.result() }

            assertThat(result.paused).isTrue()
            assertThat(session.load()).containsExactly(question, Message.Assistant(listOf(refundCall), "support"))
        }
    }

    @Nested
    inner class `a paused stream` {
        @Test
        fun `tells which agent made the call that waits`() {
            model.answers(listOf(refundCall))

            val events = runner.stream(support.build(), question).use { it.asSequence().toList() }

            assertThat(events.filterIsInstance<RunEvent.ApprovalRequested>())
                .containsExactly(RunEvent.ApprovalRequested(PendingCall(refundCall, "support")))
        }

        @Test
        fun `with the text held back, gives the text of the step before its approval requests`() {
            model.streams(listOf(StreamPart.TextDelta("Te devuelvo")))
            model.answers(listOf(TextPart("Te devuelvo"), refundCall))
            val passing = OutputGuardrail("passing") { _, _ -> GuardrailVerdict.Pass }

            val events = runner.stream(support.outputGuardrails(passing).build(), question)
                .use { it.asSequence().toList() }

            assertThat(events).containsExactly(
                RunEvent.StepStarted(1),
                RunEvent.Model(StreamPart.TextDelta("Te devuelvo")),
                RunEvent.ApprovalRequested(PendingCall(refundCall, "support")),
                RunEvent.StepFinished(1),
            )
        }
    }

    @Nested
    inner class `a resumed run` {
        @Test
        fun `keeps the results of the calls it answered in its session, and then its answer`() {
            model.answers(listOf(TextPart("Listo, te devolvi 500")))
            val session = InMemorySession(paused)

            runner.run(support.build()) {
                session(session)
                decisions(Approve("call_1"))
            }

            assertThat(session.load()).isEqualTo(
                paused + Message.Tool(listOf(refunded)) +
                    Message.Assistant(listOf(TextPart("Listo, te devolvi 500")), "support"),
            )
        }

        @Test
        fun `with a new message, keeps the results before it`() {
            model.answers(listOf(TextPart("Llega el jueves")))
            val session = InMemorySession(paused)
            val next = Message.user("Y cuando llega?")

            runner.run(support.build(), next) {
                session(session)
                decisions(Reject("call_1", "Solo 100"))
            }

            val rejected = ToolResultPart("call_1", "refund", ToolOutput.Text("Solo 100"), isError = true)
            assertThat(session.load()).isEqualTo(
                paused + Message.Tool(listOf(rejected)) + next +
                    Message.Assistant(listOf(TextPart("Llega el jueves")), "support"),
            )
        }

        @Test
        fun `in a stream, keeps them too`() {
            model.answers(listOf(TextPart("Listo")))
            val session = InMemorySession(paused)

            runner.stream(support.build()) {
                session(session)
                decisions(Approve("call_1"))
            }.use { it.result() }

            assertThat(session.load()).isEqualTo(
                paused + Message.Tool(listOf(refunded)) + Message.Assistant(listOf(TextPart("Listo")), "support"),
            )
        }
    }

    @Nested
    inner class `in a team` {
        @Test
        fun `a run that picks up calls waiting for approval goes on with the agent that made them`() {
            salesModel.answers(listOf(TextPart("Listo, te devolvi 500")))
            val afterAHandoff = listOf(
                question,
                Message.Assistant(listOf(handoffCall), "support"),
                Message.Tool(listOf(handedOver)),
                Message.Assistant(listOf(refundCall), "sales"),
            )

            val result = runner.run(support.handoffs("sales").build(), afterAHandoff) {
                team(sales.build())
                decisions(Approve("call_1"))
            }

            assertThat(salesRefund.refunded).containsExactly(500)
            assertThat(result.lastAgent.name).isEqualTo("sales")
            assertThat(model.requests).isEmpty()
        }

        @Test
        fun `the agent that made them has to be in the team, or the run fails before anything runs`() {
            val bySales = listOf(question, Message.Assistant(listOf(refundCall), "sales"))

            assertThatThrownBy { runner.run(support.build(), bySales) { decisions(Approve("call_1")) } }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("made by sales")
            assertThat(model.requests).isEmpty()
            assertThat(salesModel.requests).isEmpty()
        }

        @Test
        fun `a handoff in the step that paused is not done, and the conversation stays with the agent`() {
            model.answers(listOf(handoffCall, refundCall))

            val result = runner.run(support.handoffs("sales").build(), question) { team(sales.build()) }

            val handoff = result.steps.single().step.toolResults.single()
            assertThat(handoff.isError).isTrue()
            assertThat((handoff.output as ToolOutput.Text).value).contains("not handed over")
            assertThat(result.steps.single().step.handoff).isNull()
            assertThat(result.pending).containsExactly(PendingCall(refundCall, "support"))
            assertThat(result.warnings.map { it.message }).anyMatch { "not handed over" in it }
        }

        @Test
        fun `an approved handoff hands the conversation over before the first step`() {
            salesModel.answers(listOf(TextPart("Hola, soy ventas")))
            val waiting = listOf(question, Message.Assistant(listOf(handoffCall), "support"))

            val result = runner.run(support.handoffs("sales").build(), waiting) {
                team(sales.build())
                decisions(Approve("call_7"))
            }

            assertThat(model.requests).isEmpty()
            assertThat(salesModel.requests).hasSize(1)
            assertThat(result.result.resolved?.results).containsExactly(handedOver)
            assertThat(result.lastAgent.name).isEqualTo("sales")
            assertThat(result.text).isEqualTo("Hola, soy ventas")
        }

        @Test
        fun `and a stream tells of it after the call ran`() {
            salesModel.answers(listOf(TextPart("Hola, soy ventas")))
            val waiting = listOf(question, Message.Assistant(listOf(handoffCall), "support"))

            val events = runner.stream(support.handoffs("sales").build(), waiting) {
                team(sales.build())
                decisions(Approve("call_7"))
            }.use { it.asSequence().toList() }

            assertThat(events.take(4)).containsExactly(
                RunEvent.ToolStarted(handoffCall),
                RunEvent.ToolFinished(handedOver),
                RunEvent.Handoff("support", "sales"),
                RunEvent.StepStarted(1),
            )
        }

        private val salesModel = FakeChatModel(modelId = "sales-model")
        private val salesRefund = RefundTool(approvalOver = 100)
        private val sales = Agent("sales").model(salesModel).tools(salesRefund)
        private val handoffCall = ToolCallPart("call_7", "transfer_to_sales", Json.obj())
        private val handedOver = ToolResultPart("call_7", "transfer_to_sales", ToolOutput.Text("Transferred to sales."))
    }

    @Nested
    inner class `an agent used as a tool` {
        @Test
        fun `can wait for approval before it runs, asked by a guardrail`() {
            model.answers(listOf(researchCall))
            val careful = ToolGuardrail("careful") { call, _ ->
                if (call.toolName == "researcher") ToolGuardrailVerdict.AskForApproval("It is expensive")
                else GuardrailVerdict.Pass
            }

            val result = runner.run(support.tools(researcherTool).toolGuardrails(careful).build(), question)

            assertThat(researcherModel.requests).isEmpty()
            assertThat(result.pending).containsExactly(PendingCall(researchCall, "support", "It is expensive"))
        }

        @Test
        fun `whose run waits for approval fails the run, since that is not supported`() {
            model.answers(listOf(researchCall))
            researcherModel.answers(listOf(refundCall))

            assertThatThrownBy { runner.run(support.tools(researcherTool).build(), question) }
                .isInstanceOf(NestedApprovalError::class.java)
                .hasMessageContaining("researcher")
        }

        private val researcherModel = FakeChatModel(modelId = "researcher-model")
        private val researcherTool = Agent("researcher")
            .model(researcherModel)
            .tools(RefundTool(approvalOver = 100))
            .build()
            .asTool(runner, "Researches what it is asked")
        private val researchCall = ToolCallPart("call_5", "researcher", Json.obj("task" to "Devolve 500"))
    }

    private val model = FakeChatModel()
    private val runner = AgentRunner(ModelRegistry())
    private val support = Agent("support").model(model).tools(RefundTool(approvalOver = 100))
    private val question = Message.user("Devolveme la compra")
    private val refundCall = ToolCallPart("call_1", "refund", Json.obj("amount" to 500))
    private val refunded = ToolResultPart("call_1", "refund", ToolOutput.Text("Devueltos 500"))

    /** What a run that paused on the refund left in its session. */
    private val paused = listOf(question, Message.Assistant(listOf(refundCall), "support"))

    data class Refund(val amount: Int)
}
