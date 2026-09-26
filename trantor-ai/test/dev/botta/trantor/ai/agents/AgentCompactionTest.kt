@file:Suppress("ClassName")

package dev.botta.trantor.ai.agents

import dev.botta.trantor.ai.history.InMemorySession
import dev.botta.trantor.ai.history.SummaryCompactor
import dev.botta.trantor.ai.models.ModelRegistry
import dev.botta.trantor.ai.models.Usage
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.ai.testing.FakeChatModel
import dev.botta.trantor.ai.testing.FakeCompactor
import dev.botta.trantor.ai.testing.TestTelemetry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * A run that compacts the conversation it keeps once it ended well, when its last call went past a number of tokens.
 */
class AgentCompactionTest {
    @Nested
    inner class `with a session` {
        @Test
        fun `past the tokens it keeps the summary and the last turns in place of what it had`() {
            val session = InMemorySession(earlier)

            val result = runner.run(support, question) { session(session); compaction(compactor, afterTokens = 1000) }

            val whole = earlier + question + result.newMessages
            assertThat(compactor.conversations.single()).isEqualTo(whole)
            assertThat(session.load()).isEqualTo(listOf(Message.Summary("Resumen")) + whole.takeLast(2))
        }

        @Test
        fun `within the tokens it adds what the run added, as always, and does not compact`() {
            val session = InMemorySession(earlier)

            val result = runner.run(support, question) { session(session); compaction(compactor, afterTokens = 5000) }

            assertThat(compactor.conversations).isEmpty()
            assertThat(session.load()).isEqualTo(earlier + question + result.newMessages)
        }

        @Test
        fun `with nothing to compact it adds what the run added`() {
            val session = InMemorySession(earlier)
            compactor.nothingToDo = true

            val result = runner.run(support, question) { session(session); compaction(compactor, afterTokens = 1000) }

            assertThat(session.load()).isEqualTo(earlier + question + result.newMessages)
            assertThat(result.compacted).isNull()
        }

        @Test
        fun `a compaction that fails leaves the run well, adds what it added, and says so`() {
            val session = InMemorySession(earlier)
            compactor.error = IllegalStateException("The summarizer is down")

            val result = runner.run(support, question) { session(session); compaction(compactor, afterTokens = 1000) }

            assertThat(result.text).isEqualTo("Hola")
            assertThat(session.load()).isEqualTo(earlier + question + result.newMessages)
            assertThat(result.warnings.map { it.message })
                .containsExactly("The conversation was not compacted, and was kept as it was: The summarizer is down")
        }
    }

    @Test
    fun `without a session the result has the conversation compacted, for the application to keep`() {
        val result = runner.run(support, earlier + question) { compaction(compactor, afterTokens = 1000) }

        val whole = earlier + question + result.newMessages
        assertThat(result.compacted!!.conversation).isEqualTo(listOf(Message.Summary("Resumen")) + whole.takeLast(2))
    }

    @Test
    fun `the usage of the run counts the call that wrote the summary`() {
        val result = runner.run(support, question) { compaction(compactor, afterTokens = 1000) }

        assertThat(result.usage).isEqualTo(Usage(inputTokens = 950, outputTokens = 210))
    }

    @Nested
    inner class `a stream` {
        @Test
        fun `read to its end compacts what it keeps`() {
            val session = InMemorySession(earlier)

            runner.stream(support, question) { session(session); compaction(compactor, afterTokens = 1000) }
                .use { it.result() }

            assertThat(session.load().first()).isEqualTo(Message.Summary("Resumen"))
        }

        @Test
        fun `closed before its end compacts nothing, as it keeps nothing`() {
            val session = InMemorySession(earlier)
            model.streams(listOf(StreamPart.TextDelta("Ho"), StreamPart.TextDelta("la")))

            runner.stream(support, question) { session(session); compaction(compactor, afterTokens = 1000) }
                .use { it.next() }

            assertThat(compactor.conversations).isEmpty()
            assertThat(session.load()).isEqualTo(earlier)
        }
    }

    @Test
    fun `the call that writes the summary is traced inside the run`() {
        val telemetry = TestTelemetry()
        val summarizer = FakeChatModel(modelId = "summarizer").answers(listOf(TextPart("Resumen")))
        val traced = AgentRunner(ModelRegistry(), openTelemetry = telemetry.openTelemetry)
        val compactor = SummaryCompactor(summarizer, keepTurns = 1, openTelemetry = telemetry.openTelemetry)

        traced.run(support, earlier + question) { compaction(compactor, afterTokens = 1000) }

        assertThat(telemetry.named("chat summarizer").parentSpanId)
            .isEqualTo(telemetry.named("invoke_agent support").spanId)
    }

    private val earlier = listOf(Message.user("Hola"), Message.assistant("Hola!"))
    private val question = Message.user("Que temperatura hay?")
    private val model =
        FakeChatModel(usage = Usage(inputTokens = 900, outputTokens = 200)).answers(listOf(TextPart("Hola")))
    private val support = Agent("support").model(model).instructions("Sos soporte").build()
    private val runner = AgentRunner(ModelRegistry())
    private val compactor = FakeCompactor()
}
