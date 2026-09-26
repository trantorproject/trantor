@file:Suppress("ClassName")

package dev.botta.trantor.ai.generation

import dev.botta.json.Json
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.ai.testing.FakeChatModel
import dev.botta.trantor.ai.tools.*
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ToolLoopApprovalTest {
    @Nested
    inner class pausing {
        @Test
        fun `a call to a tool that needs approval does not run, and the run ends paused with it pending`() {
            val call = refundCall("call_1", 500)
            model.answers(listOf(call))

            val result = loop().run(ChatRequest("Devolveme la compra"))

            assertThat(refund.refunded).isEmpty()
            assertThat(result.paused).isTrue()
            assertThat(result.pending).containsExactly(PendingCall(call, agent = null))
        }

        @Test
        fun `the other calls of the step run, and their results are kept`() {
            model.answers(listOf(weatherCall("call_1", "Bariloche"), refundCall("call_2", 500)))

            val result = loop().run(ChatRequest("Que clima hay y devolveme la compra"))

            assertThat(weather.cities).containsExactly("Bariloche")
            assertThat(result.steps.single().toolResults)
                .containsExactly(ToolResultPart("call_1", "getWeather", ToolOutput.Text("7 grados en Bariloche")))
            assertThat(result.pending.map { it.call.callId }).containsExactly("call_2")
        }

        @Test
        fun `the model is not called again once the run paused`() {
            model.answers(listOf(weatherCall("call_1", "Bariloche"), refundCall("call_2", 500)))

            val result = loop().run(ChatRequest("Que clima hay y devolveme la compra"))

            assertThat(model.requests).hasSize(1)
            assertThat(result.steps).hasSize(1)
            assertThat(result.finishReason).isEqualTo(FinishReasons.ToolCalls)
        }

        @Test
        fun `a tool asks for approval with the args the model sent, so it can ask only for some of them`() {
            model.answers(listOf(refundCall("call_1", 50), refundCall("call_2", 500)))

            val result = loop().run(ChatRequest("Devolveme las dos compras"))

            assertThat(refund.askedFor).containsExactly(50, 500)
            assertThat(refund.refunded).containsExactly(50)
            assertThat(result.pending.map { it.call.callId }).containsExactly("call_2")
        }

        @Test
        fun `a call whose args do not fit its tool goes back to the model as an error without asking`() {
            model.answers(listOf(ToolCallPart("call_1", "refund", Json.obj("amount" to "mucho"))))

            val result = loop().run(ChatRequest("Devolveme la compra"))

            assertThat(refund.askedFor).isEmpty()
            assertThat(result.paused).isFalse()
            assertThat(result.steps.first().toolResults.single().isError).isTrue()
        }

        @Test
        fun `a call a check refused is answered as refused, and not left pending`() {
            model.answers(listOf(refundCall("call_1", 500)))
            val refusing = object: StepHooks {
                override fun checkTool(call: ToolCallPart) = ToolRefusal("No se puede", "refund was refused")
            }

            val result = loop(hooks = refusing).run(ChatRequest("Devolveme la compra"))

            assertThat(result.paused).isFalse()
            assertThat(result.steps.first().toolResults.single())
                .isEqualTo(ToolResultPart("call_1", "refund", ToolOutput.Text("No se puede"), isError = true))
        }

        @Test
        fun `a run that did not pause has nothing pending`() {
            model.answers(listOf(refundCall("call_1", 50)), listOf(TextPart("Listo")))

            val result = loop().run(ChatRequest("Devolveme la compra"))

            assertThat(result.paused).isFalse()
            assertThat(result.pending).isEmpty()
        }

        @Test
        fun `a stream pauses the same way`() {
            model.answers(listOf(weatherCall("call_1", "Bariloche"), refundCall("call_2", 500)))

            val result = loop().stream(ChatRequest("Que clima hay y devolveme la compra")).result()

            assertThat(weather.cities).containsExactly("Bariloche")
            assertThat(refund.refunded).isEmpty()
            assertThat(model.requests).hasSize(1)
            assertThat(result.pending.map { it.call.callId }).containsExactly("call_2")
        }
    }

    @Nested
    inner class `what is kept` {
        @Test
        fun `the answer with every call and the results of those that ran are what the run added`() {
            val weatherCall = weatherCall("call_1", "Bariloche")
            val refundCall = refundCall("call_2", 500)
            model.answers(listOf(weatherCall, refundCall))

            val result = loop().run(ChatRequest("Que clima hay y devolveme la compra"))

            assertThat(result.newMessages).containsExactly(
                Message.Assistant(listOf(weatherCall, refundCall)),
                Message.Tool(listOf(ToolResultPart("call_1", "getWeather", ToolOutput.Text("7 grados en Bariloche")))),
            )
        }

        @Test
        fun `a step where every call is pending adds the answer alone`() {
            val call = refundCall("call_1", 500)
            model.answers(listOf(TextPart("Te hago la devolucion"), call))

            val result = loop().run(ChatRequest("Devolveme la compra"))

            assertThat(result.newMessages)
                .containsExactly(Message.Assistant(listOf(TextPart("Te hago la devolucion"), call)))
        }
    }

    private fun loop(hooks: StepHooks? = null) = ToolLoop(
        NextStep { request, _ -> StepSetup(model, request, listOf(weather, refund), hooks = hooks) },
    )

    private fun weatherCall(callId: String, city: String) =
        ToolCallPart(callId, "getWeather", Json.obj("city" to city))

    private fun refundCall(callId: String, amount: Int) = ToolCallPart(callId, "refund", Json.obj("amount" to amount))

    private val model = FakeChatModel()
    private val weather = ToolLoopTest.WeatherTool()
    private val refund = RefundTool(approvalOver = 100)

    /** Gives money back, which needs a person to approve it past [approvalOver]. */
    class RefundTool(private val approvalOver: Int): Tool<RefundTool.Args>(Args.serializer()) {
        override val name = "refund"
        override val description = "Gives the money of a purchase back"

        val askedFor = mutableListOf<Int>()
        val refunded = mutableListOf<Int>()

        override fun needsApproval(args: Args, context: ToolContext): Boolean {
            askedFor.add(args.amount)
            return args.amount > approvalOver
        }

        override fun execute(args: Args, context: ToolContext): ToolResult {
            refunded.add(args.amount)
            return ToolResult.text("Devueltos ${args.amount}")
        }

        @Serializable
        data class Args(val amount: Int)
    }
}
