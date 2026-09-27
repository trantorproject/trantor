@file:Suppress("ClassName")

package dev.botta.trantor.ai.agents

import dev.botta.json.Json
import dev.botta.trantor.ai.errors.NoObjectGeneratedError
import dev.botta.trantor.ai.generation.Approve
import dev.botta.trantor.ai.generation.PendingCall
import dev.botta.trantor.ai.generation.Reject
import dev.botta.trantor.ai.generation.ToolLoopApprovalTest.RefundTool
import dev.botta.trantor.ai.history.InMemorySession
import dev.botta.trantor.ai.models.ModelRegistry
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.ai.testing.FakeChatModel
import dev.botta.trantor.ai.tools.ToolOutput
import kotlinx.serialization.Serializable
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

    private val model = FakeChatModel()
    private val runner = AgentRunner(ModelRegistry())
    private val support = Agent("support").model(model).tools(RefundTool(approvalOver = 100))
    private val question = Message.user("Devolveme la compra")
    private val refundCall = ToolCallPart("call_1", "refund", Json.obj("amount" to 500))
    private val refunded = ToolResultPart("call_1", "refund", ToolOutput.Text("Devueltos 500"))

    /** What a run that paused on the refund left in its session. */
    private val paused = listOf(question, Message.Assistant(listOf(refundCall), "support"))

    @Serializable
    data class Refund(val amount: Int)
}
