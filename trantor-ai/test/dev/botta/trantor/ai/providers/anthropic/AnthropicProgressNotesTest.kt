package dev.botta.trantor.ai.providers.anthropic

import dev.botta.json.Json
import dev.botta.json.values.JsonObject
import dev.botta.trantor.ai.generation.RunEvent
import dev.botta.trantor.ai.generation.ToolLoop
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.ai.testing.FakeHttpClient
import dev.botta.trantor.ai.tools.Tool
import dev.botta.trantor.ai.tools.ToolContext
import dev.botta.trantor.ai.tools.ToolResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Claude Sonnet 5.5 through a chain of four tools, telling the user a paragraph of what it found after each result,
 * against what Anthropic really answered: with `between_tools` (Reasoning.Off), and with its notes asked for apart
 * (nothing said about thinking). The note after the list of orders was long enough to come back as a thinking block
 * with its text, and Anthropic took it back on the calls after it.
 */
class AnthropicProgressNotesTest {
    @Test
    fun `a note between tool calls is told apart from thinking`() {
        http.answers(*recording("thinking/notes-between-%d.json"))

        val result = loop().run(request(Reasoning.Off))

        val note = result.steps[2].response.content.filterIsInstance<ReasoningPart>().single()
        assertThat(note.note).isTrue()
        assertThat(note.text).startsWith("Encontré dos pedidos tuyos: O-3 (20/09/2026) y O-1 (02/08/2026).")
    }

    @Test
    fun `and goes back as the thinking block it came as`() {
        http.answers(*recording("thinking/notes-between-%d.json"))
        val recorded = Json.parse(fixture("thinking/notes-between-3.json")).asObject()!!["content"]!!.asArray()!![0]

        loop().run(request(Reasoning.Off))

        val answer = sent(3)["messages"]!!.asArray()!![5].asObject()!!
        assertThat(answer["content"]!!.asArray()!![0]).isEqualTo(recorded)
    }

    @Test
    fun `in a stream it comes as a note, piece by piece, and nothing as thinking`() {
        http.answers(*recording("thinking/notes-updates-stream-%d.txt"))
        val parts = mutableListOf<StreamPart>()

        val result = loop().stream(request()).use { stream ->
            stream.forEach { if (it is RunEvent.Model) parts.add(it.part) }
            stream.result()
        }

        assertThat(parts.filterIsInstance<StreamPart.NoteDelta>().joinToString("") { it.text })
            .startsWith("Ana, encontré dos pedidos a tu nombre")
        assertThat(parts.filterIsInstance<StreamPart.ReasoningDelta>()).isEmpty()
        assertThat(result.steps[2].response.content.filterIsInstance<ReasoningPart>().single().note).isTrue()
    }

    @Test
    fun `asked for a summary of the thinking, a block with text is thinking, since a note cannot be told from it`() {
        http.body = fixture("thinking/notes-between-3.json")
        val summarized = Reasoning.effort(ReasoningEfforts.Low, ReasoningSummaries.Auto)

        val response = model.generate(request(summarized))

        assertThat(response.content.filterIsInstance<ReasoningPart>().single().note).isFalse()
    }

    private fun loop() = ToolLoop(model, tools)

    private fun request(reasoning: Reasoning? = null) = ChatRequest(
        listOf(
            Message.system(
                "Sos el soporte de una tienda. Cada vez que recibas un resultado, contale al usuario en un párrafo " +
                    "lo que encontraste y cómo seguís.",
            ),
            Message.user("Soy Ana Pérez. ¿Dónde está mi último pedido?"),
        ),
        settings = ChatSettings(reasoning = reasoning),
    )

    private fun sent(call: Int) = Json.parse(http.requests[call].body as String).asObject()!!

    private fun recording(pattern: String) = (1..5).map { fixture(pattern.format(it)) }.toTypedArray()

    private fun fixture(name: String) =
        javaClass.getResource("/anthropic/$name")?.readText() ?: error("Missing fixture $name")

    private val http = FakeHttpClient()
    private val model = AnthropicChatModel("claude-sonnet-5-5", AnthropicConfig(apiKey = "sk-ant-test"), http)

    /** The tools the recording was made with, answering what they answered then. */
    private val tools = listOf(
        Answering("findCustomer", "Finds a customer by name, and gives its id", Json.obj("id" to "C-17")),
        Answering(
            "listOrders",
            "The orders of a customer, by customer id",
            Json.obj(
                "orders" to Json.array(
                    Json.obj("id" to "O-3", "date" to "2026-09-20"),
                    Json.obj("id" to "O-1", "date" to "2026-08-02"),
                ),
            ),
        ),
        Answering("getOrder", "An order by id, with its tracking code", Json.obj("id" to "O-3", "tracking" to "ZX-12")),
        Answering(
            "trackPackage",
            "Where a package is, by tracking code",
            Json.obj("location" to "Rosario", "eta" to "mañana antes de las 18"),
        ),
    )

    class Answering(override val name: String, override val description: String, private val answer: JsonObject):
        Tool<Answering.Args>() {
        override fun execute(args: Args, context: ToolContext) = ToolResult.json(answer)

        data class Args(val value: String)
    }
}
