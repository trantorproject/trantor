package dev.botta.trantor.ai.history

import dev.botta.json.Json
import dev.botta.trantor.ai.errors.ProviderUnavailableError
import dev.botta.trantor.ai.models.CallOptions
import dev.botta.trantor.ai.models.ResponseInfo
import dev.botta.trantor.ai.models.Usage
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.ai.testing.FakeChatModel
import dev.botta.trantor.ai.tools.ToolOutput
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

class SummaryCompactorTest {
    @Test
    fun `keeps the system messages and the last turns as they are, and a summary of the rest between them`() {
        val conversation = listOf(system) + turn(1) + turn(2) + turn(3) + turn(4)

        val compacted = SummaryCompactor(model, keepTurns = 2).compact(conversation)!!

        assertThat(compacted.conversation)
            .containsExactlyElementsOf(listOf(system, Message.Summary("Resumen")) + turn(3) + turn(4))
        assertThat(compacted.summary).isEqualTo(Message.Summary("Resumen"))
    }

    @Test
    fun `a turn starts at what the user said, so a call and its result stay together`() {
        val withTools = listOf(
            Message.user("Que clima hay?"),
            Message.Assistant(listOf(ToolCallPart("call_1", "getWeather", Json.obj("city" to "Bariloche")))),
            Message.toolResult(ToolResultPart("call_1", "getWeather", ToolOutput.Text("7 grados"))),
            Message.assistant("Hacen 7 grados"),
        )
        val conversation = turn(1) + withTools

        val compacted = SummaryCompactor(model, keepTurns = 1).compact(conversation)!!

        assertThat(compacted.conversation).containsExactlyElementsOf(listOf(Message.Summary("Resumen")) + withTools)
    }

    @Test
    fun `with nothing older than the turns it keeps, it does nothing and asks no model`() {
        val conversation = listOf(system) + turn(1) + turn(2)

        val compacted = SummaryCompactor(model, keepTurns = 2).compact(conversation)

        assertThat(compacted).isNull()
        assertThat(model.requests).isEmpty()
    }

    @Test
    fun `summarizes the summary before with what came after it, and the new one takes its place`() {
        val conversation = listOf(system, Message.Summary("Nico viaja en julio")) + turn(1) + turn(2)

        val compacted = SummaryCompactor(model, keepTurns = 1).compact(conversation)!!

        assertThat(compacted.conversation)
            .containsExactlyElementsOf(listOf(system, Message.Summary("Resumen")) + turn(2))
        assertThat(transcript()).startsWith("[summary of what came before] Nico viaja en julio")
    }

    @Test
    fun `the model reads the instructions and the old part told line by line, with who said and did what`() {
        val conversation = listOf(
            Message.user("Cuanto sale ir a Bariloche?"),
            Message.Assistant(
                listOf(
                    ReasoningPart("Lo busco"),
                    TextPart("Lo busco"),
                    ToolCallPart("call_1", "getPrice", Json.obj("city" to "Bariloche")),
                ),
                agent = "sales",
            ),
            Message.toolResult(ToolResultPart("call_1", "getPrice", ToolOutput.Text("1800 dolares"))),
            Message.Assistant(listOf(TextPart("Cuesta 1800 dolares")), agent = "sales"),
        ) + turn(2)

        SummaryCompactor(model, keepTurns = 1).compact(conversation)

        assertThat(model.request!!.messages.first()).isEqualTo(Message.system(SummaryCompactor.DEFAULT_INSTRUCTIONS))
        assertThat(transcript()).isEqualTo(
            """
            [user] said: Cuanto sale ir a Bariloche?
            [sales] said: Lo busco
            [sales] called getPrice with {"city":"Bariloche"}
            [sales] got from getPrice: 1800 dolares
            [sales] said: Cuesta 1800 dolares
            """.trimIndent(),
        )
    }

    @Test
    fun `the instructions of the application take the place of the ones of Trantor`() {
        val conversation = turn(1) + turn(2)

        SummaryCompactor(model, keepTurns = 1, instructions = "Resumi en una linea").compact(conversation)

        assertThat(model.request!!.messages.first()).isEqualTo(Message.system("Resumi en una linea"))
    }

    @Test
    fun `a model that writes no summary, or one cut short, fails with what it answered and replaces nothing`() {
        val conversation = turn(1) + turn(2)
        val silent = FakeChatModel()
        silent.responses.add(response("", FinishReasons.Stop))
        silent.responses.add(response("Nico viaja", FinishReasons.Length))

        repeat(2) {
            assertThatThrownBy { SummaryCompactor(silent, keepTurns = 1).compact(conversation) }
                .isInstanceOf(NoSummaryWrittenError::class.java)
        }
    }

    @Test
    fun `an error of the model comes out as it was`() {
        val down = ProviderUnavailableError("fake")
        val failing = object: ChatModel by model {
            override fun generate(request: ChatRequest, options: CallOptions): ChatResponse = throw down
        }

        assertThatThrownBy { SummaryCompactor(failing, keepTurns = 1).compact(turn(1) + turn(2)) }.isSameAs(down)
    }

    @Test
    fun `says what the call that wrote the summary used, for the run that asked for it`() {
        val counted = FakeChatModel(usage = Usage(inputTokens = 300, outputTokens = 40))

        val compacted = SummaryCompactor(counted, keepTurns = 1).compact(turn(1) + turn(2))!!

        assertThat(compacted.response.usage).isEqualTo(Usage(inputTokens = 300, outputTokens = 40))
    }

    /** What the model was asked to summarize: the one user message it got. */
    private fun transcript() = (model.request!!.messages.last() as Message.User).parts
        .filterIsInstance<TextPart>().joinToString("") { it.text }

    private fun turn(n: Int) = listOf(Message.user("Pregunta $n"), Message.assistant("Respuesta $n"))

    private fun response(text: String, finishReason: FinishReasons) = ChatResponse(
        listOf(TextPart(text)),
        finishReason,
        ResponseInfo(model = "fake-model", provider = "fake", latency = 1.milliseconds),
    )

    private val system = Message.system("Sos soporte")
    private val model = FakeChatModel().answers(listOf(TextPart("Resumen")))
}
