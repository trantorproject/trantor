@file:Suppress("ClassName")

package dev.botta.trantor.ai.agents

import dev.botta.json.Json
import dev.botta.json.values.JsonObject
import dev.botta.trantor.ai.generation.Approve
import dev.botta.trantor.ai.generation.Reject
import dev.botta.trantor.ai.generation.ToolLoop
import dev.botta.trantor.ai.history.InMemorySession
import dev.botta.trantor.ai.models.ModelRegistry
import dev.botta.trantor.ai.models.chat.ChatModel
import dev.botta.trantor.ai.models.chat.Message
import dev.botta.trantor.ai.providers.anthropic.AnthropicChatModel
import dev.botta.trantor.ai.providers.anthropic.AnthropicConfig
import dev.botta.trantor.ai.providers.openai.OpenAIChatModel
import dev.botta.trantor.ai.providers.openai.OpenAIConfig
import dev.botta.trantor.ai.testing.FakeHttpClient
import dev.botta.trantor.ai.tools.Tool
import dev.botta.trantor.ai.tools.ToolContext
import dev.botta.trantor.ai.tools.ToolResult
import dev.botta.trantor.primitives.serialization.Description
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * A run that pauses on a refund and the one that picks it up, against what the providers really answered: the order
 * is looked up, the refund waits for approval, and the conversation goes on approving it, rejecting it, or with a new
 * message and no decision. What each provider took is the conversation picked up: the result of a call that comes a
 * run later, a rejection, and a result before a message of the user. o4-mini asked for one tool per step, and Claude
 * for both in one.
 */
class AgentApprovalRecordedTest {
    @Nested
    inner class `on o4-mini` {
        @Test
        fun `a paused run keeps the lookup and waits with the refund`() {
            http.answers(*fixtures("openai/approval", 2))

            val paused = pause(openAI())

            assertThat(orders.looked).containsExactly(42)
            assertThat(paused.pending.single().call.input).isEqualTo(Json.obj("order" to 41, "amount" to 250))
        }

        @Test
        fun `approved, the result goes back after its call, and the model says it gave the money back`() {
            http.answers(*fixtures("openai/approval", 3))
            val model = openAI()
            val callId = pause(model).pending.single().call.callId

            val resumed = agents.run(support(model)) { session(kept); decisions(Approve(callId)) }

            val input = inputOf(http.requests.last().body as String)
            val output = input.indexOf("function_call_output", callId)
            assertThat(output).isEqualTo(input.indexOf("function_call", callId) + 1)
            assertThat(input[output]["output"]?.asString()).isEqualTo("Devueltos 250 dólares del pedido 41")
            assertThat(refund.refunded).containsExactly(250)
            assertThat(resumed.text).contains("250")
        }

        @Test
        fun `rejected, the model reads why and does not try it again`() {
            http.answers(*fixtures("openai/rejection", 3))
            val model = openAI()
            val callId = pause(model).pending.single().call.callId

            val resumed = agents.run(support(model)) { session(kept); decisions(Reject(callId)) }

            val input = inputOf(http.requests.last().body as String)
            assertThat(input[input.indexOf("function_call_output", callId)]["output"]?.asString())
                .isEqualTo(ToolLoop.NOT_APPROVED)
            assertThat(refund.refunded).isEmpty()
            assertThat(resumed.steps.single().step.response.toolCalls).isEmpty()
        }

        @Test
        fun `with a new message and no decision, the result goes before the message`() {
            http.answers(*fixtures("openai/approval-new-message", 3))
            val model = openAI()
            val callId = pause(model).pending.single().call.callId

            val resumed = agents.run(support(model), Message.user(newMessage)) { session(kept) }

            val input = inputOf(http.requests.last().body as String)
            val output = input.indexOf("function_call_output", callId)
            val message = input.indexOfLast { it["role"]?.asString() == "user" }
            assertThat(output).isLessThan(message)
            assertThat(input[output]["output"]?.asString()).isEqualTo(ToolLoop.NOT_APPROVED)
            assertThat(resumed.warnings.map { it.message }).anyMatch { "got no decision" in it }
        }

        private fun openAI() = OpenAIChatModel("o4-mini", OpenAIConfig("sk-test"), http)

        private fun inputOf(body: String) =
            Json.parse(body).asObject()!!.getValue("input").asArray()!!.map { it.asObject()!! }

        /** Where the item of [type] about [callId] is in the input. */
        private fun List<JsonObject>.indexOf(type: String, callId: String) =
            indexOfFirst { it["type"]?.asString() == type && it["call_id"]?.asString() == callId }
    }

    @Nested
    inner class `on Claude Sonnet 4-5` {
        @Test
        fun `a paused run keeps the lookup and waits with the refund`() {
            http.answers(*fixtures("anthropic/approval", 1))

            val paused = pause(claude())

            assertThat(orders.looked).containsExactly(42)
            assertThat(paused.pending.single().call.input).isEqualTo(Json.obj("order" to 41, "amount" to 250))
        }

        @Test
        fun `approved, the result goes back with the other one, and the model says it gave the money back`() {
            http.answers(*fixtures("anthropic/approval", 2))
            val model = claude()
            val callId = pause(model).pending.single().call.callId

            val resumed = agents.run(support(model)) { session(kept); decisions(Approve(callId)) }

            val messages = messagesOf(http.requests.last().body as String)
            val results = messages.last().contentOf()
            assertThat(messages.last()["role"]?.asString()).isEqualTo("user")
            assertThat(results.map { it["type"]?.asString() }).containsExactly("tool_result", "tool_result")
            assertThat(results.last()["tool_use_id"]?.asString()).isEqualTo(callId)
            assertThat(results.last()["content"]?.asString()).isEqualTo("Devueltos 250 dólares del pedido 41")
            assertThat(refund.refunded).containsExactly(250)
            assertThat(resumed.text).contains("250")
        }

        @Test
        fun `rejected, the model reads it as an error and does not try it again`() {
            http.answers(*fixtures("anthropic/rejection", 2))
            val model = claude()
            val callId = pause(model).pending.single().call.callId

            val resumed = agents.run(support(model)) { session(kept); decisions(Reject(callId)) }

            val result = messagesOf(http.requests.last().body as String).last().contentOf()
                .single { it["tool_use_id"]?.asString() == callId }
            assertThat(result["is_error"]?.asBoolean()).isTrue()
            assertThat(result["content"]?.asString()).isEqualTo(ToolLoop.NOT_APPROVED)
            assertThat(refund.refunded).isEmpty()
            assertThat(resumed.steps.single().step.response.toolCalls).isEmpty()
        }

        @Test
        fun `with a new message and no decision, the results go first in the message of the user`() {
            http.answers(*fixtures("anthropic/approval-new-message", 2))
            val model = claude()
            pause(model)

            val resumed = agents.run(support(model), Message.user(newMessage)) { session(kept) }

            val last = messagesOf(http.requests.last().body as String).last()
            assertThat(last["role"]?.asString()).isEqualTo("user")
            assertThat(last.contentOf().map { it["type"]?.asString() })
                .containsExactly("tool_result", "tool_result", "text")
            assertThat(last.contentOf().last()["text"]?.asString()).isEqualTo(newMessage)
            assertThat(resumed.warnings.map { it.message }).anyMatch { "got no decision" in it }
        }

        private fun claude() = AnthropicChatModel("claude-sonnet-4-5", AnthropicConfig(apiKey = "sk-ant-test"), http)

        private fun messagesOf(body: String) =
            Json.parse(body).asObject()!!.getValue("messages").asArray()!!.map { it.asObject()!! }

        private fun JsonObject.contentOf() = getValue("content").asArray()!!.map { it.asObject()!! }
    }

    private fun pause(model: ChatModel) = agents.run(support(model), Message.user(question)) { session(kept) }

    /** The agent the recordings were made with, in trantor-tester, as it was then. */
    private fun support(model: ChatModel) = Agent("support")
        .model(model)
        .instructions(
            "Sos soporte de una tienda. Usá getOrder para ver un pedido y refund para devolver plata. " +
                "Hacé todo lo que te pidan en el mismo paso. Respondé corto.",
        )
        .tools(orders, refund)
        .build()

    private fun fixtures(name: String, count: Int) = (1..count).map { fixture("$name-$it") }.toTypedArray()

    private fun fixture(name: String) =
        javaClass.getResource("/$name.json")?.readText() ?: error("Missing fixture $name")

    private val http = FakeHttpClient()
    private val agents = AgentRunner(ModelRegistry())
    private val kept = InMemorySession()
    private val orders = OrderTool()
    private val refund = RefundTool()
    private val question = "Decime en qué estado está el pedido 42 y devolveme los 250 dólares del pedido 41."
    private val newMessage = "Mejor dejalo. ¿Cuándo llega el pedido 42?"

    /** The tools the recordings were made with, answering what they answered then. */
    class OrderTool: Tool<OrderTool.Args>() {
        override val name = "getOrder"
        override val description = "The state of an order of the store"
        override val readOnly = true

        val looked = mutableListOf<Int>()

        override fun execute(args: Args, context: ToolContext): ToolResult {
            looked.add(args.order)
            return ToolResult.text("El pedido ${args.order} está en camino, llega el jueves")
        }

        data class Args(@Description("The number of the order") val order: Int)
    }

    class RefundTool: Tool<RefundTool.Args>() {
        override val name = "refund"
        override val description = "Gives back the money of an order, in dollars"

        val refunded = mutableListOf<Int>()

        override fun needsApproval(args: Args, context: ToolContext) = args.amount > 100

        override fun execute(args: Args, context: ToolContext): ToolResult {
            refunded.add(args.amount)
            return ToolResult.text("Devueltos ${args.amount} dólares del pedido ${args.order}")
        }

        data class Args(
            @Description("The number of the order") val order: Int,
            @Description("How many dollars to give back") val amount: Int,
        )
    }
}
