@file:Suppress("ClassName")

package dev.botta.trantor.ai.agents

import dev.botta.json.Json
import dev.botta.trantor.ai.errors.NoObjectGeneratedError
import dev.botta.trantor.ai.generation.PendingCall
import dev.botta.trantor.ai.generation.ToolLoopApprovalTest.RefundTool
import dev.botta.trantor.ai.history.InMemorySession
import dev.botta.trantor.ai.models.ModelRegistry
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.ai.testing.FakeChatModel
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** A run of the agents that ends waiting for a person to approve some of its calls. */
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

    private val model = FakeChatModel()
    private val runner = AgentRunner(ModelRegistry())
    private val support = Agent("support").model(model).tools(RefundTool(approvalOver = 100))
    private val question = Message.user("Devolveme la compra")
    private val refundCall = ToolCallPart("call_1", "refund", Json.obj("amount" to 500))

    @Serializable
    data class Refund(val amount: Int)
}
