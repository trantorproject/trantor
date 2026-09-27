@file:Suppress("ClassName")

package dev.botta.trantor.ai.generation

import dev.botta.json.Json
import dev.botta.json.values.JsonObject
import dev.botta.trantor.ai.errors.NoPendingCallError
import dev.botta.trantor.ai.generation.ToolLoop.Companion.GENERIC_FAILURE
import dev.botta.trantor.ai.generation.ToolLoopApprovalTest.RefundTool
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.ai.testing.FakeChatModel
import dev.botta.trantor.ai.tools.*
import dev.botta.trantor.ai.tools.ToolOutput.Text
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowableOfType
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** A run that picks up one that paused, with what a person decided about the calls it left waiting. */
class ToolLoopResumeTest {
    @Nested
    inner class `an approved call` {
        @Test
        fun `runs, its result goes right after the answer that made it, and the model is called again`() {
            model.answers(listOf(TextPart("Listo, te devolvi 500")))

            val result = loop().run(ChatRequest(paused), decisions = listOf(Approve("call_2")))

            assertThat(refund.refunded).containsExactly(500)
            assertThat(model.requests.single().messages).isEqualTo(paused + Message.Tool(listOf(refunded)))
            assertThat(result.text).isEqualTo("Listo, te devolvi 500")
        }

        @Test
        fun `goes through the hooks of its tool, and does not ask for approval again`() {
            val seen = mutableListOf<String>()
            val hooks = object: StepHooks {
                override fun beforeTool(call: ToolCallPart): JsonObject {
                    seen.add("before ${call.callId}")
                    return call.input
                }

                override fun afterTool(result: ToolResultPart, failure: ToolFailure?) {
                    seen.add("after ${result.callId}")
                }
            }

            loop(hooks).run(ChatRequest(paused), decisions = listOf(Approve("call_2")))

            assertThat(seen).containsExactly("before call_2", "after call_2")
            assertThat(refund.askedFor).isEmpty()
        }

        @Test
        fun `that fails is answered as a failure, and kept in the failures of the run`() {
            refund.failWith = IllegalStateException("The payments service is down")

            val result = loop().run(ChatRequest(paused), decisions = listOf(Approve("call_2")))

            val failed = ToolResultPart("call_2", "refund", Text(GENERIC_FAILURE), isError = true)
            assertThat(model.requests.single().messages.last()).isEqualTo(Message.Tool(listOf(failed)))
            assertThat(result.toolFailures.map { it.callId }).containsExactly("call_2")
        }
    }

    @Nested
    inner class `a rejected call` {
        @Test
        fun `is answered as an error with the message of the decision`() {
            loop().run(ChatRequest(paused), decisions = listOf(Reject("call_2", "El cliente no quiere")))

            assertThat(refund.refunded).isEmpty()
            assertThat(answerTo("call_2")).isEqualTo("El cliente no quiere")
            assertThat(model.requests.single().messages.last().let { (it as Message.Tool).results.single().isError })
                .isTrue()
        }

        @Test
        fun `or with one that asks the model not to try it again`() {
            loop().run(ChatRequest(paused), decisions = listOf(Reject("call_2")))

            assertThat(answerTo("call_2")).isEqualTo(ToolLoop.NOT_APPROVED)
        }

        @Test
        fun `and so is a call that got no decision, with a warning`() {
            val result = loop().run(ChatRequest(paused + Message.user("Mejor no")))

            assertThat(refund.refunded).isEmpty()
            assertThat(answerTo("call_2")).isEqualTo(ToolLoop.NOT_APPROVED)
            assertThat(result.warnings.map { it.message }).contains(
                "The call call_2 to refund was waiting for approval and the run got no decision about it, so it " +
                    "was answered as not approved",
            )
        }
    }

    @Nested
    inner class `a decision` {
        @Test
        fun `for a call that is not waiting fails before anything runs`() {
            val error = catchThrowableOfType(NoPendingCallError::class.java) {
                loop().run(ChatRequest(paused), decisions = listOf(Approve("call_2"), Approve("call_9")))
            }

            assertThat(error.callId).isEqualTo("call_9")
            assertThat(refund.refunded).isEmpty()
            assertThat(model.requests).isEmpty()
        }

        @Test
        fun `sent twice finds the call already answered, and fails without running it again`() {
            val answered = paused + Message.Tool(listOf(refunded)) + Message.assistant("Listo")

            val error = catchThrowableOfType(NoPendingCallError::class.java) {
                loop().run(ChatRequest(answered), decisions = listOf(Approve("call_2")))
            }

            assertThat(error.callId).isEqualTo("call_2")
            assertThat(refund.refunded).isEmpty()
        }
    }

    @Nested
    inner class `what is kept` {
        @Test
        fun `with a new message, the results go before it`() {
            val question = Message.user("Y cuando llega?")

            loop().run(ChatRequest(paused + question), decisions = listOf(Approve("call_2")))

            assertThat(model.requests.single().messages).isEqualTo(paused + Message.Tool(listOf(refunded)) + question)
        }

        @Test
        fun `what the run added starts with the results of the calls it resumed`() {
            model.answers(listOf(TextPart("Listo")))

            val result = loop().run(ChatRequest(paused), decisions = listOf(Approve("call_2")))

            assertThat(result.newMessages).containsExactly(
                Message.Tool(listOf(refunded)),
                Message.Assistant(listOf(TextPart("Listo"))),
            )
        }

        @Test
        fun `the conversation it keeps has the results right after the answer, before the messages it was given`() {
            val question = Message.user("Y cuando llega?")
            model.answers(listOf(TextPart("El jueves")))

            val result = loop().run(ChatRequest(paused + question), decisions = listOf(Approve("call_2")))

            assertThat(result.keptAfter(paused + question, from = paused.size)).isEqualTo(
                paused + Message.Tool(listOf(refunded)) + question + Message.Assistant(listOf(TextPart("El jueves"))),
            )
        }
    }

    @Nested
    inner class `what is already kept` {
        @Test
        fun `does not change, and the results go after it`() {
            // A session that kept a message after the answer: what an append-only store has cannot move
            val kept = paused + Message.user("Hola?")
            model.answers(listOf(TextPart("Listo")))

            val result = loop().run(ChatRequest(kept), decisions = listOf(Approve("call_2")))

            assertThat(result.keptAfter(kept, from = kept.size))
                .isEqualTo(kept + Message.Tool(listOf(refunded)) + Message.Assistant(listOf(TextPart("Listo"))))
        }
    }

    @Test
    fun `a resumed run can pause again`() {
        val another = ToolCallPart("call_3", "refund", Json.obj("amount" to 600))
        model.answers(listOf(another))

        val result = loop().run(ChatRequest(paused), decisions = listOf(Approve("call_2")))

        assertThat(refund.refunded).containsExactly(500)
        assertThat(result.pending.map { it.call.callId }).containsExactly("call_3")
    }

    @Test
    fun `a stream resumes the same way`() {
        model.answers(listOf(TextPart("Listo")))

        val events = loop().stream(ChatRequest(paused), decisions = listOf(Approve("call_2")))
            .use { it.asSequence().toList() }

        assertThat(refund.refunded).containsExactly(500)
        assertThat(model.requests.single().messages).isEqualTo(paused + Message.Tool(listOf(refunded)))
        assertThat(events.filterIsInstance<RunEvent.ToolFinished>().map { it.result }).containsExactly(refunded)
    }

    @Test
    fun `a resumed stream tells of the approved calls as tools that run, and of the others as not approved`() {
        val another = ToolCallPart("call_3", "refund", Json.obj("amount" to 700))
        val conversation = listOf(paused[0], Message.Assistant(listOf(weatherCall, refundCall, another)), paused[2])
        model.answers(listOf(TextPart("Listo")))

        val events = loop().stream(ChatRequest(conversation), decisions = listOf(Approve("call_2"), Reject("call_3")))
            .use { it.asSequence().toList() }

        assertThat(events.take(4)).containsExactly(
            RunEvent.ToolStarted(refundCall),
            RunEvent.ToolFinished(refunded),
            RunEvent.ToolNotApproved(ToolResultPart("call_3", "refund", Text(ToolLoop.NOT_APPROVED), isError = true)),
            RunEvent.StepStarted(1),
        )
    }

    @Test
    fun `and of a call without a decision as not approved too`() {
        val events = loop().stream(ChatRequest(paused + Message.user("Mejor no")))
            .use { it.asSequence().toList() }

        val notApproved = ToolResultPart("call_2", "refund", Text(ToolLoop.NOT_APPROVED), isError = true)
        assertThat(events.first()).isEqualTo(RunEvent.ToolNotApproved(notApproved))
    }

    private fun loop(hooks: StepHooks? = null) = ToolLoop(
        NextStep { request, _ -> StepSetup(model, request, listOf(weather, refund), hooks = hooks) },
    )

    /** What the model read as the result of [callId]. */
    private fun answerTo(callId: String) = model.requests.single().messages
        .filterIsInstance<Message.Tool>()
        .flatMap { it.results }
        .single { it.callId == callId }
        .let { (it.output as ToolOutput.Text).value }

    private val model = FakeChatModel()
    private val weather = ToolLoopTest.WeatherTool()
    private val refund = RefundTool(approvalOver = 100)
    private val weatherCall = ToolCallPart("call_1", "getWeather", Json.obj("city" to "Bariloche"))
    private val refundCall = ToolCallPart("call_2", "refund", Json.obj("amount" to 500))
    private val refunded = ToolResultPart("call_2", "refund", ToolOutput.Text("Devueltos 500"))

    /** A conversation a run left paused: the weather was looked up, and the refund waits for approval. */
    private val paused = listOf(
        Message.user("Que clima hay y devolveme la compra"),
        Message.Assistant(listOf(weatherCall, refundCall)),
        Message.Tool(listOf(ToolResultPart("call_1", "getWeather", ToolOutput.Text("7 grados en Bariloche")))),
    )
}
