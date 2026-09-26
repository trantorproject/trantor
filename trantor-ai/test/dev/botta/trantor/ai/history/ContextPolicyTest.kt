@file:Suppress("ClassName")

package dev.botta.trantor.ai.history

import dev.botta.json.Json
import dev.botta.trantor.ai.RunContext
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.ai.tools.ToolOutput
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** What part of the conversation goes to the model, without touching what the application keeps. */
class ContextPolicyTest {
    @Nested
    inner class `LastMessages` {
        @Test
        fun `a conversation within the limit goes whole`() {
            val conversation = turns(3)

            assertThat(LastMessages(6).project(conversation, run)).isEqualTo(conversation)
        }

        @Test
        fun `past the limit it keeps the last ones, starting at what the user said`() {
            val conversation = turns(4)

            val sent = LastMessages(5, step = 1).project(conversation, run)

            assertThat(sent).isEqualTo(conversation.drop(4))
        }

        @Test
        fun `it cuts in steps, so what goes first stays the same from one call to the next`() {
            // A window that moves one message at a time changes the start of every request, and the cache with it
            val policy = LastMessages(4, step = 4)

            val firsts = (5..8).map { size -> policy.project(turns(4).take(size), run).first() }

            assertThat(firsts.distinct()).hasSize(1)
        }

        @Test
        fun `it never starts with the result of a tool, nor leaves a call without its result`() {
            val conversation = listOf(
                Message.user("Hola"), Message.assistant("Hola!"),
                Message.user("Clima?"), callOf("call_1"), resultOf("call_1"), Message.assistant("7 grados"),
                Message.user("Y en Lima?"), Message.assistant("20 grados"),
            )

            val sent = LastMessages(4, step = 1).project(conversation, run)

            assertThat(sent).isEqualTo(conversation.drop(6))
        }

        @Test
        fun `with nothing the user said after the cut, it starts at the last thing they said before it`() {
            val conversation = listOf(
                Message.user("Clima?"),
                callOf("call_1"), resultOf("call_1"),
                callOf("call_2"), resultOf("call_2"),
                callOf("call_3"), resultOf("call_3"),
            )

            val sent = LastMessages(3, step = 1).project(conversation, run)

            assertThat(sent).isEqualTo(conversation)
        }

        @Test
        fun `it takes a limit and a step that make sense`() {
            assertThatThrownBy { LastMessages(0) }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { LastMessages(4, step = 5) }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Nested
    inner class `DropOldToolResults` {
        @Test
        fun `it replaces what the old results said and leaves the recent ones`() {
            val conversation = (1..4).flatMap { listOf(callOf("call_$it"), resultOf("call_$it")) }

            val sent = DropOldToolResults(keep = 2, step = 1).project(conversation, run)

            assertThat(outputsOf(sent)).containsExactly(REMOVED, REMOVED, "7 grados", "7 grados")
            assertThat(sent.filterIsInstance<Message.Assistant>())
                .isEqualTo(conversation.filterIsInstance<Message.Assistant>())
        }

        @Test
        fun `it replaces them in steps, keeping at least the last ones it was told`() {
            val policy = DropOldToolResults(keep = 2, step = 3)

            val kept = (2..6).map { calls ->
                val conversation = (1..calls).flatMap { listOf(callOf("call_$it"), resultOf("call_$it")) }
                outputsOf(policy.project(conversation, run)).count { it != REMOVED }
            }

            assertThat(kept).containsExactly(2, 3, 4, 2, 3)
        }
    }

    @Test
    fun `no policy cuts the summary the conversation starts with, nor the system messages before it`() {
        val summary = Message.Summary("Nico viaja a Bariloche en julio")
        val conversation = listOf(Message.system("Sos soporte"), summary) +
            (1..6).flatMap { listOf(Message.user("Pregunta $it"), Message.assistant("Respuesta $it")) }

        val sent = projected(listOf(LastMessages(2, step = 2)), conversation, RunContext())

        assertThat(sent.take(2)).containsExactly(Message.system("Sos soporte"), summary)
        assertThat(sent.drop(2)).containsExactly(Message.user("Pregunta 6"), Message.assistant("Respuesta 6"))
    }

    @Test
    fun `KeepAll sends it all`() {
        val conversation = turns(10)

        assertThat(KeepAll.project(conversation, run)).isEqualTo(conversation)
    }

    private fun turns(count: Int) =
        (1..count).flatMap { listOf(Message.user("Pregunta $it"), Message.assistant("Respuesta $it")) }

    private fun callOf(callId: String) = Message.Assistant(listOf(ToolCallPart(callId, "getWeather", Json.obj())))

    private fun resultOf(callId: String) =
        Message.toolResult(ToolResultPart(callId, "getWeather", ToolOutput.Text("7 grados")))

    private fun outputsOf(messages: List<Message>) =
        messages.filterIsInstance<Message.Tool>().flatMap { it.results }.map { (it.output as ToolOutput.Text).value }

    private val run = RunContext()

    private companion object {
        const val REMOVED = "This result was removed to save context."
    }
}
