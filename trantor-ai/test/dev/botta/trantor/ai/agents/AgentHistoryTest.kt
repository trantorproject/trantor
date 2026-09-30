@file:Suppress("ClassName")

package dev.botta.trantor.ai.agents

import dev.botta.json.Json
import dev.botta.trantor.ai.history.ContextPolicy
import dev.botta.trantor.ai.history.InMemorySession
import dev.botta.trantor.ai.history.LastMessages
import dev.botta.trantor.ai.models.ModelRegistry
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.ai.testing.FakeChatModel
import dev.botta.trantor.ai.tools.*
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** The history of a run of the agents: what goes to the model of it on each call, and where it is kept. */
class AgentHistoryTest {
    @Nested
    inner class `The context policy` {
        @Test
        fun `it decides what each step sends, and the conversation keeps it all`() {
            supportModel.answers(listOf(weatherCall), listOf(TextPart("7 grados")))

            val result = runner.run(support.build(), earlier + question) { contextPolicy(LastMessages(1, step = 1)) }

            assertThat(supportModel.requests[0].messages).containsExactly(instructions, question)
            assertThat(supportModel.requests[1].messages.drop(1).first()).isEqualTo(question)
            assertThat(supportModel.requests[1].messages).hasSize(4)
            assertThat(result.newMessages).hasSize(3)
        }

        @Test
        fun `several go in the order they are given, each one getting what the one before sent`() {
            supportModel.answers(listOf(TextPart("7 grados")))
            val seen = mutableListOf<String>()
            fun keepingLast(name: String, count: Int) = ContextPolicy { messages, _ ->
                seen.add("$name ${messages.size}")
                messages.takeLast(count)
            }

            runner.run(support.build(), earlier + question) {
                contextPolicy(keepingLast("first", 3), keepingLast("second", 1))
            }

            assertThat(seen).containsExactly("first 5", "second 3")
            assertThat(supportModel.requests.single().messages).containsExactly(instructions, question)
        }

        @Test
        fun `it does not get the instructions, and gets the turns of other agents already told`() {
            supportModel.answers(listOf(ToolCallPart("call_1", "transfer_to_sales", Json.obj())))
            salesModel.answers(listOf(TextPart("Cuesta 100 dólares")))
            val seen = mutableListOf<List<Message>>()
            val sales = Agent("sales").model(salesModel).instructions("Sos ventas").build()

            runner.run(support.handoffs("sales").build(), question) {
                team(sales)
                contextPolicy(ContextPolicy { messages, _ -> messages.also { seen.add(it) } })
            }

            assertThat(seen.flatten()).noneMatch { it is Message.System }
            assertThat((seen[1][1] as Message.User).parts.filterIsInstance<TextPart>().first().text)
                .startsWith("For context, this is what other agents of your team said and did")
        }
    }

    @Nested
    inner class `The session` {
        @Test
        fun `the run reads it before the first call, and keeps what it got and what it added once it ended well`() {
            supportModel.answers(listOf(weatherCall), listOf(TextPart("7 grados")))
            val session = InMemorySession(earlier)

            val result = runner.run(support.build(), question) { session(session) }

            assertThat(supportModel.requests[0].messages).isEqualTo(listOf(instructions) + earlier + question)
            assertThat(session.load()).isEqualTo(earlier + question + result.newMessages)
        }

        @Test
        fun `a run that fails keeps nothing`() {
            supportModel.answers(listOf(weatherCall))
            val session = InMemorySession(earlier)

            assertThatThrownBy { runner.run(support.build(), question) { session(session); maxSteps(1) } }

            assertThat(session.load()).isEqualTo(earlier)
        }

        @Test
        fun `nor does one whose afterRun fails, since keeping it is the last thing a run does`() {
            supportModel.answers(listOf(TextPart("7 grados")))
            val session = InMemorySession(earlier)
            val failing = object: AgentHooks {
                override fun afterRun(run: AgentHookContext, result: AgentRunResult) = throw IllegalStateException("No")
            }

            assertThatThrownBy { runner.run(support.hooks(failing).build(), question) { session(session) } }

            assertThat(session.load()).isEqualTo(earlier)
        }

        @Test
        fun `a stream keeps it once read to its end`() {
            supportModel.answers(listOf(TextPart("7 grados")))
            val session = InMemorySession(earlier)

            val result = runner.stream(support.build(), question) { session(session) }.use { it.result() }

            assertThat(session.load()).isEqualTo(earlier + question + result.newMessages)
        }

        @Test
        fun `and one closed before keeps nothing`() {
            supportModel.answers(listOf(TextPart("7 grados")))
            val session = InMemorySession(earlier)

            runner.stream(support.build(), question) { session(session) }.use { it.next() }

            assertThat(session.load()).isEqualTo(earlier)
        }

        @Test
        fun `the dynamic instructions never reach it, since they are no message`() {
            supportModel.answers(listOf(TextPart("7 grados")))
            val session = InMemorySession()

            runner.run(support.dynamicInstructions("Son las 10").build(), question) { session(session) }

            assertThat(supportModel.requests.single().dynamicSystem).isEqualTo("Son las 10")
            assertThat(session.load().toString()).doesNotContain("Son las 10")
        }
    }

    private val weatherCall = ToolCallPart("call_1", "getWeather", Json.obj("city" to "Bariloche"))
    private val supportModel = FakeChatModel(modelId = "support-model")
    private val salesModel = FakeChatModel(modelId = "sales-model")
    private val instructions = Message.system("Sos soporte")
    private val support = Agent("support").model(supportModel).instructions("Sos soporte").tools(WeatherTool())
    private val runner = AgentRunner(ModelRegistry())
    private val earlier = listOf(
        Message.user("Hola"), Message.Assistant(listOf(TextPart("Hola!")), agent = "support"),
        Message.user("Cómo estás?"), Message.Assistant(listOf(TextPart("Bien")), agent = "support"),
    )
    private val question = Message.user("Que temperatura hay?")

    class WeatherTool: Tool<WeatherTool.Args>() {
        override val name = "getWeather"
        override val description = "The weather"

        override fun execute(args: Args, context: ToolContext) = ToolResult.text("7 grados en ${args.city}")

        data class Args(val city: String)
    }
}
